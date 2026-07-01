# ChestCapacity — 可扩容箱子插件（Paper 1.21）

[English](#english) | [中文](#中文)

---

## English

A Paper plugin that gives chests expandable storage capacity with redstone I/O support. Designed for redstone-heavy servers where hoppers work against large virtual inventories through an automatic water-level buffer.

### How It Works

```
Physical 27 slots = Redstone interface (vanilla hopper rules, plugin zero takeover)
Virtual storage    = Expanded capacity stored in plugin data file

The plugin runs a periodic task to keep physical slots at a target "water level":
  occupied > target  →  sink excess into virtual storage
  occupied < target  →  source items from virtual storage back to physical
  occupied = target  →  do nothing (hysteresis, avoid oscillation)
```

One parameter `keep-filled-slots` covers three use cases:
- `keep-filled-slots = 0`   → bottomless eater (hoppers keep feeding in)
- `keep-filled-slots = 27`  → endless supplier (hoppers keep pulling out)
- `0 < n < 27`              → bidirectional buffer
- `buffer-enabled = false`  → pure GUI mega-chest (no redstone automation)

Each page adds 45 slots of virtual storage. Max pages configurable.

### Features

- **Place-to-activate** — Drop the special chest item; it immediately gains capacity following config defaults. No commands needed for daily use.
- **Right-click GUI** — Open a paginated virtual warehouse (6 rows, 45 slots per page + navigation bar).
- **Redstone-ready** — Vanilla hoppers interact with the physical 27 slots; the plugin silently moves items in/out of the large virtual storage.
- **Hologram overlay** — TextDisplay above chest shows used/total capacity (blocks have no vanilla tooltip).
- **Item lore** — The held chest item shows pages/slots info via PDC-backed lore.
- **Graceful break** — Breaking drops an empty chest item with capacity NBT + virtual contents are dropped in a rate-limited stream (anti-lag).
- **YAML persistence** — Virtual inventories save/load asynchronously to `chests.yml`.

### Commands

| Command | Description |
|---|---|
| `/chestcap give <pages> [amount]` | Get an expandable chest item (OP) |
| `/chestcap get` | View capacity of held chest item |
| `/chestcap set <pages>` | Change pages of held chest item (OP) |

### Permissions

| Permission | Default | Description |
|---|---|---|
| `chestcapacity.command` | op | Use `/chestcap` command |
| `chestcapacity.use` | true | Open and use expandable chests |

With LuckPerms:
```
lp group admin permission set chestcapacity.command true
lp group default permission set chestcapacity.use true
```

### Configuration

All behavior is in `plugins/ChestCapacity/config.yml`:

```yaml
default-pages: 1          # default page count when placed
max-pages: 54             # hard cap

buffer-enabled: true      # enable water-level buffer
keep-filled-slots: 0      # target filled slots in physical 27 (0~27)

transfer-interval-ticks: 2
transfer-batch-per-chest: 5

save-interval-ticks: 1200 # async save every 60s

drop-capacity-item: true
break-drop-per-tick: 20   # rate-limit on break content drops

hologram:
  enabled: true
  y-offset: 1.1
  text: "&6Expanded Chest\n&7%used%&8/&7%slots% &8(&e%pct%%&8)"

gui:
  title: "&8Expanded Chest &7[%page%/%pages%]"
  prev-page: "&a« Prev"
  next-page: "&aNext »"
  page-indicator: "&ePage %page% / %pages%"
  filler: true

item:
  name: "&6Expanded Chest"
  lore:
    - "&7Capacity: &f%pages% pages &7(%slots% slots)"
```

### Building from Source

Requirements: JDK 21+

```bash
./gradlew build
# Output: build/libs/ChestCapacity-1.0.0.jar
```

---

## 中文

面向红石服的 Paper 插件，让箱子拥有可配置的扩容容量，物理 27 格作为红石接口，扩容内容走虚拟仓库，插件按"水位缓冲"自动搬运。

### 工作原理

```
物理 27 格 = 红石接口（原版漏斗机制，插件零接管）
虚拟存储    = 扩容出来的大容量，存插件数据文件

插件定时任务维持物理格处于一个"目标水位"：
  物理非空槽 > 目标  →  下沉：把多余货塞进虚拟存储
  物理非空槽 < 目标  →  补货：从虚拟存储抽货回物理格
  物理非空槽 = 目标  →  不动（滞回，避免来回震荡）
```

一个 `keep-filled-slots` 参数覆盖三种红石语义：
- `keep-filled-slots = 0`   → 尽量清空物理格 => 无限吃货箱
- `keep-filled-slots = 27`  → 尽量填满物理格 => 无限供货箱
- `0 < n < 27`              → 双向缓冲
- `buffer-enabled = false`  → 纯玩家 GUI 大仓库（不参与红石）

每页 45 格虚拟存储，最大页数可配置。

### 特性

- **放下即生效** — 拿起扩容箱物品放置，自动按配置扩容。不需要指令，日常不依赖指令。
- **右键大容量 GUI** — 6 行分页界面，每页 45 格 + 底部导航行，翻页自动保存。
- **红石友好** — 漏斗照常怼物理 27 格，插件后台自动在物理格与虚拟大仓库之间搬运。
- **悬浮文字** — 箱子方块上方 TextDisplay 显示已用/总格数（弥补原版方块无 tooltip 的限制）。
- **物品悬停** — 手持扩容箱物品时 lore 显示容量、页数、使用说明。
- **优雅破坏** — 挖掉箱子掉落带容量 NBT 的空箱（可回收），虚拟内容限速分批掉在原地（防卡服）。
- **YAML 持久化** — 虚拟仓库数据异步保存到 `chests.yml`。

### 指令

| 指令 | 说明 |
|---|---|
| `/chestcap give <页数> [数量]` | 发放扩容箱物品（需 OP） |
| `/chestcap get` | 查看手持扩容箱容量 |
| `/chestcap set <页数>` | 调整手持扩容箱容量（需 OP） |

### 权限

| 权限节点 | 默认 | 说明 |
|---|---|---|
| `chestcapacity.command` | op | 使用 /chestcap 指令 |
| `chestcapacity.use` | true | 打开和使用扩容箱子 |

LuckPerms 授权方式：
```
lp group admin permission set chestcapacity.command true
lp group default permission set chestcapacity.use true
```

### 配置

所有行为由 `plugins/ChestCapacity/config.yml` 控制：

```yaml
default-pages: 1          # 放置时的默认页数
max-pages: 54             # 允许的最大页数

buffer-enabled: true      # 启用水位缓冲搬运
keep-filled-slots: 0      # 物理 27 格保留的非空槽数 (0~27)

transfer-interval-ticks: 2
transfer-batch-per-chest: 5

save-interval-ticks: 1200 # 异步落盘间隔(60秒)

drop-capacity-item: true
break-drop-per-tick: 20   # 破坏时每 tick 最多掉落堆叠数

hologram:
  enabled: true
  y-offset: 1.1
  text: "&6大大大箱子\n&7%used%&8/&7%slots% &8(&e%pct%%&8)"

gui:
  title: "&8大大大箱子 &7[%page%/%pages%]"
  prev-page: "&a« 上一页"
  next-page: "&a下一页 »"
  page-indicator: "&e第 %page% / %pages% 页"
  filler: true

item:
  name: "&6大大大箱子"
  lore:
    - "&7容量: &f%pages% 页 &7(共 %slots% 格)"
```

### 从源码构建

需要 JDK 21+：

```bash
./gradlew build
# 输出: build/libs/ChestCapacity-1.0.0.jar
```