package com.redstone.chestcapacity;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个扩容箱子的“虚拟存储”数据单元。
 *
 * 真相分层：
 *   pages 的权威来源是方块 PDC；这里冗余一份，纯粹是为了让 VirtualStore
 *   在服务器重启后能脱离方块独立恢复出正确的数组尺寸。
 *   放置/调整时二者由上层保持同步。
 *
 * slots 数组长度 = pages * SLOTS_PER_PAGE，元素可为 null（空格）。
 * 这是“扩容出来”的容量，不含物理 27 格（那 27 格走原版红石接口）。
 */
public final class ChestData {

    private int pages;
    private ItemStack[] slots;
    // 溢出销毁开关：开启后，虚拟存储已满、物理格又搬不下去的溢出物品由搬运层直接删除。
    // 存在意义：红石服里下游堵塞时避免物理格回堵导致漏斗卡死；权威在虚拟存储层，随 chests.yml 落盘。
    private boolean voidOverflow;
    // 悬浮字显示开关：默认关（false），玩家在 GUI 里按需开启。权威随 chests.yml 落盘。
    // 放在这里而非方块 PDC，是为了与 voidOverflow 同源、同落盘、同一 GUI 按钮语义，避免状态分散。
    private boolean hologramShown;
    // 箱子名字：放置时只继承扩容箱物品的铁砧自定义名；null/空 表示未命名。
    private String customName;
    // 名字悬浮字显示开关：默认关。仅在容量悬浮字(hologramShown)也开启时才实际显示(联动约束)。
    private boolean nameShown;

    public ChestData(int pages) {
        this.pages = Math.max(1, pages);
        this.slots = new ItemStack[this.pages * PluginConfig.SLOTS_PER_PAGE];
    }

    public int pages() { return pages; }
    public int capacity() { return slots.length; }

    public boolean voidOverflow() { return voidOverflow; }
    public void setVoidOverflow(boolean v) { this.voidOverflow = v; }
    /** 翻转溢出销毁开关，返回翻转后的新状态（供按钮点击调用）。 */
    public boolean toggleVoidOverflow() { this.voidOverflow = !this.voidOverflow; return this.voidOverflow; }

    public boolean isHologramShown() { return hologramShown; }
    public void setHologramShown(boolean v) { this.hologramShown = v; }
    /** 翻转悬浮字显示开关，返回翻转后的新状态（供按钮点击调用）。 */
    public boolean toggleHologramShown() { this.hologramShown = !this.hologramShown; return this.hologramShown; }

    /** 箱子名字（可空）。放置时从物品 displayName 继承。 */
    public String customName() { return customName; }
    public void setCustomName(String name) {
        this.customName = (name == null || name.isBlank()) ? null : name;
    }

    public boolean isNameShown() { return nameShown; }
    public void setNameShown(boolean v) { this.nameShown = v; }
    /** 翻转名字悬浮字开关，返回翻转后的新状态。 */
    public boolean toggleNameShown() { this.nameShown = !this.nameShown; return this.nameShown; }

    public ItemStack getSlot(int index) {
        return (index >= 0 && index < slots.length) ? slots[index] : null;
    }

    public void setSlot(int index, ItemStack stack) {
        if (index >= 0 && index < slots.length) {
            slots[index] = (stack == null || stack.getType().isAir()) ? null : stack;
        }
    }

    /** 直接引用内部数组，供 GUI/搬运批量读写。调用方不得改变长度。 */
    public ItemStack[] slots() { return slots; }

    /** 是否已无任何内容（破坏时判断能否安全移除）。 */
    public boolean isEmpty() {
        for (ItemStack s : slots) if (s != null) return false;
        return true;
    }

    /**
     * 找到第一个能放下 stack 的位置并放入（优先合并到同类堆叠，其次找空位）。
     * 返回剩余放不下的部分（null 表示全部放下）。这是“下沉”搬运的核心原语。
     */
    public ItemStack push(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        int amount = stack.getAmount();
        int max = stack.getMaxStackSize();
        // 先合并到同类未满堆叠
        for (int i = 0; i < slots.length && amount > 0; i++) {
            ItemStack s = slots[i];
            if (s == null || !s.isSimilar(stack)) continue;
            int space = max - s.getAmount();
            if (space <= 0) continue;
            int move = Math.min(space, amount);
            s.setAmount(s.getAmount() + move);
            amount -= move;
        }
        // 再找空位
        for (int i = 0; i < slots.length && amount > 0; i++) {
            if (slots[i] != null) continue;
            int move = Math.min(max, amount);
            ItemStack put = stack.clone();
            put.setAmount(move);
            slots[i] = put;
            amount -= move;
        }
        if (amount <= 0) return null;
        ItemStack rest = stack.clone();
        rest.setAmount(amount);
        return rest;
    }

    /**
     * 从存储里取出最多 limit 件的一个堆叠（用于“补货”搬运）。
     * 返回 null 表示存储已空。
     */
    public ItemStack pull(int limit) {
        for (int i = 0; i < slots.length; i++) {
            ItemStack s = slots[i];
            if (s == null) continue;
            int take = Math.min(limit, s.getAmount());
            ItemStack out = s.clone();
            out.setAmount(take);
            if (take >= s.getAmount()) slots[i] = null;
            else s.setAmount(s.getAmount() - take);
            return out;
        }
        return null;
    }

    /**
     * 调整容量。缩容时若尾部有物品，返回被截断挤出的物品（由上层决定掉落/塞回）。
     * 扩容则只是补 null 尾部，不丢数据。
     */
    public List<ItemStack> resize(int newPages) {
        newPages = Math.max(1, newPages);
        if (newPages == pages) return List.of();
        int newLen = newPages * PluginConfig.SLOTS_PER_PAGE;
        ItemStack[] next = new ItemStack[newLen];
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            if (i < newLen) next[i] = slots[i];
            else overflow.add(slots[i]);
        }
        this.slots = next;
        this.pages = newPages;
        return overflow;
    }

    /** 清空全部槽位（拆除双联重分布时先清空剩余半，再从合并流回填）。 */
    public void clear() {
        java.util.Arrays.fill(slots, null);
    }

    /** 按槽序取出全部非空内容为列表（供拆除掉落 / 重分布收集连续流）。 */
    public List<ItemStack> contentsList() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : slots) if (s != null && !s.getType().isAir()) out.add(s);
        return out;
    }

    /** 当前存量（堆叠数，非物品件数），用于统计/悬浮文字显示。 */
    public int usedStacks() {
        int n = 0;
        for (ItemStack s : slots) if (s != null) n++;
        return n;
    }

    /** 深拷贝一份内容快照，供落盘时脱离主线程后续修改（避免并发改动同一 ItemStack）。 */
    public List<ItemStack> snapshotContents() {
        List<ItemStack> copy = new ArrayList<>(slots.length);
        for (ItemStack s : slots) copy.add(s == null ? null : s.clone());
        return copy;
    }

    /** 把自身写入给定配置节：pages / void-overflow / hologram-shown / name / name-shown / contents。 */
    public void writeTo(ConfigurationSection sec) {
        sec.set("pages", pages);
        sec.set("void-overflow", voidOverflow);
        sec.set("hologram-shown", hologramShown);
        sec.set("custom-name", customName);
        sec.set("name-shown", nameShown);
        sec.set("contents", snapshotContents());
    }

    /** 从配置节重建。contents 长度不足时按 pages 补齐，超出则截断（防脏数据）。 */
    public static ChestData readFrom(ConfigurationSection sec) {
        int pages = Math.max(1, sec.getInt("pages", 1));
        ChestData data = new ChestData(pages);
        data.voidOverflow = sec.getBoolean("void-overflow", false);
        data.hologramShown = sec.getBoolean("hologram-shown", false);  // 默认关闭
        data.setCustomName(sec.getString("custom-name", null));
        data.nameShown = sec.getBoolean("name-shown", false);          // 默认关闭
        List<?> list = sec.getList("contents");
        if (list != null) {
            int n = Math.min(list.size(), data.slots.length);
            for (int i = 0; i < n; i++) {
                Object o = list.get(i);
                if (o instanceof ItemStack stack) data.slots[i] = stack;
            }
        }
        return data;
    }

    @Override
    public String toString() {
        return "ChestData{pages=" + pages + ", used=" + usedStacks()
                + "/" + slots.length + "}";
    }
}