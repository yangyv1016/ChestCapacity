package com.redstone.chestcapacity;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 插件主类：只做“组装 + 生命周期”，不含业务细节。
 *
 * 数据流总览：
 *   物品(身份,pages) --放置--> 方块PDC(权威 pages) + VirtualStore(内容) + 悬浮文字
 *        ^                          |
 *        |                    TransferService 按“水位缓冲”在 物理27格 <-> VirtualStore 间搬运
 *        +----破坏(掉带pages空箱)---+  同时限速掉落 VirtualStore 内容, 清悬浮文字
 *
 * 红石语义由全局 keep-filled-slots 一个参数统一决定, 方块不背模式。
 * 组装顺序遵循依赖方向：config/keys -> marker -> items/store/holograms -> 服务 -> 注册。
 */
public final class ChestCapacityPlugin extends JavaPlugin {

    private PluginConfig config;
    private Keys keys;
    private ChestMarker marker;
    private ChestItems items;
    private VirtualStore store;
    private HologramManager holograms;
    private ChestGui gui;
    private TransferService transfer;

    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadModules();

        store = new VirtualStore(this);
        store.load();

        holograms = new HologramManager(config, keys, store);
        gui = new ChestGui(config, store, holograms);
        transfer = new TransferService(this, config, store, holograms, gui);
        transfer.start();

        getServer().getPluginManager().registerEvents(
                new ChestListeners(this, config, marker, items, store, gui, holograms), this);

        ChestCommand command = new ChestCommand(config, items);
        getCommand("chestcap").setExecutor(command);
        getCommand("chestcap").setTabCompleter(command);

        long interval = config.saveIntervalTicks;
        saveTask = getServer().getScheduler().runTaskTimer(
                this, () -> store.saveAsync(), interval, interval);

        getLogger().info("ChestCapacity 已启用。默认容量 " + config.defaultPages
                + " 页, 水位缓冲=" + (config.bufferEnabled
                        ? "开(水位 " + config.keepFilledLow + "~" + config.keepFilledHigh + " 格)" : "关") + "。");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) saveTask.cancel();
        if (transfer != null) transfer.stop();
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