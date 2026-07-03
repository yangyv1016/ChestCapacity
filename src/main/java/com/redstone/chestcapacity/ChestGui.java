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
 * 分页大容量 GUI。把 ChestView（1 或 2 段 ChestData 的连续视图）切成每页 45 格展示。
 *
 * 布局（6 行 54 格）：
 *   行 0..4 (槽 0..44)  = 当前页内容，映射到 view 全局槽 [page*45, page*45+45)
 *   行 5   (槽 45..53)  = 导航行：45 上一页, 47 溢出销毁, 49 页码, 51 悬浮字, 53 下一页
 *
 * 单/双联无感知：GUI 只跟 view 的全局槽打交道，段路由封在 ChestView 内。
 *   双联 = view 有两段（LEFT 在前），总页数与容量自然叠加，翻页连续跨越两半。
 *
 * 实时镜像模型：
 *   view 背后的 ChestData 是唯一权威，GUI 界面只是它“当前页”的实时镜像。
 *   每个搬运 tick 对打开着的界面做有序对账（见 TransferService.tick）：
 *     1. absorbEdits : 界面 --writeBack--> view   （玩家存/取即时入账）
 *     2. 搬运照常跑   : 物理格 <---> 各段 ChestData（漏斗喂的货下沉进来）
 *     3. refreshViews: view --renderPage--> 界面   （玩家看到堆叠增减 = 流入流出）
 *   三步串行且都在主线程，tick 结尾界面与权威恒一致，不存在过期快照覆盖。
 */
public final class ChestGui {

    private static final int PAGE_SLOTS = PluginConfig.SLOTS_PER_PAGE; // 45
    private static final int NAV_ROW_START = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_VOID = 47;        // 溢出销毁开关按钮
    private static final int SLOT_INDICATOR = 49;
    private static final int SLOT_HOLO = 51;        // 悬浮字显示开关按钮
    private static final int SLOT_NEXT = 53;

    private final PluginConfig config;
    private final VirtualStore store;
    private final HologramManager holograms;

    // 打开中的界面注册表：箱子坐标键 -> 正在查看它的 holder 集合。
    // 双联时 view 的两个段 key 都指向同一批 holder，搬运 tick 用任一 key 都能定位对账。
    private final Map<String, Set<ChestGuiHolder>> openViews = new HashMap<>();

    public ChestGui(PluginConfig config, VirtualStore store, HologramManager holograms) {
        this.config = config;
        this.store = store;
        this.holograms = holograms;
    }

    /** 打开指定 view 的某一页。chestKey = 规范键（双联=左半），用于标题/日志。 */
    public void open(Player player, ChestView view, String chestKey, int page) {
        int maxPage = view.totalPages() - 1;
        page = Math.max(0, Math.min(page, maxPage));

        ChestGuiHolder holder = new ChestGuiHolder(view, chestKey, page);
        Component title = PluginConfig.text(config.guiTitle
                .replace("%page%", Integer.toString(page + 1))
                .replace("%pages%", Integer.toString(view.totalPages())));
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        renderPage(inv, view, page);
        renderNav(inv, page, view.totalPages());
        renderVoidButton(inv, view);
        renderHoloButton(inv, view);
        registerView(holder);                    // 按 view 的每个段 key 注册, 让搬运 tick 能对账
        player.openInventory(inv);
    }

    /** 把 view 的第 page 页内容铺进 GUI 上半区（45 格）。 */
    private void renderPage(Inventory inv, ChestView view, int page) {
        int base = page * PAGE_SLOTS;
        for (int i = 0; i < PAGE_SLOTS; i++) {
            inv.setItem(i, view.getSlot(base + i));
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
    private void renderVoidButton(Inventory inv, ChestView view) {
        boolean on = view.voidOverflow();
        Material icon = on ? Material.FIRE_CHARGE : Material.STRUCTURE_VOID;
        String text = on ? config.guiVoidOn : config.guiVoidOff;
        inv.setItem(SLOT_VOID, labeled(icon, text));
    }

    /** 单独渲染悬浮字开关按钮：开=荧光物品展示框, 关=物品展示框。 */
    private void renderHoloButton(Inventory inv, ChestView view) {
        boolean on = view.hologramShown();
        Material icon = on ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME;
        String text = on ? config.guiHoloOn : config.guiHoloOff;
        inv.setItem(SLOT_HOLO, labeled(icon, text));
    }

    /**
     * 把 GUI 上半区（45 格）刷回 view 的当前页。
     * 翻页前、关闭时、对账第一步调用，保证权威与界面一致。
     */
    public void writeBack(ChestGuiHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        ChestView view = holder.view();
        int base = holder.page() * PAGE_SLOTS;
        for (int i = 0; i < PAGE_SLOTS; i++) {
            view.setSlot(base + i, inv.getItem(i));
        }
    }

    /** 判定某槽位是否属于导航行（点击应被拦截）。 */
    public boolean isNavSlot(int rawSlot) {
        return rawSlot >= NAV_ROW_START && rawSlot < 54;
    }

    public int slotPrev() { return SLOT_PREV; }
    public int slotNext() { return SLOT_NEXT; }
    public int slotVoid() { return SLOT_VOID; }
    public int slotHolo() { return SLOT_HOLO; }

    /**
     * 处理导航行点击：翻页、切换溢出销毁、切换悬浮字。
     * 内容区点击不经过这里（放行给原版）。
     */
    public void handleNavClick(Player player, ChestGuiHolder holder, int rawSlot) {
        ChestView view = holder.view();
        if (rawSlot == SLOT_VOID) {                  // 切换溢出销毁开关（双联两半统一置位）
            view.toggleVoidOverflow();
            Inventory inv = holder.getInventory();
            if (inv != null) renderVoidButton(inv, view);
            store.saveAsync();
            return;
        }
        if (rawSlot == SLOT_HOLO) {                  // 切换悬浮字（双联两半共用一个显示）
            view.toggleHologramShown();
            Inventory inv = holder.getInventory();
            if (inv != null) renderHoloButton(inv, view);
            holograms.syncFor(view);                 // 立即挂出/清除
            store.saveAsync();
            return;
        }
        if (rawSlot != SLOT_PREV && rawSlot != SLOT_NEXT) return;
        writeBack(holder);                       // 翻页前保存本页编辑
        int target = holder.page() + (rawSlot == SLOT_NEXT ? 1 : -1);
        open(player, view, holder.chestKey(), target);
    }

    /** 关闭时回写 + 注销界面 + 刷新悬浮文字。 */
    public void onClose(ChestGuiHolder holder) {
        writeBack(holder);
        unregisterView(holder);
        holograms.syncFor(holder.view());
    }

    /** 该箱子当前是否有人打开着界面（搬运 tick 用来决定是否需要对账）。 */
    public boolean hasOpenView(String chestKey) {
        return openViews.containsKey(chestKey);
    }

    /**
     * 对账第一步：把该箱子所有打开界面的编辑吸收进 view（玩家存/取即时入账）。
     * 在搬运之前调用，保证玩家动作先落到权威数据，再参与本 tick 的搬运。
     */
    public void absorbEdits(String chestKey) {
        Set<ChestGuiHolder> set = openViews.get(chestKey);
        if (set == null) return;
        for (ChestGuiHolder holder : set) writeBack(holder);
    }

    /**
     * 对账第三步：把 view 的最新内容回显到该箱子所有打开界面（玩家看到堆叠增减 = 流入流出）。
     * 在搬运之后调用。仅刷内容区(0..44)，导航行不动。
     */
    public void refreshViews(String chestKey) {
        Set<ChestGuiHolder> set = openViews.get(chestKey);
        if (set == null) return;
        for (ChestGuiHolder holder : set) {
            Inventory inv = holder.getInventory();
            if (inv != null) renderPage(inv, holder.view(), holder.page());
        }
    }

    /** 按 view 的每个段 key 注册 holder（双联时两个 key 都指向它）。 */
    private void registerView(ChestGuiHolder holder) {
        for (String key : holder.view().blockKeys()) {
            openViews.computeIfAbsent(key, k -> new HashSet<>()).add(holder);
        }
    }

    private void unregisterView(ChestGuiHolder holder) {
        for (String key : holder.view().blockKeys()) {
            Set<ChestGuiHolder> set = openViews.get(key);
            if (set == null) continue;
            set.remove(holder);
            if (set.isEmpty()) openViews.remove(key);
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