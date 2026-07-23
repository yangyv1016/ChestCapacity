package com.redstone.chestcapacity;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;

import java.util.ArrayList;
import java.util.List;

/**
 * 将任一扩容箱方块解析为统一逻辑仓库。
 * 单箱返回一段，双联箱按 LEFT-first 返回两段；普通箱和失效方块返回 null。
 */
public final class ChestViewResolver {

    private final PluginConfig config;
    private final ChestMarker marker;
    private final VirtualStore store;

    public ChestViewResolver(PluginConfig config, ChestMarker marker, VirtualStore store) {
        this.config = config;
        this.marker = marker;
        this.store = store;
    }

    public ChestView resolve(Block block) {
        ChestPairing pairing = ChestPairing.resolve(block);
        if (pairing == null) return null;

        List<ChestData> segments = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Block part : pairing.blocks()) {
            BlockState state = part.getState(false);
            if (!(state instanceof Chest chest) || !marker.isMarked(chest)) continue;
            int pages = Math.max(1, Math.min(config.maxPages,
                    marker.readPages(chest, config.defaultPages)));
            String key = VirtualStore.keyOf(part);
            segments.add(store.create(key, pages));
            keys.add(key);
        }
        return segments.isEmpty() ? null : new ChestView(segments, keys);
    }

    public boolean isExpandedChest(Block block) {
        BlockState state = block.getState(false);
        return state instanceof Chest chest && marker.isMarked(chest);
    }
}