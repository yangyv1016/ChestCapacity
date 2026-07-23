package com.redstone.chestcapacity;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Hopper;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 虚拟仓库红石 I/O：物理箱始终清空，原版漏斗、漏斗矿车、投掷器与自动合成器
 * 通过同一个 ChestView 与仓库交换物品；比较器由 ComparatorService 读取同一视图。
 */
public final class StorageIoService implements Listener {

    private static final BlockFace[] INPUT_NEIGHBORS = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Plugin plugin;
    private final PluginConfig config;
    private final VirtualStore store;
    private final ChestViewResolver resolver;
    private final ChestGui gui;
    private final HologramManager holograms;
    private final ComparatorService comparators;

    private BukkitTask task;
    private long ticks;
    private final Set<String> initializedComparatorViews = new HashSet<>();

    public StorageIoService(Plugin plugin, PluginConfig config, VirtualStore store,
                            ChestViewResolver resolver, ChestGui gui,
                            HologramManager holograms, ComparatorService comparators) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.resolver = resolver;
        this.gui = gui;
        this.holograms = holograms;
        this.comparators = comparators;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        if (!config.ioEnabled) {
            plugin.getLogger().info("虚拟仓库红石端点已关闭；物理箱仍会保持为空。");
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 物理箱不参与库存事务：从扩容箱抽取一律取消；写入端由主动漏斗适配接管，
     * 投掷器/自动合成器等原版输出方块则放行到物理箱，并在同 tick 周期迁入虚拟仓库。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (expandedBlockOf(event.getSource()) != null) {
            event.setCancelled(true);
            return;
        }

        Block destination = expandedBlockOf(event.getDestination());
        if (destination == null) return;
        InventoryHolder initiator = event.getInitiator().getHolder();
        if (!config.ioEnabled || initiator instanceof Hopper || initiator instanceof HopperMinecart) {
            event.setCancelled(true);
            return;
        }

        if (!canAcceptOutput(destination, event.getItem())) event.setCancelled(true);
    }

    /** 投掷器朝向扩容箱时，满仓应像原版满箱一样保留物品，而不是落到物理缓冲。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropperDispense(BlockDispenseEvent event) {
        Block source = event.getBlock();
        if (source.getType() != Material.DROPPER
                || !(source.getBlockData() instanceof org.bukkit.block.data.Directional data)) return;
        Block destination = source.getRelative(data.getFacing());
        if (resolver.resolve(destination) == null) return;
        if (!config.ioEnabled || !canAcceptOutput(destination, event.getItem())) event.setCancelled(true);
    }

    /** 自动合成器向扩容箱输出时，在合成发生前执行容量背压。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        Block source = event.getBlock();
        if (!(source.getBlockData() instanceof org.bukkit.block.data.Directional data)) return;
        Block destination = source.getRelative(data.getFacing());
        if (resolver.resolve(destination) == null) return;
        if (!config.ioEnabled || !canAcceptOutput(destination, event.getResult())) event.setCancelled(true);
    }

    private void tick() {
        ticks++;
        boolean transferTick = ticks % config.ioIntervalTicks == 0;
        Set<String> processedViews = new HashSet<>();
        Set<String> processedInputHoppers = new HashSet<>();
        Set<String> processedOutputHoppers = new HashSet<>();
        Set<UUID> processedMinecarts = new HashSet<>();

        for (Map.Entry<String, ChestData> entry : new ArrayList<>(store.entries())) {
            Block block = VirtualStore.blockOf(entry.getKey());
            if (!isLoadedExpandedChest(block)) continue;
            ChestView view = resolver.resolve(block);
            if (view == null || !processedViews.add(view.blockKeys().get(0))) continue;
            boolean initializeComparator = initializedComparatorViews.add(view.blockKeys().get(0));

            int beforeSignal = view.comparatorSignal();
            int beforeUsed = view.usedStacks();
            boolean viewed = hasOpenView(view);
            if (viewed) gui.absorbEdits(view.blockKeys().get(0));

            boolean changed = drainPhysical(view);
            if (transferTick && config.ioEnabled) {
                if (config.ioHoppers) {
                    changed |= pullFromInputHoppers(view, processedInputHoppers);
                    changed |= pushToOutputHoppers(view, processedOutputHoppers);
                }
                if (config.ioHopperMinecarts) {
                    changed |= pushToHopperMinecarts(view, processedMinecarts);
                }
            }

            if (viewed) refreshViews(view);
            if (changed || beforeUsed != view.usedStacks()) holograms.syncFor(view);
            if (initializeComparator || changed || beforeSignal != view.comparatorSignal()) {
                comparators.refresh(view);
            }
        }
    }

    /** 迁移旧版本缓冲或其他来源塞入的物品；物理箱在本 tick 结束时恢复为空。 */
    private boolean drainPhysical(ChestView view) {
        boolean changed = false;
        for (String key : view.blockKeys()) {
            Block block = VirtualStore.blockOf(key);
            if (block == null || !(block.getState(false) instanceof Chest chest)) continue;
            Inventory physical = chest.getBlockInventory();
            for (int slot = 0; slot < physical.getSize(); slot++) {
                ItemStack stack = physical.getItem(slot);
                if (stack == null || stack.getType().isAir()) continue;

                ItemStack rest = view.push(stack);
                physical.setItem(slot, null);
                changed = true;
                if (rest == null || view.voidOverflow()) continue;

                block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.8, 0.5), rest);
                plugin.getLogger().warning("扩容箱虚拟仓库已满，物理残留已掉落: " + key);
            }
        }
        return changed;
    }

    private boolean pullFromInputHoppers(ChestView view, Set<String> processed) {
        boolean changed = false;
        for (String key : view.blockKeys()) {
            Block chest = VirtualStore.blockOf(key);
            if (chest == null) continue;
            for (BlockFace face : INPUT_NEIGHBORS) {
                Block candidate = chest.getRelative(face);
                if (!(candidate.getState(false) instanceof Hopper hopper)) continue;
                if (!(candidate.getBlockData() instanceof org.bukkit.block.data.type.Hopper data)
                        || !data.isEnabled()) continue;
                if (!sameBlock(candidate.getRelative(data.getFacing()), chest)) continue;
                if (!processed.add(VirtualStore.keyOf(candidate))) continue;
                changed |= pullOne(hopper.getInventory(), view);
            }
        }
        return changed;
    }

    private boolean pushToOutputHoppers(ChestView view, Set<String> processed) {
        boolean changed = false;
        for (String key : view.blockKeys()) {
            Block chest = VirtualStore.blockOf(key);
            if (chest == null) continue;
            Block below = chest.getRelative(BlockFace.DOWN);
            if (!(below.getState(false) instanceof Hopper hopper)) continue;
            if (!(below.getBlockData() instanceof org.bukkit.block.data.type.Hopper data)
                    || !data.isEnabled()) continue;
            if (!processed.add(VirtualStore.keyOf(below))) continue;
            changed |= pushOne(view, hopper.getInventory());
        }
        return changed;
    }

    private boolean pushToHopperMinecarts(ChestView view, Set<UUID> processed) {
        boolean changed = false;
        for (String key : view.blockKeys()) {
            Block chest = VirtualStore.blockOf(key);
            if (chest == null) continue;
            for (HopperMinecart minecart : chest.getWorld().getNearbyEntitiesByType(
                    HopperMinecart.class, chest.getLocation().add(0.5, -0.5, 0.5), 1.25)) {
                if (!minecart.isEnabled()) continue;
                Block sourceAbove = minecart.getLocation().getBlock().getRelative(BlockFace.UP);
                if (!sameBlock(sourceAbove, chest) || !processed.add(minecart.getUniqueId())) continue;
                changed |= pushOne(view, minecart.getInventory());
            }
        }
        return changed;
    }

    private boolean pullOne(Inventory source, ChestView view) {
        for (int slot = 0; slot < source.getSize(); slot++) {
            ItemStack current = source.getItem(slot);
            if (current == null || current.getType().isAir()) continue;

            int requested = Math.min(config.ioItemsPerTransfer, current.getAmount());
            ItemStack moving = current.clone();
            moving.setAmount(requested);
            ItemStack rest = view.push(moving);
            int accepted = requested - amountOf(rest);
            if (rest != null && view.voidOverflow()) accepted = requested;
            if (accepted <= 0) continue;

            int left = current.getAmount() - accepted;
            if (left <= 0) source.setItem(slot, null);
            else {
                ItemStack retained = current.clone();
                retained.setAmount(left);
                source.setItem(slot, retained);
            }
            return true;
        }
        return false;
    }

    private boolean pushOne(ChestView view, Inventory target) {
        for (int slot = 0; slot < view.capacity(); slot++) {
            ItemStack stored = view.getSlot(slot);
            if (stored == null || stored.getType().isAir()) continue;
            ItemStack candidate = stored.clone();
            candidate.setAmount(Math.min(config.ioItemsPerTransfer, stored.getAmount()));

            Map<Integer, ItemStack> rest = target.addItem(candidate.clone());
            int accepted = candidate.getAmount();
            for (ItemStack stack : rest.values()) accepted -= stack.getAmount();
            if (accepted <= 0) continue;

            ItemStack pulled = view.pullFromSlot(slot, accepted);
            if (pulled == null || pulled.getAmount() != accepted || !pulled.isSimilar(candidate)) {
                throw new IllegalStateException("虚拟仓库输出事务失配");
            }
            return true;
        }
        return false;
    }

    private boolean hasOpenView(ChestView view) {
        for (String key : view.blockKeys()) if (gui.hasOpenView(key)) return true;
        return false;
    }

    private void refreshViews(ChestView view) {
        for (String key : view.blockKeys()) gui.refreshViews(key);
    }

    private boolean canAcceptOutput(Block destination, ItemStack item) {
        ChestView view = resolver.resolve(destination);
        return view != null && (view.voidOverflow() || view.acceptanceOf(item) >= item.getAmount());
    }

    private Block expandedBlockOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest && resolver.isExpandedChest(chest.getBlock())) {
            return chest.getBlock();
        }
        if (holder instanceof DoubleChest doubled) {
            if (doubled.getLeftSide() instanceof Chest left && resolver.isExpandedChest(left.getBlock())) {
                return left.getBlock();
            }
            if (doubled.getRightSide() instanceof Chest right && resolver.isExpandedChest(right.getBlock())) {
                return right.getBlock();
            }
        }
        return null;
    }

    private boolean isLoadedExpandedChest(Block block) {
        return block != null
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && resolver.isExpandedChest(block);
    }

    private static int amountOf(ItemStack stack) {
        return stack == null ? 0 : stack.getAmount();
    }

    private static boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }
}