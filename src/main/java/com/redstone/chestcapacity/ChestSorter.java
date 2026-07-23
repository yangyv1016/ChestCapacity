package com.redstone.chestcapacity;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟仓库整理算法。
 *
 * 输入与输出均为脱离 GUI 的物品序列：先按完整物品身份合并数量，再按材料注册名排序，
 * 最后依据每种物品自己的最大堆叠数重新拆分。同材料的不同元数据变体保持首次出现顺序，
 * 避免自定义名称、附魔、耐久或插件 NBT 相互覆盖。
 */
public final class ChestSorter {

    private ChestSorter() {}

    public static List<ItemStack> sortAndCompact(List<ItemStack> contents) {
        Map<ItemStack, Long> totals = new LinkedHashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            ItemStack identity = stack.clone();
            identity.setAmount(1);
            totals.merge(identity, (long) stack.getAmount(), Long::sum);
        }

        List<Map.Entry<ItemStack, Long>> groups = new ArrayList<>(totals.entrySet());
        groups.sort(Comparator.comparing(entry -> entry.getKey().getType().getKey().toString()));

        List<ItemStack> sorted = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> group : groups) {
            ItemStack identity = group.getKey();
            long remaining = group.getValue();
            int maxStack = Math.max(1, identity.getMaxStackSize());
            while (remaining > 0) {
                int amount = (int) Math.min(maxStack, remaining);
                ItemStack stack = identity.clone();
                stack.setAmount(amount);
                sorted.add(stack);
                remaining -= amount;
            }
        }
        return sorted;
    }
}