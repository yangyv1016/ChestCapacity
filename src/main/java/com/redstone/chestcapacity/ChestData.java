package com.redstone.chestcapacity;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
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
 * 它是箱子的唯一库存真相；物理箱只保留方块身份，不参与正常物品存储。
 */
public final class ChestData {

    private int pages;
    private ItemStack[] slots;
    /** Tracks non-empty slots so hot paths skip thousands of empty entries. */
    private BitSet occupied;
    /** Incrementally maintained metrics for holograms and comparator output. */
    private int usedStacks;
    private double fullness;
    private int firstPageUsedStacks;
    private double firstPageFullness;
    // 溢出销毁开关：开启后，虚拟存储已满、物理格又搬不下去的溢出物品由搬运层直接删除。
    // 存在意义：红石服里下游堵塞时避免物理格回堵导致漏斗卡死；权威在虚拟存储层，随 chests.yml 落盘。
    private boolean voidOverflow;
    // 悬浮字显示开关：默认关（false），玩家在 GUI 里按需开启。权威随 chests.yml 落盘。
    // 放在这里而非方块 PDC，是为了与 voidOverflow 同源、同落盘、同一 GUI 按钮语义，避免状态分散。
    private boolean hologramShown;
    // 箱子名字：放置时只继承扩容箱物品的铁砧自定义名；null/空 表示未命名。
    private String customName;
    // 名字悬浮字显示开关：默认关，与容量悬浮字开关相互独立。
    private boolean nameShown;
    // 比较器真实容量开关：默认关。关闭时只测量逻辑第一页，开启时测量全部虚拟槽位。
    private boolean comparatorRealCapacity;

    public ChestData(int pages) {
        this.pages = Math.max(1, pages);
        this.slots = new ItemStack[this.pages * PluginConfig.SLOTS_PER_PAGE];
        this.occupied = new BitSet(this.slots.length);
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

    public boolean comparatorRealCapacity() { return comparatorRealCapacity; }
    public void setComparatorRealCapacity(boolean v) { this.comparatorRealCapacity = v; }

    public ItemStack getSlot(int index) {
        return (index >= 0 && index < slots.length) ? slots[index] : null;
    }

    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= slots.length) return;
        ItemStack normalized = (stack == null || stack.getType().isAir()) ? null : stack;
        ItemStack previous = slots[index];
        if (previous != null) removeMetrics(index, previous);
        slots[index] = normalized;
        if (normalized != null) addMetrics(index, normalized);
    }

    /** Internal array for read-only bulk operations; callers must not mutate it. */
    public ItemStack[] slots() { return slots; }

    /** Return the next occupied slot at or after fromIndex, or -1. */
    public int nextOccupiedSlot(int fromIndex) {
        int index = occupied.nextSetBit(Math.max(0, fromIndex));
        return index >= slots.length ? -1 : index;
    }

    private void addMetrics(int index, ItemStack stack) {
        occupied.set(index);
        usedStacks++;
        double contribution = fullnessOf(stack);
        fullness += contribution;
        if (index < PluginConfig.SLOTS_PER_PAGE) {
            firstPageUsedStacks++;
            firstPageFullness += contribution;
        }
    }

    private void removeMetrics(int index, ItemStack stack) {
        occupied.clear(index);
        usedStacks--;
        double contribution = fullnessOf(stack);
        fullness -= contribution;
        if (index < PluginConfig.SLOTS_PER_PAGE) {
            firstPageUsedStacks--;
            firstPageFullness -= contribution;
        }
    }

    private static double fullnessOf(ItemStack stack) {
        return (double) stack.getAmount() / Math.max(1, stack.getMaxStackSize());
    }

    /** 是否已无任何内容（破坏时判断能否安全移除）。 */
    public boolean isEmpty() {
        return usedStacks == 0;
    }

    /**
     * 找到第一个能放下 stack 的位置并放入（优先合并到同类堆叠，其次找空位）。
     * 返回剩余放不下的部分（null 表示全部放下）。这是“下沉”搬运的核心原语。
     */
    public ItemStack push(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        int amount = stack.getAmount();
        int max = stack.getMaxStackSize();
        // Iterate only occupied slots when merging into similar stacks.
        for (int i = occupied.nextSetBit(0); i >= 0 && amount > 0;
             i = occupied.nextSetBit(i + 1)) {
            ItemStack stored = slots[i];
            if (!stored.isSimilar(stack)) continue;
            int space = max - stored.getAmount();
            if (space <= 0) continue;
            int move = Math.min(space, amount);
            ItemStack merged = stored.clone();
            merged.setAmount(stored.getAmount() + move);
            setSlot(i, merged);
            amount -= move;
        }
        // BitSet locates free slots without another linear scan.
        int empty = occupied.nextClearBit(0);
        while (empty < slots.length && amount > 0) {
            int move = Math.min(max, amount);
            ItemStack put = stack.clone();
            put.setAmount(move);
            setSlot(empty, put);
            amount -= move;
            empty = occupied.nextClearBit(empty + 1);
        }
        if (amount <= 0) return null;
        ItemStack rest = stack.clone();
        rest.setAmount(amount);
        return rest;
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
        rebuildMetrics();
        return overflow;
    }

    /** 清空全部槽位（拆除双联重分布时先清空剩余半，再从合并流回填）。 */
    public void clear() {
        java.util.Arrays.fill(slots, null);
        occupied.clear();
        usedStacks = 0;
        fullness = 0.0;
        firstPageUsedStacks = 0;
        firstPageFullness = 0.0;
    }

    /** 按槽序取出全部非空内容为列表（供拆除掉落 / 重分布收集连续流）。 */
    public List<ItemStack> contentsList() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : slots) if (s != null && !s.getType().isAir()) out.add(s);
        return out;
    }

    /** 当前存量（堆叠数，非物品件数），用于统计/悬浮文字显示。 */
    public int usedStacks() { return usedStacks; }

    public int comparatorSignal(boolean realCapacity) {
        int measuredSlots = realCapacity ? slots.length
                : Math.min(slots.length, PluginConfig.SLOTS_PER_PAGE);
        int nonEmpty = realCapacity ? usedStacks : firstPageUsedStacks;
        double measuredFullness = realCapacity ? fullness : firstPageFullness;
        if (nonEmpty == 0 || measuredSlots <= 0) return 0;
        return Math.min(15, 1 + (int) Math.floor(14.0 * measuredFullness / measuredSlots));
    }

    double fullness() { return fullness; }

    private void rebuildMetrics() {
        occupied = new BitSet(slots.length);
        usedStacks = 0;
        fullness = 0.0;
        firstPageUsedStacks = 0;
        firstPageFullness = 0.0;
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack != null && !stack.getType().isAir()) addMetrics(i, stack);
            else slots[i] = null;
        }
    }

    /** 深拷贝一份内容快照，供落盘时脱离主线程后续修改（避免并发改动同一 ItemStack）。 */
    public List<ItemStack> snapshotContents() {
        List<ItemStack> copy = new ArrayList<>(slots.length);
        for (ItemStack s : slots) copy.add(s == null ? null : s.clone());
        return copy;
    }

    /** 把自身写入给定配置节：容量、箱级开关、自定义名字与库存内容。 */
    public void writeTo(ConfigurationSection sec) {
        sec.set("pages", pages);
        sec.set("void-overflow", voidOverflow);
        sec.set("hologram-shown", hologramShown);
        sec.set("custom-name", customName);
        sec.set("name-shown", nameShown);
        sec.set("comparator-real-capacity", comparatorRealCapacity);
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
        data.comparatorRealCapacity = sec.getBoolean("comparator-real-capacity", false);
        List<?> list = sec.getList("contents");
        if (list != null) {
            int n = Math.min(list.size(), data.slots.length);
            for (int i = 0; i < n; i++) {
                Object o = list.get(i);
                if (o instanceof ItemStack stack) data.setSlot(i, stack);
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
