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

    // 红石水位缓冲：滞回双阈值 [low, high]
    //   occupied < low  -> 补货到 low(下游漏斗抽空了, 从虚拟存储补回物理格)
    //   occupied > high -> 下沉到 high(上游漏斗塞满了, 多余下沉进虚拟存储)
    //   low..high       -> 不动(滞回带, 避免每 tick 来回震荡)
    // 兼容旧配置: 只写了 keep-filled-slots(单水位)时 low=high=该值, 退化为旧行为。
    public final boolean bufferEnabled;
    public final int keepFilledLow;
    public final int keepFilledHigh;
    public final long transferIntervalTicks;
    public final int transferBatchPerChest;
    // 补货粒度：一次从虚拟存储抽多少个补进物理格。默认 1 = GUI 逐个减少(贴合原版漏斗视觉);
    // 调大提升高速/多漏斗并联的吞吐, 但 GUI 扣减会更"跳"(一次少一批)。
    public final int refillBatch;

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
    public final String guiVoidOn;    // 溢出销毁按钮: 已开启态文案
    public final String guiVoidOff;   // 溢出销毁按钮: 已关闭态文案
    public final String guiHoloOn;    // 悬浮字按钮: 已开启态文案
    public final String guiHoloOff;   // 悬浮字按钮: 已关闭态文案

    // 物品显示
    private final String itemName;
    private final List<String> itemLore;

    private static final LegacyComponentSerializer LEGACY_AMP =
            LegacyComponentSerializer.legacyAmpersand();

    public PluginConfig(FileConfiguration c) {
        this.defaultPages = Math.max(1, c.getInt("default-pages", 1));
        this.maxPages = Math.max(this.defaultPages, c.getInt("max-pages", 54));

        this.bufferEnabled = c.getBoolean("buffer-enabled", true);
        // 单水位旧字段作为回退基准; 新双阈值缺省时退化为 low=high=旧值。
        int legacy = clamp(c.getInt("keep-filled-slots", 0), 0, PHYSICAL_SLOTS);
        int low = clamp(c.getInt("keep-filled-low", legacy), 0, PHYSICAL_SLOTS);
        int high = clamp(c.getInt("keep-filled-high", legacy), 0, PHYSICAL_SLOTS);
        this.keepFilledLow = Math.min(low, high);   // 纠正 low>high 的误配, 保证区间合法
        this.keepFilledHigh = Math.max(low, high);
        this.transferIntervalTicks = Math.max(1L, c.getLong("transfer-interval-ticks", 2));
        this.transferBatchPerChest = Math.max(1, c.getInt("transfer-batch-per-chest", 5));
        this.refillBatch = clamp(c.getInt("refill-batch", 1), 1, 64);

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
        this.guiVoidOn = c.getString("gui.void-overflow-on",
                "&c溢出销毁: 开\n&7虚拟仓库满后, 搬不下的溢出物品\n&7将被直接删除(防红石堵塞)。\n&e点击关闭");
        this.guiVoidOff = c.getString("gui.void-overflow-off",
                "&a溢出销毁: 关\n&7虚拟仓库满后, 物理格保留物品\n&7(可能回堵漏斗)。\n&e点击开启");
        this.guiHoloOn = c.getString("gui.hologram-on",
                "&a悬浮字: 开\n&7箱子上方显示容量占用文字。\n&e点击关闭");
        this.guiHoloOff = c.getString("gui.hologram-off",
                "&7悬浮字: 关\n&7箱子上方不显示文字。\n&e点击开启");

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

    /** 多行文案(\n 分隔)的首行作为显示名。供 GUI 按钮用。 */
    public static Component firstLine(String multiline) {
        return text(multiline.split("\n", -1)[0]);
    }

    /** 取多行文案(\n 分隔)除首行外的其余行作为 lore（可能为空列表）。 */
    public static List<Component> loreLines(String multiline) {
        String[] lines = multiline.split("\n", -1);
        List<Component> out = new ArrayList<>(Math.max(0, lines.length - 1));
        for (int i = 1; i < lines.length; i++) out.add(text(lines[i]));
        return out;
    }

    /** 将 &-颜色码字符串转为 Component，并去掉斜体默认样式(物品名/lore用)。 */
    public static Component text(String legacy) {
        return LEGACY_AMP.deserialize(legacy).decoration(
                net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
}