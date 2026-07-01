package com.redstone.chestcapacity;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 水位缓冲搬运服务：每隔 transfer-interval-ticks 扫描所有已登记的扩容箱，
 * 维持物理 27 格处于 keep-filled-slots 目标水位。
 *
 *   物理非空槽 occupied 与目标水位 target 的关系决定单向搬运方向：
 *     occupied > target  ->  下沉：把物理格多余物品塞进虚拟存储(ChestData.push)
 *     occupied < target  ->  补货：从虚拟存储抽物品补进物理格(ChestData.pull)
 *     occupied == target ->  不动(滞回，避免来回震荡)
 *
 *   一个 keep-filled-slots 覆盖三种红石语义：
 *     0  => 尽量清空物理格 => 无限吃货箱
 *     27 => 尽量填满物理格 => 无限供货箱
 *     中间 => 双向缓冲
 *
 * 性能护栏：
 *   · 跳过未加载区块的箱子（不强制加载，红石服友好）。
 *   · 每箱每 tick 最多搬 transfer-batch-per-chest 个堆叠。
 *   · 只操作主线程；实体/方块/库存 API 均线程安全前提在主线程。
 *   · 搬运造成内容变化的箱子登记为 dirty，交给上层刷新悬浮文字。
 */
public final class TransferService {

    private final Plugin plugin;
    private final PluginConfig config;
    private final VirtualStore store;
    private final HologramManager holograms;
    private final ChestGui gui;   // 用于对打开着的界面做"吸收编辑 -> 搬运 -> 回显"三步对账

    private BukkitTask task;

    public TransferService(Plugin plugin, PluginConfig config,
                           VirtualStore store, HologramManager holograms, ChestGui gui) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.holograms = holograms;
        this.gui = gui;
    }

    public void start() {
        if (!config.bufferEnabled) {
            plugin.getLogger().info("水位缓冲已关闭，扩容箱仅作 GUI 大仓库。");
            return;
        }
        long interval = config.transferIntervalTicks;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /**
     * 一轮扫描：遍历所有登记箱子，各自向目标水位靠拢一步。
     *
     * 对有人打开界面的箱子，围绕搬运做三步对账，实现"边看边流动"：
     *   1. absorbEdits : 先把玩家在界面里的存/取写回 data（玩家动作即时入账）
     *   2. balanceOne  : 照常搬运，物理27格与 data 互通（漏斗喂的货下沉进来）
     *   3. refreshViews: 把 data 最新内容回显到界面（玩家看到堆叠增减 = 流入流出）
     * 三步串行且同在主线程，tick 结尾界面与 data 恒一致，不存在过期快照覆盖。
     */
    private void tick() {
        int target = config.keepFilledSlots;
        for (Map.Entry<String, ChestData> e : new ArrayList<>(store.entries())) {
            String key = e.getKey();
            Block block = VirtualStore.blockOf(key);
            if (block == null) continue;                       // 世界未加载
            if (!block.getWorld().isChunkLoaded(
                    block.getX() >> 4, block.getZ() >> 4)) continue;  // 区块未加载, 跳过
            BlockState state = block.getState(false);
            if (!(state instanceof Chest chest)) continue;     // 已不是箱子(被换走)

            boolean viewed = gui.hasOpenView(key);
            if (viewed) gui.absorbEdits(key);                  // 1. 吸收玩家编辑

            ChestData d = e.getValue();
            boolean changed = balanceOne(chest.getInventory(), d, target); // 2. 搬运

            if (viewed) gui.refreshViews(key);                 // 3. 回显给玩家(即便本 tick 没搬也刷, 反映其他来源改动)
            if (changed) holograms.refresh(block, d.usedStacks(), d.capacity(), d.pages());
        }
    }

    /**
     * 让单个箱子的物理库存向目标水位靠拢一步。返回是否发生了内容变化(用于刷新悬浮文字)。
     */
    private boolean balanceOne(Inventory physical, ChestData data, int target) {
        int occupied = countNonEmpty(physical);
        if (occupied > target) return sink(physical, data, occupied - target);
        if (occupied < target) return source(physical, data, target - occupied);
        return false;
    }

    /** 下沉：把物理格里的物品搬进虚拟存储，直到腾出 slotsToFree 个槽或达到限流。 */
    private boolean sink(Inventory physical, ChestData data, int slotsToFree) {
        int budget = config.transferBatchPerChest;
        boolean changed = false;
        ItemStack[] contents = physical.getContents();
        for (int i = 0; i < contents.length && budget > 0 && slotsToFree > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) continue;
            ItemStack rest = data.push(s);          // 尽量塞进虚拟存储
            if (rest == null) {                     // 整槽搬空
                physical.setItem(i, null);
                slotsToFree--;
            } else if (rest.getAmount() < s.getAmount()) {
                physical.setItem(i, rest);          // 部分搬入
            } else {
                continue;                           // 虚拟存储满了, 这槽搬不动
            }
            budget--;
            changed = true;
        }
        return changed;
    }

    /** 补货：从虚拟存储抽物品补进物理格，直到补足 slotsToFill 或达到限流。 */
    private boolean source(Inventory physical, ChestData data, int slotsToFill) {
        int budget = config.transferBatchPerChest;
        boolean changed = false;
        while (budget > 0 && slotsToFill > 0) {
            int empty = firstEmpty(physical);
            if (empty < 0) break;                   // 物理格已满
            ItemStack pulled = data.pull(64);       // 抽一个堆叠
            if (pulled == null) break;              // 虚拟存储已空
            physical.setItem(empty, pulled);
            slotsToFill--;
            budget--;
            changed = true;
        }
        return changed;
    }

    private static int countNonEmpty(Inventory inv) {
        int n = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && !s.getType().isAir()) n++;
        }
        return n;
    }

    private static int firstEmpty(Inventory inv) {
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null || c[i].getType().isAir()) return i;
        }
        return -1;
    }
}