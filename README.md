# DOCTOR M-3

> 新一代的《Doctor Who》主题模组——全新的 DOCTOR M，登陆 Minecraft 26.3。

---

## 关于

**DOCTOR M3**（简称 **DM3**）试图把《神秘博士》的核心体验带进 Minecraft。

前作 **DM2**（DOCTOR M-2）曾作为 AIT 模组的附属扩展存在。DM3（DOCTOR M-3） 是一次彻底重启——**从附属模组转为独立模组**，并基于 DM-2 的资产做重新设计与扩展。

---

### 已完成里程碑

- ✅ 动态 UUID 维度（创建 / 加载 / 卸载 / 持久化）
- ✅ 维度清单 SavedData
- ✅ 自定义维度类型与群系
- ✅ TARDIS 状态管理（`TardisData` + `TardisRegistryData`）
- ✅ 外门 / 内门方块 + 双端传送
- ✅ 开关门内外联动
- ✅ 备用门机制（真门被破坏自动升格）
- ✅ 自定义塔迪斯外部/内门样式
- ✅ TARDIS 生成物品
- ✅ 程序生成控制室（临时版）
- ✅ 命令系统（玩家向 + GM 调试）
- ✅ 中英双语文案
- ✅ MossLib 工具库集成
- ✅ 无缝传送技术集成
- ✅ **基岩版模型渲染管线（Bedrock Geometry / Animation）**
- ✅ **内外门开关门动画**

---

## 已实现功能

### 🌀 动态维度系统
- **每个 TARDIS 独立维度**——维度 ID 从 TARDIS 的 UUID 派生（`doctor_m:tardis/<uuid>`）
- **运行时创建 / 加载 / 卸载**——不依赖数据包预注册维度，服务器运行中动态生成
- **持久化清单**——服务器重启后自动恢复所有已创建的维度，玩家数据、方块、实体完整保留
- **彻底删除**——连同存档一起清除，不留死数据

### 🚪 TARDIS 门与传送
- **外门 / 内门双方块**——共享基类 `AbstractTardisDoorBlock`，各自绑定 TARDIS 的内外端
- **走过去即传送**（`entityInside`）——门开着时碰撞触发，关门状态下无反应
- **落点自洽**——进门落内门正面侧、面朝房间；出门落外门正面侧、面朝外界
- **开关门联动**——无论点内门还是外门，两侧状态同步切换
- **传送防抖**——20 tick guard，避免落点边缘反复穿越

### 🔑 TARDIS 状态管理
- **`TardisData`**——单台 TARDIS 的状态（UUID、owner、内外门位置 / 朝向、备用门列表）
- **`TardisRegistryData`**——所有 TARDIS 的持久化清单（SavedData）
- **`TardisManager`**——静态 API：查询、创建、穿门、开关门、删除

### 🧩 备用门机制
- **真出入口唯一**——`TardisData.interiorPos` 是唯一"真门"
- **玩家手动放置的内门**——在 TARDIS 维度内自动登记为**备用门**，不响应右键 / 传送
- **真门被破坏**——最早登记的备用门自动升格为真门
- **无备用门时**——下一次从外门进入会自动重建默认内门（自愈）
- **非法位置放置**——非 TARDIS 维度里放的内门会立刻被拆掉并提示

### 🎨 TARDIS 生成与内容
- **`TardisSpawnerItem`**——一键生成整台 TARDIS（UUID + 维度 + 内门 + 外门）
- **程序生成控制室（临时版）**——矩形房间 + 占位控制台，用于测试进出
- **外门不生成物品**（`@NoItem`）——保证 TARDIS 只能通过 spawner 或命令创建
- **内门生成物品**——玩家可在 TARDIS 内手动放置（自动进备用门列表）

### 🎮 命令
- `/doctor_m tardis create`——在玩家前方生成整台 TARDIS（维度 + 外门 + 内门 + 房间）
- `/doctor_m tardis tp [id]`——传送至自己的 / 指定的 TARDIS
- `/doctor_m tardis release <id>`——OP：删除指定 UUID 的 TARDIS（含外门 + 维度 + 磁盘）
- `/doctor_m tardis info [id]`——查看 TARDIS 详情（owner、内外门位置、备用门数）
- `/doctor_m tardis list`——列出所有已注册的 TARDIS
- `/doctor_m tardis door open|close [id]`——联动开关内外门
- `/doctor_m debug dims`——列出所有动态维度的状态
- `/doctor_m debug save|reload`——批量保存 / 卸载（用于持久化测试）
- `/doctor_m debug purge`——清空所有 TARDIS + 所有动态维度（核武器，慎用）

### 🌍 维度内容
- **自定义维度类型** `doctor_m:tardis`——全黑、无光照、有天花板、不刷怪、可睡觉
- **自定义群系** `doctor_m:tardis`——空生物生成表，从数据层面禁绝自然刷怪
- **虚空地形生成器**——为控制室预留空间

### 🛠️ 基础设施（MossLib）
- **`DynamicDimensionManager`**——运行时维度的核心 API
- **`DimensionRegistryData`**——维度清单持久化
- **`PayloadRegistrar`**——声明式网络包注册
- **`AutoRegister`**——声明式内容注册（1.21.2+ 的 `setId` 兼容）

### 🦴 基岩版模型与动画系统

不再依赖原版方块模型烘焙，也不局限于 Java 版模型格式——DM3 内置了一套**完整的 Bedrock Geometry / Animation 渲染管线**，Blockbench 里怎么摆，游戏里就怎么渲。

#### 渲染管线（`BedrockModelRenderer`）
- **通用基岩几何渲染器**——接受任意 `BedrockGeometryModel`，不知道 TARDIS 是什么，可复用于任何模型
- **Bedrock 原生坐标**——+X 东 / +Y 上 / +Z 南，与 Minecraft 世界坐标一致，**不做任何轴翻转**
- **完整骨骼树递归**——父骨骼 → 子骨骼，逐级累积变换矩阵
- **cube 独立 pivot + rotation**——每个 cube 可单独设定旋转中心与欧拉角
- **六面独立 UV**——每个面可独立指定 UV 矩形、正负 `uv_size`、`flipU` / `flipV` 标记
- **镜像支持**——`mirror: true` 语义正确还原（U 翻转 + 几何同步）

#### 数据结构（`BedrockGeometryModel` / `BedrockAnimationModel`）
- **几何**：`Bone` / `Cube` / `FaceUv` 三层 record，纯数据、不可变
- **动画**：`Animation` / `BoneTracks` / `Keyframe` 三层 record，支持 `rotation` / `position` / `scale` 三通道
- **X 轴镜像**（`mirrorX()`）——用于修正 Blockbench 里"从内往外看"建模的模型：几何、旋转、UV 一次性镜像，渲染层保持干净
- **动画镜像**（`BedrockAnimationModel.mirrorX()`）——关键帧的 Y / Z 欧拉角与 X 位移同步镜像，与几何镜像配套

#### 缓存与加载（`BedrockCache`）
- **Fabric Resource Reload**——随资源包重载自动重新解析，无需重启
- **几何 / 动画分表**——各自独立 map，按 `Identifier` 索引
- **appearance 驱动加载**——只加载当前外观实际引用的几何 / 动画文件，不浪费 I/O
- **零拷贝解析**——Gson 流式读取，直接构建 record

#### 解析器（`BedrockParser`）
- **标准 Blockbench 导出**——`format_version: 1.8.0` 的几何与动画 JSON
- **亚像素 UV**——用 `float` 而非 `int`，支持 16×16 以上的精细纹理
- **负 `uv_size` 语义**——折算为「原点左移 + 正尺寸 + flip 标记」
- **`hold_on_last_frame`**——正确识别为"停在最后一帧"而非"循环"
- **关键帧排序 + 线性插值**——`rotation` / `position` / `scale` 三通道统一采样

#### 门动画集成（`TardisDoorRenderer`）
- **外观 ID 路由**——从 `TardisDoorRenderState` 取 `appearanceId`，查表得 `TardisAsset.Bedrock`
- **开关动画选择**——`open` 状态决定播 `openAnimation` 还是 `closeAnimation`
- **时长 clamp**——`loop=false` 时自动 `min(elapsed, length)`，动画播完停住
- **朝向旋转**——按方块 `FACING` 旋转模型，`MODEL_FACING` 常量标注模型在 Blockbench 里的正面
- **偏移 + 全局缩放**——外观 JSON 里的 `offset` / `scale` 字段运行时应用

#### 外观系统（`TardisAsset.Bedrock` + `TardisAppearance`）
- **JSON 驱动**——每个外观独立配置 `geometry` / `animation` / `texture` / `open` / `close` / `offset` / `scale`
- **多外观共存**——每个外观独立的几何 + 动画 + 纹理，运行时按 ID 加载
- **开关动画可选**——`open` / `close` 字段可留空，退化为静态模型
- **Blockbench 原生兼容**——无需额外插件，导出即可用

---

## 计划实现功能

### 🚪 TARDIS
- **控制室结构（数据包 / NBT）**——替换当前的程序生成临时版
- **飞行 / 时间旅行**——选择目标坐标与时间点，起飞降落
- **变色龙电路**——将 TARDIS 外观伪装成周围方块
- **多房间扩展**——图书馆、衣帽间、游泳池……

### 🦴 动画系统扩展
- **`position` / `scale` 通道**——骨骼位移动画与缩放动画
- **缓动曲线**——`catmullrom` / `smooth` 关键帧插值
- **动画状态机**——idle / takeoff / landing 等多状态切换
- **声音同步**——关键帧事件触发音效
- **粒子绑定**——关键帧事件生成粒子

### 🔐 访问控制
- **可信玩家列表**——TARDIS 主人可授权他人进入
- **钥匙物品**——右键开门 / 授权
- **访问权限命令**——`/doctor_m tardis trust|untrust <player>`

### 🪛 音速起子
- **多模式**——扫描 / 开门 / 破解 / 医疗 / 遥控
- **方块交互**——侦测方块类型、开启铁门、破解红石
- **实体交互**——读取生物信息、治愈、暂时眩晕

### 🧬 再生系统
- **死亡 = 再生**——触发再生动画 + 外貌随机化
- **有限次数**——可配置（默认 12 次）

### 🌍 世界
- Gallifrey ？

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

**Pre-Alpha**——动态维度 + TARDIS 门 / 传送 / 状态管理 + **基岩版模型渲染与骨骼动画**已跑通，控制室仍是程序生成临时版，等待数据包结构替换。

---

## 许可证

MIT

---

## 致谢

- BBC 与《神秘博士》的所有创作者
- Fabric 社区
- Blockbench 与基岩版模型生态
- 所有贡献者（欢迎 PR）