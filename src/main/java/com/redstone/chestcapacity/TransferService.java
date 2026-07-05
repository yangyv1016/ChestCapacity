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
 * 维持物理 27 格处于滞回双阈值 [keep-filled-low, keep-filled-high] 区间内。
 *
 *   物理非空槽 occupied 与阈值区间的关系决定搬运方向：
 *     occupied > high  ->  下沉：把物理格多余物品塞进虚拟存储(ChestData.push)
 *     occupied < low   ->  补货：从虚拟存储抽物品补进物理格(ChestData.pull)
 *     low..high        ->  不动(滞回带，避免每 tick 来回震荡)
 *
 *   阈值区间覆盖三种红石语义：
 *     low=0,  high=0   => 尽量清空物理格 => 无限吃货箱(只进不出)
 *     low=27, high=27  => 尽量填满物理格 => 无限供货箱(只出不进)
 *     low<high(如 1~26) => 双向缓冲(漏斗既能塞进也能抽出)
 *   low=high 退化为旧的单一水位行为, 与旧 keep-filled-slots 配置一致。
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
            // 只搬本半的物理 27 格(getBlockInventory)。双联时 getInventory 会返回合并的 54 格,
            // 导致水位计数按两半合计、搬运串味; getBlockInventory 严格只取当前方块这一半。
            boolean changed = balanceOne(chest.getBlockInventory(), d); // 2. 搬运

            if (viewed) gui.refreshViews(key);                 // 3. 回显给玩家(即便本 tick 没搬也刷, 反映其他来源改动)
            if (changed) holograms.syncBlock(block);           // 内容变了 -> 刷该配对悬浮字(幂等, 双联两半重复调用也安全)
        }
    }

    /**
     * 让单个箱子的物理库存回到滞回带 [low, high] 内, 靠拢一步。
     * 返回是否发生了内容变化(用于刷新悬浮文字)。
     *
     *   occupied > high -> 下沉多余到 high(上游塞满 -> 腾格给漏斗继续塞)
     *   occupied < low  -> 补货到 low   (下游抽空 -> 补回物理格给漏斗继续抽)
     *   low..high       -> 不动(滞回, 避免震荡)
     * low=high 时退化为旧的单一水位行为, 与旧配置一致。
     */
    private boolean balanceOne(Inventory physical, ChestData data) {
        int occupied = countNonEmpty(physical);
        if (occupied > config.keepFilledHigh) return sink(physical, data, occupied - config.keepFilledHigh);
        if (occupied < config.keepFilledLow) return source(physical, data, config.keepFilledLow - occupied);
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
            } else if (data.voidOverflow()) {       // 虚拟存储满且开了溢出销毁 -> 直接丢弃这槽, 保证红石不堵
                physical.setItem(i, null);
                slotsToFree--;
            } else {
                continue;                           // 虚拟存储满且未开销毁, 这槽搬不动
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
            ItemStack pulled = data.pull(config.refillBatch);  // 抽 refill-batch 个(默认1, GUI逐个减少贴合原版)
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