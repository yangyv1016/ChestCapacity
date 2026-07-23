package com.redstone.chestcapacity;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 一次 GUI 打开所面对的“逻辑存储”：由 1 或 2 段 ChestData 按顺序拼成的连续槽空间。
 *
 * 存在意义（收敛复杂度）：
 *   把“单箱还是双联”的差异全部封在这里。GUI 只跟“全局槽下标 0..capacity-1”打交道，
 *   由本类路由到 (第几段, 段内第几格)。于是 renderPage/writeBack/镜像逻辑无需任何 if 双联判断。
 *
 * 段顺序：与 ChestPairing.blocks 对齐，LEFT 段在前。容量 = Σ 各段 capacity（页数叠加）。
 *
 * 权威分层不变：本类不持有内容，只是对底层多份 ChestData 的“连续视图”。
 *   写入 setSlot 直接落到对应段的 ChestData，落盘仍由各自 ChestData.writeTo 负责，
 *   所以拆掉双联的一半时，另一半数据天然完好，无需迁移。
 */
public final class ChestView {

    // 与 blockKeys[i] 一一对应的段数据。segments[i] 覆盖全局槽 [offset[i], offset[i]+cap[i])。
    private final List<ChestData> segments;
    private final List<String> blockKeys;   // 各段对应的箱子坐标键（LEFT 在前），供写回/落盘/悬浮字定位
    private final int[] offset;              // 各段在全局槽空间里的起始下标
    private final int capacity;             // 全局总槽数 = Σ 段容量

    public ChestView(List<ChestData> segments, List<String> blockKeys) {
        if (segments.isEmpty() || segments.size() != blockKeys.size()) {
            throw new IllegalArgumentException("segments 与 blockKeys 必须非空且等长");
        }
        this.segments = segments;
        this.blockKeys = blockKeys;
        this.offset = new int[segments.size()];
        int acc = 0;
        for (int i = 0; i < segments.size(); i++) {
            offset[i] = acc;
            acc += segments.get(i).capacity();
        }
        this.capacity = acc;
    }

    /** 单段快捷构造（单箱）。 */
    public static ChestView single(ChestData data, String key) {
        return new ChestView(List.of(data), List.of(key));
    }

    public int capacity() { return capacity; }

    /** 总页数 = 全局容量 / 每页格数。因每段容量都是整页，能整除。 */
    public int totalPages() { return capacity / PluginConfig.SLOTS_PER_PAGE; }

    public List<ChestData> segments() { return segments; }
    public List<String> blockKeys() { return blockKeys; }

    /**
     * 拆除某一段后，把「合并连续内容」按 trim_tail 语义重分布到剩余段。
     *
     * 语义（严格截断，贴合原版箱子体感）：
     *   1. 按段序(LEFT-first)把全部段拼成一条【含空格、保持槽位】的连续内容视图。
     *   2. 剩余段总容量 = N。原样接收连续视图的前 N 格（槽对槽平移，
     *      不压实、不合并同类、不填补空洞）—— 物品位置不被重排。
     *   3. 第 N 格之后的非空内容按槽序原样掉落（尾端溢出）。
     *
     * removedKey 指定被拆的那一段（不再回填）。返回需要掉落的尾端溢出。
     * 若无剩余段（单段视图且就是 removedKey），整份非空内容作为溢出返回。
     */
    public List<ItemStack> redistributeExcluding(String removedKey) {
        // 1) 拼合并连续视图：含 null 空格、保持原槽序（前 N 格语义据此成立）
        ItemStack[] merged = new ItemStack[capacity];
        int gi = 0;
        for (ChestData d : segments) {
            ItemStack[] src = d.slots();
            System.arraycopy(src, 0, merged, gi, src.length);
            gi += src.length;
        }

        // 2) 找出剩余段并清空（被拆段不回填，其登记由上层从 store 移除）
        List<ChestData> remain = new java.util.ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (blockKeys.get(i).equals(removedKey)) continue;
            remain.add(segments.get(i));
        }
        for (ChestData d : remain) d.clear();

        // 3) 前 N 格原样槽对槽平移进剩余段（含空格），idx 走到哪填到哪
        int idx = 0;
        for (ChestData d : remain) {
            for (int i = 0; i < d.capacity(); i++) {
                d.setSlot(i, merged[idx]);   // 原样搬(null 也照搬)，不压实不合并
                idx++;
            }
        }

        // 4) 尾端第 idx 格起的非空内容原样掉落
        List<ItemStack> overflow = new java.util.ArrayList<>();
        for (; idx < capacity; idx++) {
            if (merged[idx] != null && !merged[idx].getType().isAir()) overflow.add(merged[idx]);
        }
        return overflow;
    }

    /** 全局槽 -> 落在哪一段。越界返回 -1。 */
    private int segmentIndexOf(int globalSlot) {
        if (globalSlot < 0 || globalSlot >= capacity) return -1;
        for (int i = offset.length - 1; i >= 0; i--) {
            if (globalSlot >= offset[i]) return i;
        }
        return -1;
    }

    public ItemStack getSlot(int globalSlot) {
        int seg = segmentIndexOf(globalSlot);
        if (seg < 0) return null;
        return segments.get(seg).getSlot(globalSlot - offset[seg]);
    }

    public void setSlot(int globalSlot, ItemStack stack) {
        int seg = segmentIndexOf(globalSlot);
        if (seg < 0) return;
        segments.get(seg).setSlot(globalSlot - offset[seg], stack);
    }

    /** 将物品按段顺序写入整个逻辑仓库，返回无法容纳的剩余部分。 */
    public ItemStack push(ItemStack stack) {
        ItemStack rest = stack == null ? null : stack.clone();
        for (ChestData segment : segments) {
            if (rest == null) break;
            rest = segment.push(rest);
        }
        return rest;
    }

    /** 返回当前逻辑仓库还能接收多少件指定物品，不改变存储。 */
    public int acceptanceOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0;
        long space = 0;
        int max = Math.max(1, stack.getMaxStackSize());
        for (ChestData segment : segments) {
            for (ItemStack stored : segment.slots()) {
                if (stored == null || stored.getType().isAir()) space += max;
                else if (stored.isSimilar(stack)) space += Math.max(0, max - stored.getAmount());
                if (space >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) space;
    }

    /** 从指定全局槽取出最多 limit 件，槽为空或越界时返回 null。 */
    public ItemStack pullFromSlot(int globalSlot, int limit) {
        if (limit <= 0) return null;
        ItemStack current = getSlot(globalSlot);
        if (current == null || current.getType().isAir()) return null;
        int take = Math.min(limit, current.getAmount());
        ItemStack result = current.clone();
        result.setAmount(take);
        if (take >= current.getAmount()) setSlot(globalSlot, null);
        else {
            ItemStack retained = current.clone();
            retained.setAmount(current.getAmount() - take);
            setSlot(globalSlot, retained);
        }
        return result;
    }

    /**
     * 按原版容器公式计算比较器输入信号。
     * 箱级开关关闭时只测量逻辑第一页，避免通过信号暴露扩容后的真实容量。
     */
    public int comparatorSignal() {
        int measuredSlots = comparatorRealCapacity()
                ? capacity
                : Math.min(capacity, PluginConfig.SLOTS_PER_PAGE);
        double fullness = 0.0;
        boolean nonEmpty = false;
        for (int slot = 0; slot < measuredSlots; slot++) {
            ItemStack stack = getSlot(slot);
            if (stack == null || stack.getType().isAir()) continue;
            nonEmpty = true;
            fullness += (double) stack.getAmount() / Math.max(1, stack.getMaxStackSize());
        }
        if (!nonEmpty || measuredSlots <= 0) return 0;
        return Math.min(15, 1 + (int) Math.floor(14.0 * fullness / measuredSlots));
    }

    /** 深拷贝完整逻辑仓库的非空内容，供整理等批处理脱离底层数组运算。 */
    public List<ItemStack> snapshotContents() {
        List<ItemStack> contents = new java.util.ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            ItemStack stack = getSlot(i);
            if (stack != null && !stack.getType().isAir()) contents.add(stack.clone());
        }
        return contents;
    }

    /**
     * 用连续物品序列替换整个逻辑仓库，剩余槽位清空。
     * 调用方必须保证内容不超过容量；复制 ItemStack，避免外部后续修改污染权威数据。
     */
    public void replaceContents(List<ItemStack> contents) {
        if (contents.size() > capacity) {
            throw new IllegalArgumentException("整理结果超过仓库容量");
        }
        for (ChestData segment : segments) segment.clear();
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            setSlot(i, stack == null ? null : stack.clone());
        }
    }

    /** 合计已用堆叠数（供合并悬浮字显示）。 */
    public int usedStacks() {
        int n = 0;
        for (ChestData d : segments) n += d.usedStacks();
        return n;
    }

    /**
     * 溢出销毁：任一段开启即视为开启（保守取并集, 面向红石防堵初衷）。
     * 切换时对所有段统一置位，保证双联两半语义一致。
     */
    public boolean voidOverflow() {
        for (ChestData d : segments) if (d.voidOverflow()) return true;
        return false;
    }

    public boolean toggleVoidOverflow() {
        boolean next = !voidOverflow();
        for (ChestData d : segments) d.setVoidOverflow(next);
        return next;
    }

    /** 比较器容量模式：双联两半统一设置，任一旧数据为开启即视为开启。 */
    public boolean comparatorRealCapacity() {
        for (ChestData d : segments) if (d.comparatorRealCapacity()) return true;
        return false;
    }

    public boolean toggleComparatorRealCapacity() {
        boolean next = !comparatorRealCapacity();
        for (ChestData d : segments) d.setComparatorRealCapacity(next);
        return next;
    }

    /** 悬浮字显示：同样取并集 + 统一置位，双联两半共用一个显示状态。 */
    public boolean hologramShown() {
        for (ChestData d : segments) if (d.isHologramShown()) return true;
        return false;
    }

    public boolean toggleHologramShown() {
        boolean next = !hologramShown();
        for (ChestData d : segments) d.setHologramShown(next);
        return next;
    }

    /**
     * 箱子名字取规范首段（双联=LEFT）。双联只显示一个名字，首段顺序由 ChestPairing 稳定保证。
     * 名字来源是放置时扩容箱物品的 displayName（可由铁砧修改）。
     */
    public String customName() {
        return segments.get(0).customName();
    }

    /** 名字悬浮字开关：同样取并集 + 统一置位，双联两半共用一个状态。 */
    public boolean nameShown() {
        for (ChestData d : segments) if (d.isNameShown()) return true;
        return false;
    }

    public boolean toggleNameShown() {
        boolean next = !nameShown();
        for (ChestData d : segments) d.setNameShown(next);
        return next;
    }
}