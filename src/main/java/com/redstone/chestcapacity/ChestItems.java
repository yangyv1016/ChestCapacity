package com.redstone.chestcapacity;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 扩容箱“物品形态”的工厂与识别器。
 *
 * 职责边界：
 *   · 只管把 pages 组装成一个带 name+lore+PDC 的箱子 ItemStack；
 *   · lore/name 文案一律向 PluginConfig 要，本类不拼字符串；
 *   · PDC 标记的读写委托给 ChestMarker。
 *
 * “放下即生效”链路的起点：玩家拿到这个物品，放置监听读它的 pages 快照进方块。
 */
public final class ChestItems {

    private final PluginConfig config;
    private final ChestMarker marker;

    public ChestItems(PluginConfig config, ChestMarker marker) {
        this.config = config;
        this.marker = marker;
    }

    /** 造一个 pages 页的扩容箱物品。 */
    public ItemStack create(int pages, int amount) {
        ItemStack item = new ItemStack(Material.CHEST, Math.max(1, amount));
        applyMeta(item, pages);
        return item;
    }

    /**
     * 把 name/lore/PDC 写到（或刷新到）给定物品上。
     * 指令调整容量后调用它，保证悬停显示与 NBT 同步。
     */
    public void applyMeta(ItemStack item, int pages) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.displayName(config.itemDisplayName(pages));
        meta.lore(config.itemLore(pages));
        marker.write(meta, pages);
        item.setItemMeta(meta);
    }

    /** 是否为扩容箱物品（含 pages 标记）。 */
    public boolean isChestItem(ItemStack item) {
        if (item == null || item.getType() != Material.CHEST) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && marker.isMarked(meta);
    }

    public int readPages(ItemStack item, int fallback) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return marker.readPages(meta, fallback);
    }
}