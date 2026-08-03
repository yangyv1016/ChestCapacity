package com.redstone.chestcapacity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 扩容箱子的虚拟存储总管：坐标 -> ChestData 的内存映射，并负责 YAML 落盘。
 *
 * 权威真相分层（重要）：
 *   · 方块 PDC     持有 pages，是“这个箱子扩容了没、多大”的权威来源。
 *   · VirtualStore 持有扩容出来的物品内容，key 与方块坐标一一对应。
 *   放置/破坏/调整由监听器驱动两者同步；本类只管“按坐标存取内容 + 落盘”。
 *
 * 并发边界：
 *   所有 Map 读写只在主线程发生。落盘时在主线程用 snapshot 生成 YAML 文本，
 *   仅把字符串写文件的 IO 交给异步线程，绝不让 Bukkit 对象跨线程。
 */
public final class VirtualStore {

    private final Plugin plugin;
    private final File dataFile;
    private final Map<String, ChestData> byKey = new HashMap<>();
    private List<String> scanKeys = List.of();
    private boolean scanKeysDirty = true;
    private static final int MAX_PARSED_KEYS = 16_384;
    private static final Map<String, BlockAddress> PARSED_KEYS = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BlockAddress> eldest) {
                    return size() > MAX_PARSED_KEYS;
                }
            });

    private record BlockAddress(String world, int x, int y, int z) {
        Block block() {
            org.bukkit.World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : loaded.getBlockAt(x, y, z);
        }
    }

    public VirtualStore(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "chests.yml");
    }

    /** 坐标键：world:x:y:z。方块与位置复用同一构造，保证键一致。 */
    public static String keyOf(Block block) {
        return keyOf(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
    }

    public static String keyOf(Location loc) {
        return keyOf(loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private static String keyOf(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /**
     * 坐标键 -> 方块。世界未加载或键格式非法返回 null。
     * 从右侧拆出 z/y/x，剩余为世界名（容忍世界名本身含冒号）。
     */
    public static Block blockOf(String key) {
        BlockAddress cached = PARSED_KEYS.get(key);
        if (cached != null) return cached.block();
        BlockAddress parsed = parseKey(key);
        if (parsed == null) return null;
        PARSED_KEYS.put(key, parsed);
        return parsed.block();
    }

    private static BlockAddress parseKey(String key) {
        int p3 = key.lastIndexOf(':');
        if (p3 < 0) return null;
        int p2 = key.lastIndexOf(':', p3 - 1);
        if (p2 < 0) return null;
        int p1 = key.lastIndexOf(':', p2 - 1);
        if (p1 < 0) return null;
        try {
            return new BlockAddress(key.substring(0, p1),
                    Integer.parseInt(key.substring(p1 + 1, p2)),
                    Integer.parseInt(key.substring(p2 + 1, p3)),
                    Integer.parseInt(key.substring(p3 + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 存取 API（主线程调用）----

    public ChestData get(String key) {
        return byKey.get(key);
    }

    public boolean has(String key) {
        return byKey.containsKey(key);
    }

    /** 放置扩容箱时登记。已存在则返回旧数据（不覆盖，避免误清空）。 */
    public ChestData create(String key, int pages) {
        ChestData existing = byKey.get(key);
        if (existing != null) return existing;
        ChestData data = new ChestData(pages);
        byKey.put(key, data);
        scanKeysDirty = true;
        return data;
    }

    /** 遍历所有已登记箱子（供搬运任务扫描）。 */
    public java.util.Set<Map.Entry<String, ChestData>> entries() {
        return byKey.entrySet();
    }

    /** Stable scan list rebuilt only when chests are added or removed. */
    public List<String> scanKeys() {
        if (scanKeysDirty) {
            scanKeys = List.copyOf(new ArrayList<>(byKey.keySet()));
            scanKeysDirty = false;
        }
        return scanKeys;
    }

    /** 破坏箱子时移除并返回其数据（供上层把内容掉落）。 */
    public ChestData remove(String key) {
        ChestData removed = byKey.remove(key);
        if (removed != null) {
            scanKeysDirty = true;
            PARSED_KEYS.remove(key);
        }
        return removed;
    }

    public int size() {
        return byKey.size();
    }

    // ---- 持久化 ----

    /** 启动时同步加载（数据量通常不大，启动阶段可接受）。 */
    public void load() {
        byKey.clear();
        scanKeysDirty = true;
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("chests");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                byKey.put(key, ChestData.readFrom(sec));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "跳过损坏的箱子数据: " + key, e);
            }
        }
        plugin.getLogger().info("已加载 " + byKey.size() + " 个扩容箱子的存储。");
    }

    /**
     * 异步保存：主线程序列化成 YAML 文本 -> 异步写文件。
     * 序列化用 ChestData.snapshotContents() 里的深拷贝，写文件期间主线程改动互不影响。
     */
    public void saveAsync() {
        String text = serializeOnMainThread();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeText(text));
    }

    /** 同步保存：用于插件关闭时（此时不能再排异步任务）。 */
    public void saveSync() {
        writeText(serializeOnMainThread());
    }

    private String serializeOnMainThread() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("chests");
        for (Map.Entry<String, ChestData> e : byKey.entrySet()) {
            e.getValue().writeTo(root.createSection(e.getKey()));
        }
        return yaml.saveToString();
    }

    private synchronized void writeText(String text) {
        try {
            File dir = dataFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.nio.file.Files.writeString(dataFile.toPath(), text);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "保存扩容箱子数据失败", e);
        }
    }
}