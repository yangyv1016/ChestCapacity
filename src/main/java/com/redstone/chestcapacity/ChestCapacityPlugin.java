package com.redstone.chestcapacity;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 插件主类：只做“组装 + 生命周期”，不含业务细节。
 *
 * 数据流总览：
 *   物品(身份,pages) --放置--> 方块PDC(权威 pages) + VirtualStore(唯一库存) + 悬浮文字
 *                                           |
 *                              StorageIoService 适配原版容器红石 I/O
 *                                           |
 *                 漏斗 / 漏斗矿车 / 投掷器 / 自动合成器 / 比较器共享同一 ChestView
 *
 * 物理箱只保留方块身份，不再承载隐藏缓冲物品。
 * 组装顺序遵循依赖方向：config/keys -> marker -> items/store/holograms -> 服务 -> 注册。
 */
public final class ChestCapacityPlugin extends JavaPlugin {

    private PluginConfig config;
    private Keys keys;
    private ChestMarker marker;
    private ChestItems items;
    private VirtualStore store;
    private ChestViewResolver resolver;
    private HologramManager holograms;
    private ComparatorService comparators;
    private ChestGui gui;
    private StorageIoService io;

    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadModules();

        store = new VirtualStore(this);
        store.load();

        resolver = new ChestViewResolver(config, marker, store);
        holograms = new HologramManager(config, keys, store);
        comparators = new ComparatorService(this, resolver);
        gui = new ChestGui(config, store, holograms, comparators);
        io = new StorageIoService(this, config, store, resolver, gui, holograms, comparators);

        getServer().getPluginManager().registerEvents(
                new ChestListeners(this, config, marker, items, store, resolver,
                        gui, holograms, comparators), this);
        getServer().getPluginManager().registerEvents(io, this);
        getServer().getPluginManager().registerEvents(comparators, this);
        io.start();

        ChestCommand command = new ChestCommand(config, items);
        getCommand("chestcap").setExecutor(command);
        getCommand("chestcap").setTabCompleter(command);

        long interval = config.saveIntervalTicks;
        saveTask = getServer().getScheduler().runTaskTimer(
                this, () -> store.saveAsync(), interval, interval);

        getLogger().info("ChestCapacity 已启用。默认容量 " + config.defaultPages
                + " 页, 红石I/O=" + (config.ioEnabled
                        ? "开(" + config.ioIntervalTicks + " tick/次, 每次 "
                        + config.ioItemsPerTransfer + " 件)" : "关") + "。");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) saveTask.cancel();
        if (io != null) io.stop();
        if (store != null) store.saveSync();
    }

    /** 读取/重载强类型配置与依赖它的基础件。 */
    public void reloadModules() {
        reloadConfig();
        this.config = new PluginConfig(getConfig());
        this.keys = new Keys(this);
        this.marker = new ChestMarker(keys);
        this.items = new ChestItems(config, marker);
    }

    public PluginConfig config() { return config; }
    public ChestItems items() { return items; }
    public VirtualStore store() { return store; }
}