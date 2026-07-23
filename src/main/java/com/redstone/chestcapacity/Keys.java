package com.redstone.chestcapacity;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * 集中管理所有 PersistentDataContainer 键。
 * 方块 TileState 与物品 ItemMeta 复用同一个键：pages（扩容页数）。
 *
 * 设计约束：方块只承载“它被扩容成了多大”这一件事，不背模式等额外功能。
 * 红石 I/O 策略是全局配置，物品内容统一存放在 VirtualStore。
 */
public final class Keys {

    public final NamespacedKey pages;     // Integer, 扩容页数(物品/方块)
    public final NamespacedKey holo;      // String,  容量 TextDisplay 归属的箱子坐标键
    public final NamespacedKey holoName;  // String,  名字 TextDisplay 归属的箱子坐标键

    public Keys(Plugin plugin) {
        this.pages    = new NamespacedKey(plugin, "pages");
        this.holo     = new NamespacedKey(plugin, "holo");
        this.holoName = new NamespacedKey(plugin, "holo_name");
    }
}