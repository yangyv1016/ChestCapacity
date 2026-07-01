package com.redstone.chestcapacity;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 分页大容量 GUI。把 ChestData 的虚拟存储切成每页 45 格来展示。
 *
 * 布局（6 行 54 格）：
 *   行 0..4 (槽 0..44)  = 当前页内容，映射到 data.slots[page*45 + i]
 *   行 5   (槽 45..53)  = 导航行：45 上一页, 49 页码, 53 下一页, 其余玻璃板填充
 *
 * 回写策略：不做实时逐格监听，改为在“翻页”和“关闭”两个时机把可编辑区
 * 整片刷回 ChestData。这样点击事件只需拦截导航行，内容区放行原版拖拽，手感自然。
 */
public final class ChestGui {

    private static final int PAGE_SLOTS = PluginConfig.SLOTS_PER_PAGE; // 45
    private static final int NAV_ROW_START = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_INDICATOR = 49;
    private static final int SLOT_NEXT = 53;

    private final PluginConfig config;
    private final VirtualStore store;
    private final HologramManager holograms;

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

    /**
     * 处理导航行点击：翻页。返回处理后应保持打开(true)还是无操作(false)。
     * 内容区点击不经过这里（放行给原版）。
     */
    public void handleNavClick(Player player, ChestGuiHolder holder, int rawSlot) {
        if (rawSlot != SLOT_PREV && rawSlot != SLOT_NEXT) return;
        writeBack(holder);                       // 翻页前保存本页编辑
        int target = holder.page() + (rawSlot == SLOT_NEXT ? 1 : -1);
        open(player, holder.data(), holder.chestKey(), target);
    }

    /** 关闭时回写 + 刷新悬浮文字。 */
    public void onClose(ChestGuiHolder holder) {
        writeBack(holder);
        var block = VirtualStore.blockOf(holder.chestKey());
        if (block != null) {
            ChestData d = holder.data();
            holograms.refresh(block, d.usedStacks(), d.capacity(), d.pages());
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
}