package com.redstone.chestcapacity;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** 将扩容箱的虚拟占用率投影为相邻比较器的输入信号。 */
public final class ComparatorService implements Listener {

    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Plugin plugin;
    private final ChestViewResolver resolver;

    public ComparatorService(Plugin plugin, ChestViewResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Block comparatorBlock = event.getBlock();
        if (comparatorBlock.getType() != Material.COMPARATOR
                || !(comparatorBlock.getBlockData() instanceof Comparator data)) return;

        Block source = inputContainerOf(comparatorBlock, data);
        ChestView view = source == null ? null : resolver.resolve(source);
        if (view == null) return;

        int input = view.comparatorSignal();
        int side = Math.max(
                comparatorBlock.getRelative(leftOf(data.getFacing())).getBlockPower(),
                comparatorBlock.getRelative(rightOf(data.getFacing())).getBlockPower());
        int output = data.getMode() == Comparator.Mode.SUBTRACT
                ? Math.max(0, input - side)
                : (input >= side ? input : 0);
        event.setNewCurrent(output);
    }

    /** 在虚拟内容变化后立即刷新，并在下一 tick 再确认一次红石状态。 */
    public void refresh(ChestView view) {
        List<String> keys = List.copyOf(view.blockKeys());
        refreshNow(keys);
        plugin.getServer().getScheduler().runTask(plugin, () -> refreshNow(keys));
    }

    private void refreshNow(List<String> keys) {
        for (String key : keys) {
            Block chest = VirtualStore.blockOf(key);
            if (chest == null || !chest.getWorld().isChunkLoaded(
                    chest.getX() >> 4, chest.getZ() >> 4)) continue;
            chest.getState(false).update(true, true);
            for (BlockFace face : HORIZONTAL) {
                refreshComparatorIfReading(chest.getRelative(face), chest);

                Block bridge = chest.getRelative(face);
                if (!bridge.getType().isOccluding()) continue;
                refreshComparatorIfReading(bridge.getRelative(face), chest);
            }
        }
    }

    /**
     * 原版比较器既可直接读取容器，也可隔一个实心方块读取容器。
     * 这里只扩展输入源解析，不接管普通容器的比较器行为。
     */
    private Block inputContainerOf(Block comparatorBlock, Comparator data) {
        BlockFace backwards = data.getFacing().getOppositeFace();
        Block direct = comparatorBlock.getRelative(backwards);
        if (resolver.resolve(direct) != null) return direct;
        if (!direct.getType().isOccluding()) return null;

        Block behindSolid = direct.getRelative(backwards);
        return resolver.resolve(behindSolid) != null ? behindSolid : null;
    }

    private void refreshComparatorIfReading(Block candidate, Block chest) {
        if (candidate.getType() != Material.COMPARATOR
                || !(candidate.getBlockData() instanceof Comparator data)) return;
        Block source = inputContainerOf(candidate, data);
        if (source == null || !sameBlock(source, chest)) return;
        candidate.setBlockData(candidate.getBlockData(), true);
    }

    private static BlockFace leftOf(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> BlockFace.SELF;
        };
    }

    private static BlockFace rightOf(BlockFace facing) {
        return leftOf(facing).getOppositeFace();
    }

    private static boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }
}