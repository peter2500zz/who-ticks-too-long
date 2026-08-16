# 指令指南

根指令 `/wttl`。全部子指令需要 OP 权限等级 2，与 `/gamemode` 同级。

## 总览

| 指令 | 作用 |
| --- | --- |
| `/wttl status` | 监控状态与本模组自身开销 |
| `/wttl chunk enable` | 开启区块热度监控 |
| `/wttl chunk disable` | 关闭区块热度监控并释放内存 |
| `/wttl top [数量] [窗口] [页码]` | 最耗时区块排行 |
| `/wttl object enable <x> <z> [时长] [维度] [methods]` | 深入检查指定区块 |
| `/wttl object disable` | 立即结束检查 |
| `/wttl object report [数量]` | 区块内对象耗时明细 |
| `/wttl object methods [数量]` | 区块内热点方法与调用栈 |
| `/wttl object save` | 将当前报告写入磁盘 |
| `/wttl auto enable [毫秒] [百分比]` | 开启自动抓取 |
| `/wttl auto disable` | 关闭自动抓取 |
| `/wttl auto list` | 列出自动抓取记录 |
| `/wttl auto show <序号>` | 查看某条记录 |

## 时长语法

窗口与时长参数照搬原版 `/time` 的写法：数字加单位后缀，**裸数字按 tick 计**。

| 单位 | 含义 | 示例 |
| --- | --- | --- |
| 无 | tick | `200` 等于 10 秒 |
| `t` | tick | `200t` |
| `s` | 秒 | `30s` |
| `m` | 分钟 | `5m` |
| `h` | 小时 | `1h` |

在原版的 `t` 与 `s` 之上增加了 `m` 与 `h`。补全行为与原版一致：输入数字后才提示单位。

小数可用，`0.5m` 等于 30 秒。单位不区分大小写。

## 区块热度

常驻巡航层，回答「哪个区块最贵」。这是其余功能的入口，也是自动抓取的判断依据。

### /wttl chunk enable

开启后立刻开始采样。历史分两层保留，关闭时全部释放：

| 层 | 覆盖 | 精度 | 内存 |
| --- | --- | --- | --- |
| 原始样本 | 最近 5 分钟 | 逐样本精确 | 约 6 MB |
| 聚合桶 | 最近 6 小时 | 按 10 秒计数 | 通常约 1 MB |

窗口在 5 分钟以内由原始层回答，更长的由聚合层回答。排行本身就是计数比较，因此两层给出的名次一致；聚合层只损失亚秒分辨率，而没有任何查询用得上它。

```
Chunk heat monitoring on. Ranking becomes meaningful after a few seconds of samples.
```

### /wttl chunk disable

```
Chunk heat monitoring off. Sample buffer released.
```

### /wttl top

```
/wttl top [数量] [窗口] [页码]
```

数量默认 10，上限 50。窗口默认 `1m`。页码默认 1。

```
-- Hottest chunks - 30s window --
  21,980 samples, 58.5% of wall time spent ticking chunks
  1.  20.4%  [0, 0] @ 0,0 overworld  mostly entity
  2.  16.0%  [2, 1] @ 32,16 overworld  mostly entity
  3.   8.4%  [0, 1] @ 0,16 overworld  mostly entity
```

- 第一行的百分比是该区块占**区块 tick 时间**的比例，不是占整个 tick 的比例
- `[0, 0]` 是区块坐标，`@ 0,0` 是该区块的起始方块坐标，可直接用于传送
- 末尾是该区块最主要的工作类型，取值为 `entity`、`block entity`、`random tick`、`scheduled block`、`scheduled fluid`

请求的窗口长于采样环保留的历史时不会报错，而是返回现有全部数据并说明：

```
-- Hottest chunks - 1h window --
  4,974 samples, 33.9% of wall time spent ticking chunks
  Only 39.0s of samples are retained, so that is what this covers.
```

## 深入检查

对单个区块开启精确插桩，得到每个对象的真实耗时。同一时刻只允许一个检查在跑。

### /wttl object enable

```
/wttl object enable <区块x> <区块z>
/wttl object enable <区块x> <区块z> until-stopped [methods]
/wttl object enable <区块x> <区块z> <秒数> [methods]
/wttl object enable <区块x> <区块z> <秒数> <维度> [methods]
```

时长默认 30 秒，上限 600 秒。维度默认为指令执行者所在维度。末尾追加 `methods` 同时开启方法级采样。

时长到点后自动结束并保留结果，避免忘记关闭而长期付出插桩成本。

```
Inspecting chunk [0, 0] (blocks 0,0 to 15,15) in minecraft:overworld for 15s. Read it with /wttl object report.
  Sampling methods with execution (safepoint biased). Read them with /wttl object methods.
```

### /wttl object disable

提前结束，已收集的数据保留。

### /wttl object report

```
/wttl object report [数量]
```

```
-- Chunk [0, 0] @ 0,0 overworld --
  4.168 ms per tick over 361 ticks - 34,023 object ticks in 18.0s
  by type:
    1.  92.3%  minecraft:villager              3.846 ms/tick  avg   54.2 us  worst 6034.6 us  [entity]
    2.   6.7%  minecraft:armor_stand           0.279 ms/tick  avg   14.0 us  worst  418.2 us  [entity]
    3.   0.8%  minecraft:hopper                0.034 ms/tick  avg   13.8 us  worst  146.5 us  [block entity]
  worst individual objects:
    1.   1.4%  minecraft:villager           now at [7, 150, 6]  300 ticks
    2.   1.4%  minecraft:villager           now at [12, 150, 10]  300 ticks
    3.   1.4%  minecraft:villager           now at [9, 101, 15]  207 ticks
```

`worst` 列是**单次最贵的一 tick**，用于发现被平均值掩盖的偶发卡顿。上例中一次村民 tick 花了 6 毫秒。

可移动对象按实体 id 归并，坐标显示为最后一次采集到的位置，因此标注 `now at`。固定对象按坐标归并。

### /wttl object methods

```
/wttl object methods [数量]
```

需要检查时带了 `methods`，否则没有数据。

```
-- Methods inside the inspected chunk --
  execution (safepoint biased) - 870 of 6,132 server-thread samples fell inside the chunk (14.2%)
  This sampler lands on safepoints, so treat hot spots as indicative, not exact.
   1.   8.9%  BlockCollisions.computeNext  during minecraft:villager
        via AbstractIterator.tryToComputeNext <- AbstractIterator.hasNext <- ImmutableCollection$Builder.addAll <- ImmutableList$Builder.addAll
   2.   6.6%  Long2ObjectOpenHashMap.get  during minecraft:villager
        via SectionStorage.get <- SectionStorage.getOrLoad <- PoiManager.lambda$getInChunk$0 <- 0x000000003203d088.apply
   3.   6.0%  Sink$ChainedReference.cancellationRequested  during minecraft:villager
        via Sink$ChainedInt.cancellationRequested <- IntPipeline.forEachWithCancel <- AbstractPipeline.copyIntoWithCancel
   4.   5.4%  ServerChunkCache.getChunk  during minecraft:villager
        via Level.getChunk <- LevelReader.getChunk <- Level.getChunk <- Level.getBlockState
   5.   5.3%  Brain.startEachNonRunningBehavior  during minecraft:villager
        via Brain.tick <- Villager.customServerAiStep <- Mob.serverAiStep <- LivingEntity.aiStep
```

- 百分比是该方法占落入本区块样本的比例
- `during` 指出采样时正在 tick 的对象类型
- `via` 是最常见的调用路径，最内层在前

Linux 服务器会使用无偏采样器，不会出现 safepoint 提示行。详见[工作原理](design.md#方法级采样)。

### /wttl object save

把当前结果写入磁盘，内容比聊天栏完整。

```
Writing report to whotickstoolong/2026-08-16_12-00-04_manual_dim0_chunk_0_0.txt
```

## 自动抓取

无人值守时自动记录卡顿现场。需要区块热度监控处于开启状态。

### /wttl auto enable

```
/wttl auto enable [毫秒] [百分比]
```

默认阈值 40 毫秒与 30%。

```
Automatic drill-down on: captures when the server averages over 40 ms/tick and one chunk holds over 30% of chunk tick time.
```

两个条件**同时满足**才会触发：服务器近 100 tick 平均耗时超过毫秒阈值，且 10 秒窗口内某个区块占区块 tick 时间超过百分比阈值。命中后对该区块自动检查 30 秒并开启方法采样，结果写入磁盘。同一区块 5 分钟内不重复触发。

阈值不会持久化，服务器重启后回到默认值。

### /wttl auto disable

### /wttl auto list

内存中保留最近 8 条记录，磁盘上的报告文件不受此限制。

```
-- Automatic captures (1) --
  1. 2026-08-16 12:04:23  [2, 1] @ 32,16 overworld  31.7 ms/tick server, 19% chunk
      2026-08-16_12-04-56_auto_dim0_chunk_2_1.txt
```

### /wttl auto show

```
/wttl auto show <序号>
```

序号取自 `/wttl auto list`。输出为触发信息加上与 `/wttl object report` 和 `/wttl object methods` 相同的内容。

```
Captured automatically at 2026-08-16 12:04:23, server averaging 31.7 ms/tick
```

## 状态

### /wttl status

```
-- Who Ticks Too Long --
  server: 31.39 ms/tick averaged over the last 100 ticks
  chunk heat: on - 1000 Hz requested, 915 Hz achieved
  samples: 20,134 taken, 16 discarded (0.08%)
  sample buffer: 6,152 KiB
  sampler thread: 0.27 s CPU - 1.2% of one core
  hot path: 250,511 objects instrumented - about 50.1 us total, <0.001% of server wall time (estimate)
  deep inspection: idle
  automatic drill-down: off
  reports: whotickstoolong/ in the game directory
```

- `server` 是设置自动抓取阈值时的参考值
- `achieved` 低于 `requested` 属正常，取决于平台定时器精度，报告基于样本占比而非速率
- `discarded` 是读取上下文时服务器线程正好切换对象而丢弃的样本
- `sampler thread` 与 `hot path` 是本模组自身的开销

关闭时：

```
-- Who Ticks Too Long --
  server: 29.91 ms/tick averaged over the last 100 ticks
  chunk heat: off
  Nothing is instrumented and no memory is held.
  deep inspection: idle
  automatic drill-down: off
  reports: whotickstoolong/ in the game directory
```

## 报告文件

报告写入游戏目录下的 `whotickstoolong/`，与 `logs/` 和 `crash-reports/` 平级，纯文本不压缩。**任何存档目录都不会被写入。**

```
whotickstoolong/
├── 2026-08-16_12-00-04_manual_dim0_chunk_0_0.txt
└── 2026-08-16_12-04-56_auto_dim0_chunk_2_1.txt
```

文件名依次为时间戳、来源、维度编号、区块坐标。内容比聊天栏完整，包含区块的完整方块范围：

```
Who Ticks Too Long
Automatic capture at 2026-08-16 12:04:23 - server averaging 31.7 ms/tick, chunk held 19.0% of chunk tick time
------------------------------------------------------------------------------
Chunk [2, 1] in minecraft:overworld
  Blocks X 32..47, Z 16..31 - origin 32, 16
  ...
```

写入交由后台线程完成，磁盘阻塞不会变成 tick 阻塞。
