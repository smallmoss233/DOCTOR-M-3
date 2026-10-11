# DOCTOR M-3

> 新一代的《Doctor Who》主题模组——全新的 DOCTOR M，登陆 Minecraft 26.3。

---

## 关于

**DOCTOR M-3**（简称 **DM3**）试图把《神秘博士》的核心体验带进 Minecraft。

前作 **DM2** 是 AIT 模组的附属扩展。DM3 是一次彻底重启——从附属模组转为独立模组，并基于 DM2 的资产做重新设计与扩展。

---

## 已完成里程碑

- ✅ 动态 UUID 维度（创建 / 加载 / 卸载 / 持久化）
- ✅ 维度清单 SavedData
- ✅ 自定义维度类型与群系
- ✅ TARDIS 状态管理（`TardisData` + `TardisRegistryData`）
- ✅ 外门 / 内门方块 + 双端传送
- ✅ 开关门内外联动
- ✅ 备用门机制（真门被破坏自动升格）
- ✅ 自定义塔迪斯外部 / 内门样式
- ✅ TARDIS 生成物品
- ✅ 程序生成控制室（临时版）
- ✅ 命令系统（玩家向 + GM 调试）
- ✅ 中英双语文案
- ✅ MossLib 工具库集成
- ✅ 无缝传送技术集成
- ✅ 基岩版模型渲染管线（Bedrock Geometry / Animation）
- ✅ 内外门开关门动画
- ✅ **Blockbench 旋转一致性** —— 三轴旋转薄片的还原

---

## 已实现功能

### 🌀 动态维度系统

- **每个 TARDIS 独立维度** —— 维度 ID 从 TARDIS 的 UUID 派生（`doctor_m:tardis/<uuid>`）
- **运行时创建 / 加载 / 卸载** —— 不依赖数据包预注册维度，服务器运行中动态生成
- **持久化清单** —— 服务器重启后自动恢复所有已创建的维度，玩家数据、方块、实体完整保留
- **彻底删除** —— 连同存档一起清除，不留死数据

### 🚪 TARDIS 门与传送

- **外门 / 内门双方块** —— 共享基类 `AbstractTardisDoorBlock`，各自绑定 TARDIS 的内外端
- **走过去即传送**（`entityInside`）—— 门开着时碰撞触发，关门状态下无反应
- **落点自洽** —— 进门落内门正面侧、面朝房间；出门落外门正面侧、面朝外界
- **开关门联动** —— 无论点内门还是外门，两侧状态同步切换
- **传送防抖** —— 20 tick guard，避免落点边缘反复穿越

### 🔑 TARDIS 状态管理

- **`TardisData`** —— 单台 TARDIS 的状态（UUID、owner、内外门位置 / 朝向、备用门列表）
- **`TardisRegistryData`** —— 所有 TARDIS 的持久化清单（SavedData）
- **`TardisManager`** —— 静态 API：查询、创建、穿门、开关门、删除

### 🧩 备用门机制

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

### 🌍 维度内容

- **自定义维度类型** `doctor_m:tardis` —— 全黑、无光照、有天花板、不刷怪、可睡觉
- **自定义群系** `doctor_m:tardis` —— 空生物生成表，从数据层面禁绝自然刷怪
- **虚空地形生成器** —— 为控制室预留空间

### 🛠️ 基础设施（MossLib）

- **`DynamicDimensionManager`** —— 运行时维度的核心 API
- **`DimensionRegistryData`** —— 维度清单持久化
- **`PayloadRegistrar`** —— 声明式网络包注册
- **`AutoRegister`** —— 声明式内容注册（1.21.2+ 的 `setId` 兼容）

### 🦴 基岩版模型与动画系统

不再依赖原版方块模型烘焙，也不局限于 Java 版模型格式——DM3 内置了一套完整的
**Bedrock Geometry / Animation 渲染管线**，Blockbench 里怎么摆，游戏里就怎么渲。

#### 渲染管线

- **通用基岩几何渲染器**（`BedrockModelRenderer`）—— 接受任意 `BedrockGeometryModel`，不知道 TARDIS 是什么，可复用于任何模型
- **Bedrock 原生坐标** —— +X 东 / +Y 上 / +Z 南，与 Minecraft 世界坐标一致
- **完整骨骼树递归** —— 父骨骼 → 子骨骼，逐级累积变换矩阵
- **cube 独立 pivot + rotation** —— 每个 cube 可单独设定旋转中心与欧拉角
- **六面独立 UV** —— 支持每面独立矩形、正负 `uv_size`、`flipU` / `flipV`
- **镜像支持** —— `mirror: true` 语义正确还原

#### 与 Blockbench 的旋转一致性

三轴旋转的薄片（斜面挡板、控制面板三角等）能正确还原，靠的是两条**配套**的变换，缺一不可：

1. **解析时的反变换** —— Blockbench 从 `.bbmodel` 导出 JSON 时，对 cube 的旋转做了 `(x, y, z) → (-x, -y, z)` 的坐标约定变换（逐 cube 对照工程文件与导出 JSON 验证一致，自逆）。解析阶段再做一次同样的变换，把 JSON 值还原为 Blockbench 内部值。
2. **渲染时的组合定序** —— 按 `XYZ` 顺序（矩阵 `Rz·Ry·Rx`）将还原后的三个分量组合成四元数。

单轴 cube 的 x/y 分量为零，对第一层变换不敏感，因此故障表现曾是"**只有三轴旋转的薄片错位**"——这一现象的正确解释就在这里。

> 相关代码：`BedrockAxes#applyCube`（反变换）、`BedrockRenderMath#cubeOrder`（定序）。**改动其一必崩，两边必须同步。**

#### 数据结构与缓存

- **几何**：`Bone` / `Cube` / `FaceUv` 三层 record，纯数据、不可变
- **动画**：`Animation` / `BoneTracks` / `Keyframe` 三层 record，支持 `rotation` / `position` / `scale` 三通道
- **X 轴镜像** —— 用于修正"从内往外看"建模的模型：几何、旋转、UV 一次性镜像，渲染层保持干净
- **`BedrockCache`** —— Fabric Resource Reload 自动重解析；按 `Identifier` 索引；appearance 驱动加载

#### 解析器

- **标准 Blockbench 导出** —— `format_version: 1.12.0` 的几何与动画 JSON
- **亚像素 UV** —— 用 `float` 而非 `int`，支持 16×16 以上的精细纹理
- **负 `uv_size`** —— 折算为「原点左移 + 正尺寸 + flip 标记」
- **`hold_on_last_frame`** —— 正确识别为"停在最后一帧"而非"循环"
- **关键帧排序 + 线性插值** —— 三通道统一采样

#### 门动画集成

- **外观 ID 路由** —— 从 `TardisDoorRenderState` 取 `appearanceId`，查表得 `TardisAsset.Bedrock`
- **开关动画选择** —— `open` 状态决定播 `openAnimation` 还是 `closeAnimation`
- **时长 clamp** —— `loop=false` 时自动 `min(elapsed, length)`
- **朝向旋转** —— 按方块 `FACING` 旋转模型，`MODEL_FACING` 标注 Blockbench 里的正面
- **偏移 + 全局缩放** —— 外观 JSON 里的 `offset` / `scale` 运行时应用

#### 外观系统

- **JSON 驱动** —— 每个外观独立配置 `geometry` / `animation` / `texture` / `open` / `close` / `offset` / `scale`
- **多外观共存** —— 每个外观独立的几何 + 动画 + 纹理，运行时按 ID 加载
- **开关动画可选** —— `open` / `close` 字段可留空，退化为静态模型
- **Blockbench 原生兼容** —— 无需额外插件，导出即可用

#### 模型问题排查（`/dmbedrock`）

基岩模型的故障模式是**静默的**：`inflate` 被忽略、骨骼父子错连、动画名拼错——都不会报错，只是模型悄悄少一块、歪一点或者干脆不出现。因此内置了客户端调试命令：

- `/dmbedrock report` —— 查看已加载的几何 / 动画数，以及加载失败和解析有问题的资源
- `/dmbedrock check` —— 逐套外观尝试加载其引用的全部资源，列出缺失的几何 / 动画 / 动画名
- `/dmbedrock reload` —— 清空缓存，改了模型 JSON 后无需重启游戏

架构说明、坐标系定式与目视校验清单见
[`docs/BEDROCK.md`](docs/BEDROCK.md)。模型层的独立运行时验证见
[`tools/bedrock-verify`](tools/bedrock-verify)（84 项断言，不启动游戏即可运行）。

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

**Pre-Alpha** —— 动态维度 + TARDIS 门 / 传送 / 状态管理 + 基岩版模型渲染与骨骼动画已跑通，控制室仍是程序生成临时版，等待数据包结构替换。

---

## 许可证

MIT

---

## 致谢

- BBC 与《神秘博士》的所有创作者
- Fabric 社区
- Blockbench 与基岩版模型生态
- 所有贡献者（欢迎 PR）