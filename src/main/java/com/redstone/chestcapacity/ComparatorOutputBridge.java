package com.redstone.chestcapacity;

import org.bukkit.block.Block;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 将插件计算出的模拟信号写入原版比较器方块实体。
 * 反射边界集中在本类，业务层不依赖 CraftBukkit/NMS 类型。
 */
final class ComparatorOutputBridge {

    private final Plugin plugin;
    private boolean failureLogged;

    private Constructor<?> blockPosConstructor;
    private Method craftWorldGetHandle;
    private Method levelGetBlockEntity;
    private Method levelGetBlockState;
    private Method blockStateGetBlock;
    private Method comparatorGetOutputSignal;
    private Method comparatorSetOutputSignal;
    private Method levelUpdateNeighborsAt;
    private Method levelUpdateNeighbourForOutputSignal;

    ComparatorOutputBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 写入目标信号；仅在实际值或亮起状态变化时通知红石邻居。 */
    boolean write(Block block, int requestedSignal) {
        int signal = Math.max(0, Math.min(15, requestedSignal));
        try {
            Object level = levelOf(block);
            Object pos = blockPosConstructor.newInstance(block.getX(), block.getY(), block.getZ());
            Object blockEntity = levelGetBlockEntity.invoke(level, pos);
            if (blockEntity == null || !blockEntity.getClass().getSimpleName().contains("Comparator")) {
                return false;
            }

            resolveComparatorMethods(blockEntity.getClass());
            int previous = ((Number) comparatorGetOutputSignal.invoke(blockEntity)).intValue();
            Comparator data = (Comparator) block.getBlockData();
            boolean shouldBePowered = signal > 0;
            boolean stateChanged = data.isPowered() != shouldBePowered;
            if (previous == signal && !stateChanged) return false;

            comparatorSetOutputSignal.invoke(blockEntity, signal);
            if (stateChanged) {
                data.setPowered(shouldBePowered);
                block.setBlockData(data, false);
            }

            Object blockState = levelGetBlockState.invoke(level, pos);
            Object nmsBlock = blockStateGetBlock.invoke(blockState);
            levelUpdateNeighborsAt.invoke(level, pos, nmsBlock);
            levelUpdateNeighbourForOutputSignal.invoke(level, pos, nmsBlock);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logFailure(ex);
            return false;
        }
    }

    private Object levelOf(Block block) throws ReflectiveOperationException {
        if (blockPosConstructor == null) {
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);
            craftWorldGetHandle = block.getWorld().getClass().getMethod("getHandle");

            Object level = craftWorldGetHandle.invoke(block.getWorld());
            Class<?> levelClass = level.getClass();
            levelGetBlockEntity = method(levelClass, "getBlockEntity", 1);
            levelGetBlockState = method(levelClass, "getBlockState", 1);
            levelUpdateNeighborsAt = method(levelClass, "updateNeighborsAt", 2);
            levelUpdateNeighbourForOutputSignal = method(
                    levelClass, "updateNeighbourForOutputSignal", 2);
        }
        return craftWorldGetHandle.invoke(block.getWorld());
    }

    private void resolveComparatorMethods(Class<?> blockEntityClass) throws ReflectiveOperationException {
        if (comparatorGetOutputSignal != null) return;
        comparatorGetOutputSignal = method(blockEntityClass, "getOutputSignal", 0);
        comparatorSetOutputSignal = method(blockEntityClass, "setOutputSignal", 1);

        Class<?> blockStateClass = levelGetBlockState.getReturnType();
        blockStateGetBlock = method(blockStateClass, "getBlock", 0);
    }

    private static Method method(Class<?> type, String name, int parameterCount)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.getName().equals(name)
                        && candidate.getParameterCount() == parameterCount) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
    }

    private void logFailure(Exception ex) {
        if (failureLogged) return;
        failureLogged = true;
        plugin.getLogger().severe("无法写入比较器模拟信号，Paper 运行时接口不匹配: "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }
}