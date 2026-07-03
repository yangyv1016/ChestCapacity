package com.redstone.chestcapacity;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * 箱子方块的“物理配对”解析结果（不可变值对象）。
 *
 * 只回答一个问题：这个箱子方块在原版层面是单箱, 还是与相邻箱组成了双联?
 * 权威来自 Bukkit：双联时 chest.getInventory().getHolder() 是 DoubleChest,
 * 它的 getLeftSide()/getRightSide() 给出稳定的左右两半（朝向决定, 不随点击变化）。
 *
 * 职责边界（重要）：本类只管“物理上几块、分别是哪块、谁左谁右”，
 *   不判断某半是否扩容箱（那是 ChestMarker 的事）、也不碰虚拟存储。
 *   这样配对解析可被 GUI 入口、悬浮字、破坏/爆炸清理复用而不重复踩坑。
 *
 * 段顺序约定：blocks 恒为 LEFT 在前、RIGHT 在后（单箱则仅一个元素）。
 * 上层据此拼接 ChestView，保证翻页看到的先后顺序稳定可预测。
 */
public final class ChestPairing {

    private final List<Block> blocks;   // 单箱=[self]; 双联=[left, right]
    private final boolean doubled;

    private ChestPairing(List<Block> blocks, boolean doubled) {
        this.blocks = blocks;
        this.doubled = doubled;
    }

    /**
     * 解析给定箱子方块的配对。非箱子返回 null。
     * 双联时无论传入左半还是右半，都归一化为 [left, right]。
     */
    public static ChestPairing resolve(Block chestBlock) {
        BlockState state = chestBlock.getState(false);
        if (!(state instanceof Chest chest)) return null;
        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest dc) {
            Block left = ((Chest) dc.getLeftSide()).getBlock();
            Block right = ((Chest) dc.getRightSide()).getBlock();
            return new ChestPairing(List.of(left, right), true);
        }
        return new ChestPairing(List.of(chestBlock), false);
    }

    public boolean isDouble() { return doubled; }

    /** 有序方块列表（LEFT 在前）。单箱仅一个元素。 */
    public List<Block> blocks() { return blocks; }

    /** 规范首块：双联=左半, 单箱=自身。用作标题/主锚点。 */
    public Block primary() { return blocks.get(0); }

    /** 双联时返回另一半，单箱返回 null。 */
    public Block partnerOf(Block block) {
        if (!doubled) return null;
        Block a = blocks.get(0), b = blocks.get(1);
        if (sameBlock(a, block)) return b;
        if (sameBlock(b, block)) return a;
        return null;
    }

    /**
     * 悬浮字锚点：双联取两半中点（合并显示一个），单箱取自身中心。
     * 竖直方向叠加 yOffset。
     */
    public Location hologramAnchor(double yOffset) {
        if (!doubled) {
            return blocks.get(0).getLocation().add(0.5, yOffset, 0.5);
        }
        Location l = blocks.get(0).getLocation();
        Location r = blocks.get(1).getLocation();
        double x = (l.getX() + r.getX()) / 2.0 + 0.5;
        double z = (l.getZ() + r.getZ()) / 2.0 + 0.5;
        return new Location(l.getWorld(), x, l.getY() + yOffset, z);
    }

    private static boolean sameBlock(Block a, Block b) {
        return a.getX() == b.getX() && a.getY() == b.getY()
                && a.getZ() == b.getZ() && a.getWorld().equals(b.getWorld());
    }
}