package com.redstone.chestcapacity;

import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

/**
 * pages 标记在“物品 ItemMeta”和“方块 TileState”上的统一读写口。
 *
 * 物品与方块都实现 PersistentDataHolder，所以同一套逻辑复用：
 *   · 物品：身份来源，玩家拿在手上，放下时其 pages 被快照到方块。
 *   · 方块：运行期权威，搬运/GUI/破坏都以方块上的 pages 为准。
 *
 * 这样避免 "pages" 读写字符串在命令、监听、GUI 里各写一遍。
 */
public final class ChestMarker {

    private final Keys keys;

    public ChestMarker(Keys keys) {
        this.keys = keys;
    }

    /** 是否是被本插件标记过的扩容箱（含 pages 键即视为扩容箱）。 */
    public boolean isMarked(PersistentDataHolder holder) {
        return holder != null &&
                holder.getPersistentDataContainer().has(keys.pages, PersistentDataType.INTEGER);
    }

    public int readPages(PersistentDataHolder holder, int fallback) {
        if (holder == null) return fallback;
        Integer v = holder.getPersistentDataContainer()
                .get(keys.pages, PersistentDataType.INTEGER);
        return v == null ? fallback : v;
    }

    public void write(PersistentDataContainer pdc, int pages) {
        pdc.set(keys.pages, PersistentDataType.INTEGER, pages);
    }

    public void write(PersistentDataHolder holder, int pages) {
        write(holder.getPersistentDataContainer(), pages);
    }
}