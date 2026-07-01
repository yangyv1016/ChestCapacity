package com.redstone.chestcapacity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 放好的箱子方块没有原版 tooltip（原版限制），用一个 TextDisplay 实体挂在方块上方，
 * 显示“已用/总容量”。本类是这类悬浮文字的唯一出入口。
 *
 * 归属识别：每个 TextDisplay 的 PDC 里写入它所属箱子的坐标键(keys.holo)。
 *   · 放置箱子 -> spawn
 *   · 内容变动/容量变动 -> refresh（找不到就补建）
 *   · 破坏箱子 -> remove
 *   · 服务器重启：实体随世界持久化存在，靠坐标键在破坏/刷新时重新关联。
 *
 * 全部操作在主线程执行（实体 API 要求）。
 */
public final class HologramManager {

    private final PluginConfig config;
    private final Keys keys;

    public HologramManager(PluginConfig config, Keys keys) {
        this.config = config;
        this.keys = keys;
    }

    /** 悬浮文字应处的世界坐标：方块中心 + 竖直偏移。 */
    private Location anchorOf(Block block) {
        return block.getLocation().add(0.5, config.hologramYOffset, 0.5);
    }

    /** 放置或刷新：确保该箱子有一个内容正确的悬浮文字。 */
    public void refresh(Block block, int used, int slots, int pages) {
        if (!config.hologramEnabled) return;
        TextDisplay display = find(block);
        if (display == null) display = spawn(block);
        if (display == null) return;
        display.text(config.hologramComponent(used, slots, pages));
    }

    /** 破坏箱子：移除其悬浮文字。 */
    public void remove(Block block) {
        TextDisplay display = find(block);
        if (display != null) display.remove();
    }

    private TextDisplay spawn(Block block) {
        World world = block.getWorld();
        String key = VirtualStore.keyOf(block);
        return world.spawn(anchorOf(block), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);   // 始终面向玩家
            td.setPersistent(true);
            td.setDefaultBackground(false);
            td.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
            td.getPersistentDataContainer().set(
                    keys.holo, PersistentDataType.STRING, key);
        });
    }

    /**
     * 找到属于该箱子的悬浮文字。用坐标键匹配，遍历锚点附近的 TextDisplay。
     * 区块未加载时返回 null（调用方自行决定是否跳过）。
     */
    private TextDisplay find(Block block) {
        String key = VirtualStore.keyOf(block);
        Location anchor = anchorOf(block);
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return null;
        for (TextDisplay td : block.getWorld().getNearbyEntitiesByType(
                TextDisplay.class, anchor, 1.0)) {
            String owned = td.getPersistentDataContainer()
                    .get(keys.holo, PersistentDataType.STRING);
            if (key.equals(owned)) return td;
        }
        return null;
    }
}