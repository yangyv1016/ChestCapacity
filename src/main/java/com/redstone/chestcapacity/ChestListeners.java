package com.redstone.chestcapacity;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 把各模块粘合到 Bukkit 事件上的唯一监听器。数据流的“驱动层”。
 *
 *   放置扩容箱物品 -> 物品 pages 快照进方块 PDC + VirtualStore 登记 + 挂悬浮文字
 *   右键扩容箱     -> 打开分页虚拟大仓库 GUI（物理 27 格留给红石/漏斗）
 *   GUI 点击       -> 只拦导航行翻页，内容区放行原版拖拽
 *   GUI 关闭       -> 回写虚拟存储 + 刷新悬浮文字
 *   破坏扩容箱     -> 关原版掉落，改为掉带 pages 的空箱 + 限速掉落虚拟内容 + 清悬浮文字
 *
 * 普通原版箱子完全不受影响（只认带 pages 标记的箱子）。
 */
public final class ChestListeners implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ChestMarker marker;
    private final ChestItems items;
    private final VirtualStore store;
    private final ChestGui gui;
    private final HologramManager holograms;

    public ChestListeners(Plugin plugin, PluginConfig config, ChestMarker marker,
                          ChestItems items, VirtualStore store, ChestGui gui,
                          HologramManager holograms) {
        this.plugin = plugin;
        this.config = config;
        this.marker = marker;
        this.items = items;
        this.store = store;
        this.gui = gui;
        this.holograms = holograms;
    }

    // ---- 放置：物品身份 -> 方块权威 ----

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!items.isChestItem(inHand)) return;      // 只接管本插件的扩容箱物品

        int pages = clampPages(items.readPages(inHand, config.defaultPages));
        Block block = event.getBlockPlaced();
        BlockState state = block.getState(false);
        if (!(state instanceof Chest chest)) return;

        // 防混合(forbid_mixed): 扩容箱只能与扩容箱合并。若此次放置与相邻的“普通箱”凑成双联,
        // 取消放置(物品留在手上), 提示玩家。扩容箱之间的合并放行(容量叠加)。
        ChestPairing pairing = ChestPairing.resolve(block);
        if (pairing != null && pairing.isDouble()) {
            Block partner = pairing.partnerOf(block);
            if (partner != null && !isMarkedChest(partner)) {
                event.setCancelled(true);
                msg(event.getPlayer(), "&c扩容箱不能与普通箱子合并, 请隔开或与另一个扩容箱相邻。");
                return;
            }
        }

        marker.write(chest, pages);                  // 把 pages 写进方块 PDC
        chest.update(true, false);

        String key = VirtualStore.keyOf(block);
        ChestData data = store.create(key, pages);
        data.setCustomName(items.readName(inHand)); // 从物品(可能被铁砧改过名)继承名字
        holograms.syncBlock(block);                  // 按配对刷新(双联合并显示)
    }

    // ---- 右键：打开虚拟大仓库 GUI ----

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;          // 只处理主手一次
        if (!event.getAction().isRightClick()) return;
        if (event.getPlayer().isSneaking()) return;                 // 潜行右键放行: 让玩家能贴着箱子放漏斗/方块, 不抢开 GUI
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHEST) return;

        BlockState state = block.getState(false);
        if (!(state instanceof Chest chest) || !marker.isMarked(chest)) return;

        event.setCancelled(true);                                    // 拦下原版物理箱界面
        ChestPairing pairing = ChestPairing.resolve(block);
        ChestView view = buildView(pairing);                         // 单箱=1 段, 双联=左右 2 段拼接
        if (view == null) return;                                    // 兜底: 无有效扩容段
        gui.open(event.getPlayer(), view, view.blockKeys().get(0), 0);
    }

    /**
     * 由配对构造 GUI 视图：对每个扩容半登记/补建 ChestData 并按 LEFT-first 顺序拼成 ChestView。
     * 普通半(理论上被 onPlace 防混合挡掉, 这里再兜底)跳过, 不纳入视图。
     * 若无任何扩容段(极端脏态)返回 null，调用方直接放弃开界面。
     */
    private ChestView buildView(ChestPairing pairing) {
        List<ChestData> segments = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Block b : pairing.blocks()) {
            BlockState bs = b.getState(false);
            if (!(bs instanceof Chest c) || !marker.isMarked(c)) continue;  // 跳过非扩容半
            int pages = clampPages(marker.readPages(c, config.defaultPages));
            String key = VirtualStore.keyOf(b);
            segments.add(store.create(key, pages));                  // 缺失则补建(兼容旧箱)
            keys.add(key);
        }
        if (segments.isEmpty()) return null;
        return new ChestView(segments, keys);
    }

    // ---- GUI 点击：只拦导航行 ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ChestGuiHolder holder)) return;

        // 双击收集(含整理 mod 的批量收集)会跨整个界面卷同类物品, 可能把导航行按钮/内容一起卷走。
        // 顶部界面混入了导航行按钮, 语义不干净, 这里直接拦掉这一种交互, 避免按钮被吸走或数据错乱。
        if (event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        boolean inTop = raw < event.getInventory().getSize();
        if (inTop && gui.isNavSlot(raw)) {
            event.setCancelled(true);                                // 导航行不可拿取
            if (event.getWhoClicked() instanceof Player player) {
                gui.handleNavClick(player, holder, raw);
            }
        }
        // 内容区(0..44)与玩家背包放行，交原版处理拖拽/取放
    }

    // ---- GUI 拖拽：保护导航行 ----
    // 一键整理 mod / 原版拖拽分发会把物品撒到一批槽位。若落到导航行(45..53),
    // 物品会压在按钮上而 writeBack 只回写内容区(0..44) -> 物品凭空消失。
    // 这里只要拖拽涉及任一导航行槽就整体取消, 内容区内部的拖拽照常放行。

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ChestGuiHolder)) return;
        int topSize = event.getInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && gui.isNavSlot(raw)) {   // 命中顶部界面的导航行
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- GUI 关闭：回写 + 刷新悬浮文字 ----

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ChestGuiHolder holder) {
            gui.onClose(holder);
        }
    }

    // ---- 破坏：接管掉落 ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isMarkedChest(block)) return;
        event.setDropItems(false);                     // 关原版掉落, 全部手动接管
        dismantle(block, true);                        // 玩家破坏: 物理内容照掉
    }

    // ---- 爆炸: 兜底清理, 修复"箱子没了但悬浮字还在" ----
    // TNT/苦力怕(EntityExplode)与床/重生锚(BlockExplode)炸掉扩容箱时不经过 onBreak,
    // 若不接管, 悬浮字与虚拟存储都会残留。这里把被炸的扩容箱从爆炸掉落表里摘掉, 由我们统一接管。

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::dismantleIfMarked);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::dismantleIfMarked);
    }

    /** 若是扩容箱则接管拆除并返回 true（从爆炸块列表中移除, 交我们掉落）。 */
    private boolean dismantleIfMarked(Block block) {
        if (!isMarkedChest(block)) return false;
        dismantle(block, true);
        return true;
    }

    private boolean isMarkedChest(Block block) {
        if (block.getType() != Material.CHEST) return false;
        BlockState state = block.getState(false);
        return state instanceof Chest chest && marker.isMarked(chest);
    }

    /**
     * 拆除一个扩容箱：清悬浮字 + 移除虚拟存储登记 + 掉落（空箱物品、可选物理内容、限速掉虚拟内容）。
     * 破坏与爆炸共用同一出口，保证"箱子消失"时悬浮字与存储登记一定被清理，杜绝残留。
     */
    private void dismantle(Block block, boolean dropPhysical) {
        Chest chest = (Chest) block.getState(false);
        int pages = clampPages(marker.readPages(chest, config.defaultPages));
        String key = VirtualStore.keyOf(block);
        Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);

        // 拆前记住双联另一半: 拆掉本半后, 它会从"合并显示"回落为单箱, 需延迟一 tick 重刷。
        ChestPairing pairing = ChestPairing.resolve(block);
        Block partner = pairing != null ? pairing.partnerOf(block) : null;

        holograms.clearForBlock(block);                // 先清本配对悬浮字(含合并实体)

        if (dropPhysical) {                            // 只掉本半物理 27 格(getBlockInventory), 不误掉另一半
            for (ItemStack s : chest.getBlockInventory().getContents()) {
                if (s != null && !s.getType().isAir()) dropAt.getWorld().dropItemNaturally(dropAt, s);
            }
        }
        if (config.dropCapacityItem) {                 // 掉一个保留 pages 的空箱物品, 便于回收
            dropAt.getWorld().dropItemNaturally(dropAt, items.create(pages, 1));
        }

        // 虚拟内容处理: 区分单箱 / 双联拆一半
        //   单箱      -> 整份内容掉落(无剩余段可容纳)。
        //   双联拆半  -> trim_tail 重分布(见 ChestView.redistributeExcluding):
        //               合并连续内容按段序回填剩余半, 只掉落塞不下的尾端溢出。
        List<ItemStack> toDrop;
        if (partner != null && isMarkedChest(partner)) {
            ChestView view = buildView(pairing);       // 覆盖左右两段的连续视图
            toDrop = (view != null)
                    ? view.redistributeExcluding(key)  // 尾端溢出
                    : dumpRemoved(key);                // 兜底: 视图异常则整份掉落
        } else {
            toDrop = dumpRemoved(key);                 // 单箱: 整份掉落
        }
        store.remove(key);                             // 无论如何, 本半登记必须摘除
        store.saveAsync();                             // 重分布改了剩余半内容, 立即落盘防崩服回滚
        scheduleDrop(dropAt, toDrop);                  // 限速分批掉落

        if (partner != null) refreshPartnerNextTick(partner);  // 剩余半回到单箱悬浮字
    }

    /** 从 store 摘出某段的全部内容为掉落列表（内容已随 remove 脱离存储）。 */
    private List<ItemStack> dumpRemoved(String key) {
        ChestData data = store.get(key);
        return data != null ? data.contentsList() : List.of();
    }

    /**
     * 延迟一 tick 刷新另一半的悬浮字。破坏/爆炸事件触发时本半方块尚未真正移除,
     * 立即刷 partner 仍会解析成双联; 下一 tick 方块已消失, 它自然解析为单箱, 显示自身容量。
     */
    private void refreshPartnerNextTick(Block partner) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (isMarkedChest(partner)) holograms.syncBlock(partner);
        });
    }

    /**
     * 破坏后把虚拟存储的大量内容分批限速掉落，避免单 tick 生成上千掉落物卡服。
     * 每 tick 掉 config.breakDropPerTick 个堆叠，掉完自动结束。null/空气自动跳过。
     */
    private void scheduleDrop(Location at, List<ItemStack> items) {
        Deque<ItemStack> queue = new ArrayDeque<>();
        for (ItemStack s : items) {
            if (s != null && !s.getType().isAir()) queue.add(s);
        }
        if (queue.isEmpty()) return;
        int perTick = config.breakDropPerTick;
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
                at.getWorld().dropItemNaturally(at, queue.poll());
            }
            if (queue.isEmpty()) task.cancel();
        }, 1L, 1L);
    }

    private int clampPages(int pages) {
        return Math.max(1, Math.min(config.maxPages, pages));
    }

    private void msg(Player player, String legacy) {
        player.sendMessage(PluginConfig.text(legacy));
    }
}