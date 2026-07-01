package com.redstone.chestcapacity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
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

    // 正在被 GUI 查看/编辑的箱子的引用计数（key -> 打开中的界面数）。
    // 存在意义：GUI 回写是"整片快照覆盖"，与 TransferService 的实时搬运是两个写者，
    // 若在 GUI 打开期间搬运继续写入 slots，关闭时的旧快照会覆盖掉这些并发搬入的物品。
    // 因此打开期间对该箱子暂停搬运，让快照保持权威。计数支持翻页(先开新再关旧)与多人查看。
    private final Map<String, Integer> viewers = new HashMap<>();

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
        int p3 = key.lastIndexOf(':');
        if (p3 < 0) return null;
        int p2 = key.lastIndexOf(':', p3 - 1);
        if (p2 < 0) return null;
        int p1 = key.lastIndexOf(':', p2 - 1);
        if (p1 < 0) return null;
        try {
            int x = Integer.parseInt(key.substring(p1 + 1, p2));
            int y = Integer.parseInt(key.substring(p2 + 1, p3));
            int z = Integer.parseInt(key.substring(p3 + 1));
            org.bukkit.World world = Bukkit.getWorld(key.substring(0, p1));
            if (world == null) return null;
            return world.getBlockAt(x, y, z);
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

    // ---- GUI 会话锁：打开期间暂停该箱子的搬运，避免快照回写吞掉并发搬入的物品 ----

    /** GUI 打开时登记一个查看者。翻页时"先开新页(+1)再关旧页(-1)"，计数不会中途归零。 */
    public void beginView(String key) {
        viewers.merge(key, 1, Integer::sum);
    }

    /** GUI 关闭/翻页离开时注销一个查看者，归零则移除键。 */
    public void endView(String key) {
        Integer n = viewers.get(key);
        if (n == null) return;
        if (n <= 1) viewers.remove(key);
        else viewers.put(key, n - 1);
    }

    /** 该箱子当前是否正被 GUI 查看（搬运应跳过它）。 */
    public boolean isViewed(String key) {
        return viewers.containsKey(key);
    }

    /** 放置扩容箱时登记。已存在则返回旧数据（不覆盖，避免误清空）。 */
    public ChestData create(String key, int pages) {
        ChestData existing = byKey.get(key);
        if (existing != null) return existing;
        ChestData data = new ChestData(pages);
        byKey.put(key, data);
        return data;
    }

    /** 遍历所有已登记箱子（供搬运任务扫描）。 */
    public java.util.Set<Map.Entry<String, ChestData>> entries() {
        return byKey.entrySet();
    }

    /** 破坏箱子时移除并返回其数据（供上层把内容掉落）。 */
    public ChestData remove(String key) {
        return byKey.remove(key);
    }

    public int size() {
        return byKey.size();
    }

    // ---- 持久化 ----

    /** 启动时同步加载（数据量通常不大，启动阶段可接受）。 */
    public void load() {
        byKey.clear();
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