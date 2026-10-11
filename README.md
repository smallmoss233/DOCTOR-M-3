# DOCTOR M-3

> 新一代的《Doctor Who》主题模组——全新的 DOCTOR M，登陆 Minecraft 26.3。

---

## 关于

**DOCTOR M-3**（简称 **DM3**）试图把《神秘博士》的核心体验带进 Minecraft。

前作 **DM2** 是 AIT 模组的附属扩展。DM3 是一次彻底重启——从附属模组转为独立模组，并基于 DM2 的资产做重新设计与扩展。

---

## 功能

### 🚪 TARDIS 门与传送

- **外门 / 内门双方块** —— 共享基类 `AbstractTardisDoorBlock`，各自绑定 TARDIS 的内外端
- **走过去即传送** —— 门开着时踏入即触发，关门状态下无反应
- **落点自洽** —— 进门落内门正面侧、面朝房间；出门落外门正面侧、面朝外界
- **开关门联动** —— 无论点内门还是外门，两侧状态同步切换
- **传送防抖** —— 20 tick guard，避免落点边缘反复穿越

### 🔑 TARDIS 状态与备用门

- **`TardisData`** —— 单台 TARDIS 的状态（UUID、owner、内外门位置 / 朝向、备用门列表）
- **`TardisRegistryData`** —— 所有 TARDIS 的持久化清单（SavedData）
- **`TardisManager`** —— 静态 API：查询、创建、穿门、开关门、删除
- **真出入口唯一** —— `TardisData.interiorPos` 是唯一"真门"
- **玩家手动放置的内门** —— 在 TARDIS 维度内自动登记为备用门，不响应右键 / 传送
- **真门被破坏** —— 最早登记的备用门自动升格为真门
- **无备用门时** —— 下一次从外门进入会自动重建默认内门（自愈）
- **非法位置放置** —— 非 TARDIS 维度里放的内门会立刻被拆掉并提示

### 🎨 TARDIS 生成与内容

- **`TardisSpawnerItem`** —— 一键生成整台 TARDIS（UUID + 维度 + 内门 + 外门）
- **程序生成控制室（临时版）** —— 矩形房间 + 占位控制台，用于测试进出
- **外门不生成物品**（`@NoItem`）—— 保证 TARDIS 只能通过 spawner 或命令创建
- **内门生成物品** —— 玩家可在 TARDIS 内手动放置（自动进备用门列表）

### 🎮 命令

| 命令 | 说明 |
|---|---|
| `/doctor_m tardis create` | 在玩家前方生成整台 TARDIS |
| `/doctor_m tardis tp [id]` | 传送至自己的 / 指定的 TARDIS |
| `/doctor_m tardis release <id>` | OP：删除指定 UUID 的 TARDIS |
| `/doctor_m tardis info [id]` | 查看 TARDIS 详情 |
| `/doctor_m tardis list` | 列出所有已注册的 TARDIS |
| `/doctor_m tardis door open\|close [id]` | 联动开关内外门 |
| `/doctor_m debug dims` | 列出所有动态维度的状态 |
| `/doctor_m debug save\|reload` | 批量保存 / 卸载（持久化测试用） |
| `/doctor_m debug purge` | 清空所有 TARDIS + 所有动态维度（慎用） |

---

## 技术栈

### 🛠️ MossLib（核心库）

**MossLib** —— 独立的 Fabric 技术支持库。任何人可自由使用、修改、再分发，
唯一要求是注明 **Small Moss** 为原作者。

DM3 依赖 MossLib 的以下模块：

- **`DynamicDimensionManager`** —— 运行时维度的创建 / 加载 / 卸载 / 持久化 API
- **`DimensionRegistryData`** —— 维度清单 SavedData
- **`PayloadRegistrar`** —— 声明式网络包注册
- **`AutoRegister`** —— 声明式内容注册（1.21.2+ 的 `setId` 兼容）
- **Bedrock Geometry / Animation 管线** —— 从 `.geo.json` / `.animation.json`
  到渲染顶点的完整链路：JSON 解析、坐标系与镜像定式、骨骼树变换、UV 处理、
  缓存与资源包重载
- **`/dmbedrock` 调试命令** —— 基岩模型的故障模式是静默的
  （`inflate` 被忽略、骨骼错连、动画名拼错都不报错，只是模型悄悄少一块），
  该命令把加载报告直接显示在聊天栏

### 🎯 DM3 内置

**动态 UUID 维度系统** —— 在 MossLib 的 `DynamicDimensionManager` 之上：

- 每 TARDIS 一个独立维度（`doctor_m:tardis/<uuid>`）
- 自定义维度类型 + 群系：全黑、无光照、不刷怪
- 虚空地形生成，为控制室预留空间
- 服务器重启后自动恢复，存档完整保留

**STP 无缝传送** —— 消除传送时的加载画面并提升传送速度：

- 预载目标维度区块，避免传送瞬间的世界加载卡顿
- 传送前后协同，玩家不会看到"卡住一帧再出现"的断层

**基岩模型的集成** —— 在 MossLib 的基岩管线之上实现 TARDIS 特有的胶水层：

- **外观系统** —— `TardisAppearance` / `TardisAsset.Bedrock`：每个外观独立配置
  `geometry` / `animation` / `texture` / `open` / `close` / `offset` / `scale`，
  JSON 驱动、多外观共存、开关动画可选（留空退化为静态模型）
- **门动画路由** —— 按方块 `FACING` 旋转模型，按 `open` 状态选播
  `openAnimation` / `closeAnimation`，`loop=false` 时自动 clamp 到时长
- **与 Blockbench 的旋转一致性** —— 三轴旋转薄片的还原靠两条配套的变换：

    1. **解析时的反变换** —— Blockbench 从 `.bbmodel` 导出 JSON 时，对 cube 的旋转
       做了 `(x, y, z) → (-x, -y, z)` 的坐标约定变换（逐 cube 对照工程文件与导出
       JSON 验证一致，自逆）。解析阶段再做一次同样的变换，把 JSON 值还原为
       Blockbench 内部值。
    2. **渲染时的组合定序** —— 按 `XYZ` 顺序（矩阵 `Rz·Ry·Rx`）将还原后的三个
       分量组合成四元数。

  单轴 cube 的 x/y 分量为零，对第一层变换不敏感，因此故障表现曾是
  "**只有三轴旋转的薄片错位**"。相关代码：`BedrockAxes#applyCube`（反变换）、
  `BedrockRenderMath#cubeOrder`（定序）。**改动其一必崩，两边必须同步。**

---

## 依赖

| 组件 | 版本 |
|------|------|
| **Minecraft** | 26.3 |
| **Fabric Loader** | 0.19.5+ |
| **Fabric API** | 0.160.5+26.3 |
| **Java** | 25+ |

> 从 26.1 起 Minecraft 不再混淆，Loom 默认使用 Mojang 官方映射（Mojmap），
> 无需在 `build.gradle` 中声明 `mappings`。

---

## 开发状态

**Pre-Alpha** —— 动态维度 + TARDIS 门 / 传送 / 状态管理 + 基岩版模型渲染与骨骼
动画已跑通，控制室仍是程序生成临时版，等待数据包结构替换。

---

## 许可证

DM3 主体采用 **MIT** 协议。

`mosslib` 包内的代码采用**署名即可**的开放条款：可使用、修改、再分发，
唯一要求是注明 **Small Moss** 为原作者。详见上一节。

---

## 致谢

- BBC 与《神秘博士》的所有创作者
- Fabric 社区
- Blockbench 与基岩版模型生态
- 所有贡献者（欢迎 PR）