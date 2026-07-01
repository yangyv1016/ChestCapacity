package com.redstone.chestcapacity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置的强类型快照。启动/reload 时从 FileConfiguration 读入一次，
 * 之后各模块只读本对象，避免到处 getConfig() 与魔法字符串扩散。
 */
public final class PluginConfig {

    public static final int SLOTS_PER_PAGE = 45;   // 每页可用格(底行9格留给导航)
    public static final int PHYSICAL_SLOTS = 27;   // 物理箱子槽位数

    // 容量
    public final int defaultPages;
    public final int maxPages;

    // 红石水位缓冲
    public final boolean bufferEnabled;
    public final int keepFilledSlots;
    public final long transferIntervalTicks;
    public final int transferBatchPerChest;

    // 落盘
    public final long saveIntervalTicks;

    // 破坏
    public final boolean dropCapacityItem;
    public final int breakDropPerTick;

    // 悬浮文字
    public final boolean hologramEnabled;
    public final double hologramYOffset;
    private final String hologramText;

    // GUI
    public final String guiTitle;
    public final String guiPrevPage;
    public final String guiNextPage;
    public final String guiPageIndicator;
    public final boolean guiFiller;

    // 物品显示
    private final String itemName;
    private final List<String> itemLore;

    private static final LegacyComponentSerializer LEGACY_AMP =
            LegacyComponentSerializer.legacyAmpersand();

    public PluginConfig(FileConfiguration c) {
        this.defaultPages = Math.max(1, c.getInt("default-pages", 1));
        this.maxPages = Math.max(this.defaultPages, c.getInt("max-pages", 54));

        this.bufferEnabled = c.getBoolean("buffer-enabled", true);
        this.keepFilledSlots = clamp(c.getInt("keep-filled-slots", 0), 0, PHYSICAL_SLOTS);
        this.transferIntervalTicks = Math.max(1L, c.getLong("transfer-interval-ticks", 2));
        this.transferBatchPerChest = Math.max(1, c.getInt("transfer-batch-per-chest", 5));

        this.saveIntervalTicks = Math.max(200L, c.getLong("save-interval-ticks", 1200));

        this.dropCapacityItem = c.getBoolean("drop-capacity-item", true);
        this.breakDropPerTick = Math.max(1, c.getInt("break-drop-per-tick", 20));

        this.hologramEnabled = c.getBoolean("hologram.enabled", true);
        this.hologramYOffset = c.getDouble("hologram.y-offset", 1.1);
        this.hologramText = c.getString("hologram.text", "&6扩容箱子\n&7%used%&8/&7%slots%");

        this.guiTitle = c.getString("gui.title", "&8扩容箱子 &7[%page%/%pages%]");
        this.guiPrevPage = c.getString("gui.prev-page", "&a« 上一页");
        this.guiNextPage = c.getString("gui.next-page", "&a下一页 »");
        this.guiPageIndicator = c.getString("gui.page-indicator", "&e第 %page% / %pages% 页");
        this.guiFiller = c.getBoolean("gui.filler", true);

        this.itemName = c.getString("item.name", "&6扩容箱子");
        this.itemLore = c.getStringList("item.lore");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 物品显示名(已含颜色/去斜体)。%pages%/%slots% 占位符已替换。 */
    public Component itemDisplayName(int pages) {
        return text(fillItem(itemName, pages));
    }

    /** 组装物品 lore。把“lore 长什么样”收拢在配置对象里，物品构建处只管调用。 */
    public List<Component> itemLore(int pages) {
        List<Component> out = new ArrayList<>(itemLore.size());
        for (String line : itemLore) out.add(text(fillItem(line, pages)));
        return out;
    }

    /**
     * 悬浮文字的 Component（多行用 \n 拆分成换行）。
     * used=已用格 slots=总格 pages=页数。
     */
    public Component hologramComponent(int used, int slots, int pages) {
        int pct = slots <= 0 ? 0 : (int) Math.round(used * 100.0 / slots);
        String filled = hologramText
                .replace("%used%", Integer.toString(used))
                .replace("%slots%", Integer.toString(slots))
                .replace("%pages%", Integer.toString(pages))
                .replace("%pct%", Integer.toString(pct));
        Component out = null;
        for (String line : filled.split("\n", -1)) {
            Component c = text(line);
            out = (out == null) ? c : out.append(Component.newline()).append(c);
        }
        return out == null ? Component.empty() : out;
    }

    private String fillItem(String s, int pages) {
        return s.replace("%pages%", Integer.toString(pages))
                .replace("%slots%", Integer.toString(pages * SLOTS_PER_PAGE));
    }

    /** 将 &-颜色码字符串转为 Component，并去掉斜体默认样式(物品名/lore用)。 */
    public static Component text(String legacy) {
        return LEGACY_AMP.deserialize(legacy).decoration(
                net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
}