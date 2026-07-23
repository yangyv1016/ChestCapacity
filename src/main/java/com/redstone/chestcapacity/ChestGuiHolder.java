package com.redstone.chestcapacity;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 分页 GUI 的归属标记。一个 holder = 某个玩家正在查看某个（单箱或双联）大仓库的某一页。
 *
 * 作用：InventoryClickEvent / InventoryCloseEvent 拿到 holder 即可判定
 * “这是本插件的扩容箱界面”，并取回它对应的 ChestView 与当前页码，
 * 无需用标题字符串反查（脆弱）。
 *
 * 双联支持：持有的是 ChestView（1 或 2 段 ChestData 的连续视图），
 * 而非单份 ChestData。GUI 全程只跟 view 的全局槽打交道，不感知单/双联。
 * chestKey 是打开入口的规范键（双联=左半），仅用于标题与日志；搬运/写回/悬浮字
 * 定位改用 view.blockKeys() 覆盖全部段。
 *
 * 注意：GUI 展示的是完整逻辑库存。物理箱不承载正常物品，红石 I/O 由 StorageIoService
 * 直接与 view 交换数据，因此不会存在 GUI 不可见的缓冲物品。
 */
public final class ChestGuiHolder implements InventoryHolder {

    private final ChestView view;
    private final String chestKey;   // 打开入口的规范键（双联=左半），供标题/日志
    private int page;                // 当前页(0-based)
    private Inventory inventory;

    public ChestGuiHolder(ChestView view, String chestKey, int page) {
        this.view = view;
        this.chestKey = chestKey;
        this.page = page;
    }

    public ChestView view() { return view; }
    public String chestKey() { return chestKey; }
    public int page() { return page; }
    public void setPage(int page) { this.page = page; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}