package com.redstone.chestcapacity;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 放好的箱子方块没有原版 tooltip（原版限制），用一个 TextDisplay 实体挂在方块上方，
 * 显示“已用/总容量”。本类是这类悬浮文字的唯一出入口。
 *
 * 以“配对(ChestPairing)”为刷新单位，而非单个方块：
 *   · 单箱   -> 悬浮字挂在自身中心上方，内容 = 自身占用。
 *   · 双联   -> 只挂一个，锚在两半中点上方，内容 = 两半合计（需求2: merged_one）。
 *   归属键统一用规范首块(primary，双联=左半)的坐标键，写入 TextDisplay 的 PDC(keys.holo)。
 *
 * 实体唯一性 + 幂等（修复偶发残留/重复的根因）：
 *   每个配对对每种展示最多对应一个 TextDisplay（容量 / 名字各一个）。syncBlock 对双联两半
 *   分别调用会解析出同一配对、同一锚点、同一归属键，findNear 命中同一实体后自愈只留一个，
 *   所以搬运 tick 按单段遍历重复调用也安全。
 *
 * 内容权威来自 VirtualStore（按段聚合），不依赖 GUI 传入，避免过期快照。
 * 全部操作在主线程执行（实体 API 要求）。
 */
public final class HologramManager {

    private static final double SEARCH_RADIUS = 1.6;  // 覆盖双联中点到两半上方的实体
    private static final double NAME_VERTICAL_GAP = 0.28; // 名字实体相对容量悬浮字的额外高度

    private final PluginConfig config;
    private final Keys keys;
    private final VirtualStore store;

    public HologramManager(PluginConfig config, Keys keys, VirtualStore store) {
        this.config = config;
        this.keys = keys;
        this.store = store;
    }

    /**
     * 以某一半方块为线索，刷新其所在配对的悬浮字（单/双联自适应，合并显示，幂等）。
     * 内容从 VirtualStore 按段聚合。非扩容箱或全局关闭时清干净后返回。
     */
    public void syncBlock(Block anyHalf) {
        ChestPairing pairing = ChestPairing.resolve(anyHalf);
        if (pairing == null) return;                 // 已不是箱子

        Agg agg = aggregate(pairing);
        Location anchor = pairing.hologramAnchor(config.hologramYOffset);
        Location nameAnchor = anchor.clone().add(0.0, NAME_VERTICAL_GAP, 0.0);
        Set<String> keySet = keysOf(pairing);
        World world = anyHalf.getWorld();

        // 名字悬浮字严格从属于容量悬浮字：主开关/容量开关关闭时两种实体都清除。
        if (!config.hologramEnabled || agg.segments == 0 || !agg.shown) {
            clearDisplays(anchor, keySet, world, keys.holo);
            clearDisplays(nameAnchor, keySet, world, keys.holoName);
            return;
        }
        String ownerKey = VirtualStore.keyOf(pairing.primary());
        TextDisplay capacity = ensureSingle(anchor, keySet, ownerKey, world, keys.holo);
        if (capacity != null) capacity.text(config.hologramComponent(agg.used, agg.capacity, agg.pages));

        // 名字是独立实体，位置在容量悬浮字上方；无名字或名字开关关闭时只清名字实体。
        if (!agg.nameShown || agg.customName == null) {
            clearDisplays(nameAnchor, keySet, world, keys.holoName);
            return;
        }
        TextDisplay name = ensureSingle(nameAnchor, keySet, ownerKey, world, keys.holoName);
        if (name != null) name.text(PluginConfig.text(agg.customName));
    }

    /** 转发：GUI 只握有 view 时用它刷新（取任一段方块解析配对）。 */
    public void syncFor(ChestView view) {
        for (String key : view.blockKeys()) {
            Block block = VirtualStore.blockOf(key);
            if (block != null) { syncBlock(block); return; }
        }
    }

    /** 清除某方块所在配对的全部悬浮字（拆除/爆炸时调用，含历史残留）。 */
    public void clearForBlock(Block anyHalf) {
        ChestPairing pairing = ChestPairing.resolve(anyHalf);
        Location anchor = (pairing != null)
                ? pairing.hologramAnchor(config.hologramYOffset)
                : anyHalf.getLocation().add(0.5, config.hologramYOffset, 0.5);
        Location nameAnchor = anchor.clone().add(0.0, NAME_VERTICAL_GAP, 0.0);
        Set<String> keySet = (pairing != null) ? keysOf(pairing)
                : Set.of(VirtualStore.keyOf(anyHalf));
        clearDisplays(anchor, keySet, anyHalf.getWorld(), keys.holo);
        clearDisplays(nameAnchor, keySet, anyHalf.getWorld(), keys.holoName);
    }

    // ---- 聚合 ----

    /** 一个配对聚合出的展示数据。segments=命中 store 的扩容段数(0 表示非扩容箱)。 */
    private static final class Agg {
        int segments, used, capacity, pages;
        boolean shown;       // 任一段开启即显示
        boolean nameShown;   // 任一段开启即显示（ChestView 切换时会统一两半）
        String customName;   // 规范首段(LEFT)的名字，双联统一入口保持稳定
    }

    private Agg aggregate(ChestPairing pairing) {
        Agg a = new Agg();
        for (Block b : pairing.blocks()) {
            ChestData d = store.get(VirtualStore.keyOf(b));
            if (d == null) continue;                 // 该半不是扩容箱, 跳过
            if (a.segments == 0) a.customName = d.customName();
            a.segments++;
            a.used += d.usedStacks();
            a.capacity += d.capacity();
            a.pages += d.pages();
            if (d.isHologramShown()) a.shown = true;
            if (d.isNameShown()) a.nameShown = true;
        }
        return a;
    }

    private Set<String> keysOf(ChestPairing pairing) {
        Set<String> set = new HashSet<>();
        for (Block b : pairing.blocks()) set.add(VirtualStore.keyOf(b));
        return set;
    }

    // ---- 实体维护 ----

    /**
     * 保证锚点处恰有一个指定类型、归属本配对的悬浮字并返回它：
     *   无 -> 在 anchor 新建；有多个 -> 保留第一个(校正到 anchor)、删除其余(自愈)。
     * 区块未加载返回 null。
     */
    private TextDisplay ensureSingle(Location anchor, Set<String> acceptKeys,
                                     String ownerKey, World world, NamespacedKey typeKey) {
        if (!isChunkLoaded(anchor)) return null;
        List<TextDisplay> found = findNear(anchor, acceptKeys, world, typeKey);
        if (found.isEmpty()) return spawn(anchor, ownerKey, world, typeKey);
        TextDisplay keep = found.get(0);
        for (int i = 1; i < found.size(); i++) found.get(i).remove();
        keep.teleport(anchor);                       // 双联合并后锚点可能移到中点, 校正之
        keep.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, ownerKey);
        return keep;
    }

    private TextDisplay spawn(Location anchor, String ownerKey, World world, NamespacedKey typeKey) {
        return world.spawn(anchor, TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);   // 始终面向玩家
            td.setPersistent(true);
            td.setDefaultBackground(false);
            td.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
            td.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, ownerKey);
        });
    }

    /** 清除锚点附近、指定类型且归属键 ∈ acceptKeys 的全部实体。 */
    private void clearDisplays(Location anchor, Set<String> acceptKeys, World world, NamespacedKey typeKey) {
        for (TextDisplay td : findNear(anchor, acceptKeys, world, typeKey)) td.remove();
    }

    /** 锚点附近、指定类型且归属键 ∈ acceptKeys 的全部悬浮字。区块未加载返回空列表。 */
    private List<TextDisplay> findNear(Location anchor, Set<String> acceptKeys,
                                       World world, NamespacedKey typeKey) {
        List<TextDisplay> out = new ArrayList<>();
        if (!isChunkLoaded(anchor)) return out;
        for (TextDisplay td : world.getNearbyEntitiesByType(
                TextDisplay.class, anchor, SEARCH_RADIUS)) {
            String owned = td.getPersistentDataContainer()
                    .get(typeKey, PersistentDataType.STRING);
            if (owned != null && acceptKeys.contains(owned)) out.add(td);
        }
        return out;
    }

    private boolean isChunkLoaded(Location loc) {
        return loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }
}