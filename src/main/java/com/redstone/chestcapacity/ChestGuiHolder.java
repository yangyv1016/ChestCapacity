package com.redstone.chestcapacity;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 分页 GUI 的归属标记。一个 holder = 某个玩家正在查看某个箱子的某一页。
 *
 * 作用：InventoryClickEvent / InventoryCloseEvent 拿到 holder 即可判定
 * “这是本插件的扩容箱界面”，并取回它对应的 ChestData 与当前页码，
 * 无需用标题字符串反查（脆弱）。
 *
 * 注意：GUI 展示的是 ChestData 的“虚拟存储”内容，不含物理 27 格。
 * 物理格由红石/漏斗和 TransferService 管，GUI 不碰。
 */
public final class ChestGuiHolder implements InventoryHolder {

    private final ChestData data;
    private final String chestKey;   // 该箱子的坐标键, 供关闭回写与刷新悬浮文字定位
    private int page;                // 当前页(0-based)
    private Inventory inventory;

    public ChestGuiHolder(ChestData data, String chestKey, int page) {
        this.data = data;
        this.chestKey = chestKey;
        this.page = page;
    }

    public ChestData data() { return data; }
    public String chestKey() { return chestKey; }
    public int page() { return page; }
    public void setPage(int page) { this.page = page; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}