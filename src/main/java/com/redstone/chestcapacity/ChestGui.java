package com.redstone.chestcapacity;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 分页大容量 GUI。把 ChestData 的虚拟存储切成每页 45 格来展示。
 *
 * 布局（6 行 54 格）：
 *   行 0..4 (槽 0..44)  = 当前页内容，映射到 data.slots[page*45 + i]
 *   行 5   (槽 45..53)  = 导航行：45 上一页, 49 页码, 53 下一页, 其余玻璃板填充
 *
 * 实时镜像模型（v1.0.3 起）：
 *   data.slots 是唯一权威，GUI 界面只是它"当前页"的一面实时镜像。
 *   每个搬运 tick 对打开着的界面做一次有序对账（见 TransferService.tick）：
 *     1. absorbEdits : 界面 --writeBack--> data   （玩家存/取即时入账）
 *     2. 搬运照常跑   : 物理27格 <---> data        （漏斗喂的货下沉进来）
 *     3. refreshViews: data --renderPage--> 界面   （玩家看到堆叠增减 = 流入流出）
 *   因为对账串行且都在主线程，tick 结尾界面与 data 恒一致，不存在"过期快照覆盖"。
 */
public final class ChestGui {

    private static final int PAGE_SLOTS = PluginConfig.SLOTS_PER_PAGE; // 45
    private static final int NAV_ROW_START = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_VOID = 47;        // 溢出销毁开关按钮
    private static final int SLOT_INDICATOR = 49;
    private static final int SLOT_NEXT = 53;

    private final PluginConfig config;
    private final VirtualStore store;
    private final HologramManager holograms;

    // 打开中的界面注册表：箱子坐标键 -> 正在查看它的 holder 集合（支持翻页瞬间与多人同看）。
    // 搬运 tick 靠它定位"某箱子当前有哪些界面要吸收编辑/回显"。
    private final Map<String, Set<ChestGuiHolder>> openViews = new HashMap<>();

    public ChestGui(PluginConfig config, VirtualStore store, HologramManager holograms) {
        this.config = config;
        this.store = store;
        this.holograms = holograms;
    }

    /** 打开指定箱子的某一页。 */
    public void open(Player player, ChestData data, String chestKey, int page) {
        int maxPage = data.pages() - 1;
        page = Math.max(0, Math.min(page, maxPage));

        ChestGuiHolder holder = new ChestGuiHolder(data, chestKey, page);
        Component title = PluginConfig.text(config.guiTitle
                .replace("%page%", Integer.toString(page + 1))
                .replace("%pages%", Integer.toString(data.pages())));
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        renderPage(inv, data, page);
        renderNav(inv, page, data.pages());
        renderVoidButton(inv, data);
        openViews.computeIfAbsent(chestKey, k -> new HashSet<>()).add(holder); // 登记, 让搬运 tick 能对账它
        player.openInventory(inv);
    }

    /** 把 data 的第 page 页内容铺进 GUI 上半区。 */
    private void renderPage(Inventory inv, ChestData data, int page) {
        int base = page * PAGE_SLOTS;
        for (int i = 0; i < PAGE_SLOTS; i++) {
            inv.setItem(i, data.getSlot(base + i));
        }
    }

    /** 渲染底部导航行。 */
    private void renderNav(Inventory inv, int page, int pages) {
        if (config.guiFiller) {
            ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
            for (int i = NAV_ROW_START; i < 54; i++) inv.setItem(i, filler);
        }
        if (page > 0) {
            inv.setItem(SLOT_PREV, named(Material.ARROW,
                    PluginConfig.text(config.guiPrevPage)));
        }
        if (page < pages - 1) {
            inv.setItem(SLOT_NEXT, named(Material.ARROW,
                    PluginConfig.text(config.guiNextPage)));
        }
        inv.setItem(SLOT_INDICATOR, named(Material.PAPER, PluginConfig.text(
                config.guiPageIndicator
                        .replace("%page%", Integer.toString(page + 1))
                        .replace("%pages%", Integer.toString(pages)))));
    }

    /** 单独渲染溢出销毁按钮：开=红色火焰弹, 关=绿色屏障。文案首行为名, 其余行为 lore。 */
    private void renderVoidButton(Inventory inv, ChestData data) {
        boolean on = data.voidOverflow();
        Material icon = on ? Material.FIRE_CHARGE : Material.STRUCTURE_VOID;
        String text = on ? config.guiVoidOn : config.guiVoidOff;
        inv.setItem(SLOT_VOID, labeled(icon, text));
    }

    /**
     * 把 GUI 上半区（45 格）刷回 ChestData 的当前页。
     * 翻页前、关闭时调用，保证虚拟存储与界面一致。
     */
    public void writeBack(ChestGuiHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        ChestData data = holder.data();
        int base = holder.page() * PAGE_SLOTS;
        for (int i = 0; i < PAGE_SLOTS; i++) {
            data.setSlot(base + i, inv.getItem(i));
        }
    }

    /** 判定某槽位是否属于导航行（点击应被拦截）。 */
    public boolean isNavSlot(int rawSlot) {
        return rawSlot >= NAV_ROW_START && rawSlot < 54;
    }

    public int slotPrev() { return SLOT_PREV; }
    public int slotNext() { return SLOT_NEXT; }
    public int slotVoid() { return SLOT_VOID; }

    /**
     * 处理导航行点击：翻页或切换溢出销毁。
     * 内容区点击不经过这里（放行给原版）。
     */
    public void handleNavClick(Player player, ChestGuiHolder holder, int rawSlot) {
        if (rawSlot == SLOT_VOID) {                  // 切换溢出销毁开关, 只刷这一个按钮, 不打断查看
            holder.data().toggleVoidOverflow();
            Inventory inv = holder.getInventory();
            if (inv != null) renderVoidButton(inv, holder.data());
            store.saveAsync();                       // 开关是持久状态, 立即落盘防丢
            return;
        }
        if (rawSlot != SLOT_PREV && rawSlot != SLOT_NEXT) return;
        writeBack(holder);                       // 翻页前保存本页编辑
        int target = holder.page() + (rawSlot == SLOT_NEXT ? 1 : -1);
        open(player, holder.data(), holder.chestKey(), target);
    }

    /** 关闭时回写 + 注销界面 + 刷新悬浮文字。 */
    public void onClose(ChestGuiHolder holder) {
        writeBack(holder);
        Set<ChestGuiHolder> set = openViews.get(holder.chestKey());
        if (set != null) {
            set.remove(holder);
            if (set.isEmpty()) openViews.remove(holder.chestKey());
        }
        var block = VirtualStore.blockOf(holder.chestKey());
        if (block != null) {
            ChestData d = holder.data();
            holograms.refresh(block, d.usedStacks(), d.capacity(), d.pages());
        }
    }

    /** 该箱子当前是否有人打开着界面（搬运 tick 用来决定是否需要对账）。 */
    public boolean hasOpenView(String chestKey) {
        return openViews.containsKey(chestKey);
    }

    /**
     * 对账第一步：把该箱子所有打开界面的编辑吸收进 data（玩家存/取即时入账）。
     * 在搬运之前调用，保证玩家动作先落到权威数据，再参与本 tick 的搬运。
     */
    public void absorbEdits(String chestKey) {
        Set<ChestGuiHolder> set = openViews.get(chestKey);
        if (set == null) return;
        for (ChestGuiHolder holder : set) writeBack(holder);
    }

    /**
     * 对账第三步：把 data 的最新内容回显到该箱子所有打开界面（玩家看到堆叠增减 = 流入流出）。
     * 在搬运之后调用。仅刷内容区(0..44)，导航行不动。
     */
    public void refreshViews(String chestKey) {
        Set<ChestGuiHolder> set = openViews.get(chestKey);
        if (set == null) return;
        for (ChestGuiHolder holder : set) {
            Inventory inv = holder.getInventory();
            if (inv != null) renderPage(inv, holder.data(), holder.page());
        }
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 多行文案 -> 物品：首行作显示名，其余行作 lore（换行符切分）。 */
    private static ItemStack labeled(Material material, String multiline) {
        String[] lines = multiline.split("\n", -1);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(PluginConfig.text(lines[0]));
            if (lines.length > 1) {
                java.util.List<Component> lore = new java.util.ArrayList<>();
                for (int i = 1; i < lines.length; i++) lore.add(PluginConfig.text(lines[i]));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}