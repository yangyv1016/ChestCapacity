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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;

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

        marker.write(chest, pages);                  // 把 pages 写进方块 PDC
        chest.update(true, false);

        String key = VirtualStore.keyOf(block);
        ChestData data = store.create(key, pages);
        holograms.refresh(block, data.usedStacks(), data.capacity(), data.pages());
    }

    // ---- 右键：打开虚拟大仓库 GUI ----

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;          // 只处理主手一次
        if (!event.getAction().isRightClick()) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHEST) return;

        BlockState state = block.getState(false);
        if (!(state instanceof Chest chest) || !marker.isMarked(chest)) return;

        event.setCancelled(true);                                    // 拦下原版物理箱界面
        int pages = clampPages(marker.readPages(chest, config.defaultPages));
        String key = VirtualStore.keyOf(block);
        ChestData data = store.create(key, pages);                   // 缺失则补建(兼容旧箱)
        gui.open(event.getPlayer(), data, key, 0);
    }

    // ---- GUI 点击：只拦导航行 ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ChestGuiHolder holder)) return;
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
        if (block.getType() != Material.CHEST) return;
        BlockState state = block.getState(false);
        if (!(state instanceof Chest chest) || !marker.isMarked(chest)) return;

        int pages = clampPages(marker.readPages(chest, config.defaultPages));
        String key = VirtualStore.keyOf(block);
        Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);

        event.setDropItems(false);                     // 关原版掉落, 全部手动接管

        // 物理 27 格里的内容(红石接口里的实物)照常掉
        for (ItemStack s : chest.getInventory().getContents()) {
            if (s != null && !s.getType().isAir()) dropAt.getWorld().dropItemNaturally(dropAt, s);
        }

        // 掉一个保留 pages 的空箱物品, 便于回收扩容箱
        if (config.dropCapacityItem) {
            dropAt.getWorld().dropItemNaturally(dropAt, items.create(pages, 1));
        }

        holograms.remove(block);
        ChestData data = store.remove(key);            // 摘出虚拟存储内容
        if (data != null) scheduleDrop(dropAt, data);  // 限速分批掉落
    }

    /**
     * 破坏后把虚拟存储的大量内容分批限速掉落，避免单 tick 生成上千掉落物卡服。
     * 每 tick 掉 config.breakDropPerTick 个堆叠，掉完自动结束。
     */
    private void scheduleDrop(Location at, ChestData data) {
        Deque<ItemStack> queue = new ArrayDeque<>();
        for (ItemStack s : data.slots()) {
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
}