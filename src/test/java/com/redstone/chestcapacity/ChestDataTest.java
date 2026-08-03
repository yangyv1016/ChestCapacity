package com.redstone.chestcapacity;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChestDataTest {

    private static final Material STONE = material("STONE");
    private static final Material DIRT = material("DIRT");

    @Test
    void sparseStorageTracksOccupiedSlotsAndMetrics() {
        ChestData data = new ChestData(54);
        data.setSlot(data.capacity() - 1, stack(STONE, 32, 64));

        assertEquals(1, data.usedStacks());
        assertEquals(data.capacity() - 1, data.nextOccupiedSlot(0));
        assertEquals(-1, data.nextOccupiedSlot(data.capacity()));
        assertEquals(0, data.comparatorSignal(false));
        assertEquals(1, data.comparatorSignal(true));
    }

    @Test
    void pushMergesBeforeUsingAnEmptySlot() {
        ChestData data = new ChestData(54);
        data.setSlot(data.capacity() - 1, stack(STONE, 63, 64));

        assertNull(data.push(stack(STONE, 1, 64)));
        assertEquals(64, data.getSlot(data.capacity() - 1).getAmount());
        assertEquals(1, data.usedStacks());
    }

    @Test
    void resizeRebuildsIndexesAndReturnsTailOverflow() {
        ChestData data = new ChestData(2);
        data.setSlot(0, stack(DIRT, 1, 64));
        data.setSlot(89, stack(STONE, 2, 64));

        List<ItemStack> overflow = data.resize(1);

        assertEquals(1, data.usedStacks());
        assertEquals(0, data.nextOccupiedSlot(0));
        assertEquals(1, overflow.size());
        assertEquals(STONE, overflow.getFirst().getType());
    }

    @Test
    void replacingAndClearingSlotsKeepsCachedSignalCorrect() {
        ChestData data = new ChestData(1);
        data.setSlot(0, stack(STONE, 64, 64));
        assertEquals(1, data.usedStacks());
        assertEquals(1, data.comparatorSignal(false));

        data.setSlot(0, stack(STONE, 32, 64));
        assertEquals(1, data.usedStacks());
        assertEquals(1, data.comparatorSignal(false));

        data.clear();
        assertTrue(data.isEmpty());
        assertEquals(0, data.comparatorSignal(true));
    }

    private static Material material(String name) {
        Material material = mock(Material.class, name);
        when(material.isAir()).thenReturn(false);
        return material;
    }

    private static ItemStack stack(Material material, int initialAmount, int maxStackSize) {
        AtomicInteger amount = new AtomicInteger(initialAmount);
        ItemStack mock = mock(ItemStack.class);
        when(mock.getType()).thenReturn(material);
        when(mock.getAmount()).thenAnswer(ignored -> amount.get());
        doAnswer(invocation -> {
            amount.set(invocation.getArgument(0));
            return null;
        }).when(mock).setAmount(anyInt());
        when(mock.getMaxStackSize()).thenReturn(maxStackSize);
        when(mock.isSimilar(any(ItemStack.class))).thenAnswer(invocation -> {
            ItemStack other = invocation.getArgument(0);
            return other != null && other.getType() == material;
        });
        when(mock.clone()).thenAnswer(ignored -> stack(material, amount.get(), maxStackSize));
        return mock;
    }
}
