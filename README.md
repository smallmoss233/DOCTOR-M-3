# DOCTOR M-3

> 新一代的《Doctor Who》主题模组——全新的 DOCTOR M，登陆 Minecraft 26.3。

---

## 关于

**DOCTOR M3**（简称 **DM3**）试图把《神秘博士》的核心体验带进 Minecraft。

前作 **DM2**（DOCTOR M-2）曾作为 AIT 模组的附属扩展存在。DM3（DOCTOR M-3） 是一次彻底重启——**从附属模组转为独立模组**，并基于 DM-2 的资产做重新设计与扩展。

---

## 已实现功能

### 🌀 动态维度系统
- **每个 TARDIS 独立维度**——维度 ID 从 TARDIS 的 UUID 派生（`doctor_m:tardis/<uuid>`）
- **运行时创建 / 加载 / 卸载**——不依赖数据包预注册维度，服务器运行中动态生成
- **持久化清单**——服务器重启后自动恢复所有已创建的维度，玩家数据、方块、实体完整保留
- **彻底删除**——可选连同存档一起清除，不留死数据

### 🎮 调试命令
- `/doctor_m tardis create`——为当前玩家创建独立 TARDIS 维度
- `/doctor_m tardis tp [id]`——传送至自己的 / 指定的 TARDIS
- `/doctor_m tardis release`——删除自己的 TARDIS（含存档）
- `/doctor_m debug list`——列出所有动态维度的状态
- `/doctor_m debug create|delete <id>`——GM 手动管理任意维度
- `/doctor_m debug save|reload`——批量保存 / 卸载（用于持久化测试）

### 🌍 维度内容
- **自定义维度类型** `doctor_m:tardis`——全黑、无光照、有天花板、不刷怪、可睡觉
- **自定义群系** `doctor_m:tardis`——空生物生成表，从数据层面禁绝自然刷怪
- **虚空地形生成器**——为将来的控制台房间预留空间

### 🛠️ 基础设施（MossLib）
- **`DynamicDimensionManager`**——运行时维度的核心 API
- **`DimensionRegistryData`**——维度清单持久化
- **`PayloadRegistrar`**——声明式网络包注册
- **`AutoRegister`**——声明式内容注册（1.21.2+ 的 `setId` 兼容）
- **`CooldownTracker`**——通用冷却工具

---

## 计划实现功能

### 🚪 TARDIS
- **控制台房间**——结构方块或程序化生成的内饰
- **进门方块**——以方块作为 TARDIS 入口，不再依赖命令
- **飞行 / 时间旅行**——选择目标坐标与时间点，起飞降落
- **变色龙电路**——将 TARDIS 外观伪装成周围方块
- **多房间扩展**——图书馆、衣帽间、游泳池……

### 🪛 音速起子
- **多模式**——扫描 / 开门 / 破解 / 医疗 / 遥控
- **方块交互**——侦测方块类型、开启铁门、破解红石
- **实体交互**——读取生物信息、治愈、暂时眩晕

### 🧬 再生系统
- **死亡 = 再生**——触发再生动画 + 外貌随机化
- **有限次数**——可配置（默认 12 次）
- **物品保留**——重生后物品栏不丢失

### 👾 敌人
- Dalek / Cyberman / 哭泣天使 / Sontaran / Silurian……

### 🌍 世界
- Gallifrey / Skaro / 平行地球……

---

## 命名

- **DOCTOR M**——"M"的含义将在正式版揭晓
- **M3**——第三代设计，重启自更早的实验版本
- **DM3**——社区昵称

---

## 依赖

| 组件 | 版本 |
|------|------|
| **Minecraft** | 26.3 |
| **Fabric Loader** | 0.19.5+ |
| **Fabric API** | 0.160.5+26.3 |
| **Java** | 25+ |

> 从 26.1 起 Minecraft 不再混淆，Loom 默认使用 Mojang 官方映射（Mojmap），无需在 `build.gradle` 中声明 `mappings`。

---

## 开发状态

**Pre-Alpha**——核心框架已就位，动态维度系统验证通过。正在设计 TARDIS 内饰与玩法。

### 已完成里程碑

- ✅ 动态 UUID 维度（创建 / 加载 / 卸载 / 持久化）
- ✅ 维度清单 SavedData
- ✅ 命令系统（玩家向 + GM 调试）
- ✅ 自定义维度类型与群系
- ✅ 中英双语文案
- ✅ MossLib 工具库集成

### 下一步

- ⬜ TARDIS 内饰生成
- ⬜ 进门方块
- ⬜ TARDIS 飞行系统
- ⬜ 音速起子

---

## 许可证

MIT

---

## 致谢

- BBC 与《神秘博士》的所有创作者
- Fabric 社区
- 所有贡献者（欢迎 PR）