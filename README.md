<div align="center">

<img src="src/main/resources/assets/whotickstoolong/icon.png" width="160" alt="Who Ticks Too Long">

# Who Ticks Too Long

**谁\*\*Tick这么久？！**

服务端区块 tick 性能分析工具

</div>

---

回答一个问题：**到底是哪个区块在拖慢服务器，它内部又在做什么。**

从区块排行一路下钻到具体实体、具体方块实体、具体方法调用。空闲时不消耗性能，任何时候都不写入存档。

## 特性

- **区块热度排行** —— 常驻运行，按占用 tick 时间排序。热路径开销实测低于服务器时间的 0.001%
- **对象明细** —— 指定区块内每个实体、方块实体、计划刻的精确耗时，含平均值与单次最差值
- **方法级采样** —— 区块内的热点方法及其代表调用栈
- **自动抓取** —— 服务器变卡且存在明确元凶时自动记录现场，报告写入磁盘
- **自身开销可查** —— 采样线程 CPU 与热路径成本随时可查，不必凭信任

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.19.3 或更高 |
| Fabric API | 必需 |
| Java | 25 |

## 安装

将 `whotickstoolong-<版本>.jar` 与 Fabric API 一并放入服务端的 `mods` 目录。

全部逻辑运行在服务端，玩家客户端无需安装。单人存档的内置服务端同样可用。

## 快速开始

指令需要 OP 权限等级 2，与 `/gamemode` 同级。

开启区块热度监控：

```
/wttl chunk enable
```

等待数秒让样本积累，查看排行：

```
/wttl top
```

```
-- Hottest chunks - 1m window --
  21,980 samples, 58.5% of wall time spent ticking chunks
  1.  20.4%  [0, 0] @ 0,0 overworld  mostly entity
  2.  16.0%  [2, 1] @ 32,16 overworld  mostly entity
  3.   8.4%  [0, 1] @ 0,16 overworld  mostly entity
```

对嫌疑区块深入检查 30 秒，同时采样方法：

```
/wttl object enable 0 0 30 methods
```

```
-- Chunk [0, 0] @ 0,0 overworld --
  4.168 ms per tick over 361 ticks - 34,023 object ticks in 18.0s
  by type:
    1.  92.3%  minecraft:villager              3.846 ms/tick  avg   54.2 us  worst 6034.6 us  [entity]
    2.   6.7%  minecraft:armor_stand           0.279 ms/tick  avg   14.0 us  worst  418.2 us  [entity]
    3.   0.8%  minecraft:hopper                0.034 ms/tick  avg   13.8 us  worst  146.5 us  [block entity]
```

让它在无人值守时自己抓现场：

```
/wttl auto enable
```

## 文档

- [指令指南](docs/commands.md) —— 全部指令、参数与输出示例
- [工作原理](docs/design.md) —— 设计、开销数据与已知边界

## 许可证

[MIT](LICENSE)
