package doctor_m.tardis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import doctor_m.tardis.console.ControlType;
import doctor_m.tardis.console.ControlTypes;
import doctor_m.tardis.console.handler.IncrementBehavior;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TardisData {

    /** 默认外观 ID。 */
    public static final Identifier DEFAULT_APPEARANCE =
            Identifier.fromNamespaceAndPath("doctor_m", "tt_capsule");

    /** 默认内饰 ID（对应 {@code data/doctor_m/structure/classic_interior.nbt}）。 */
    public static final Identifier DEFAULT_INTERIOR =
            Identifier.fromNamespaceAndPath("doctor_m", "classic_interior");

    private final UUID id;
    private final UUID owner;
    private ResourceKey<Level> exteriorDim;
    private BlockPos exteriorPos;
    private Direction exteriorFacing;
    private BlockPos interiorPos;
    private Direction interiorFacing;
    private Identifier appearanceId;
    private final List<BlockPos> spareDoors = new ArrayList<>();

    // ============================================================
    //              新增：内饰模板字段
    // ============================================================

    /**
     * 内饰 ID：指向 {@code data/<ns>/structure/<path>.nbt} 结构模板。
     * 由 {@link TardisInteriorLoader} 在生成时加载放置。
     */
    private Identifier interiorId = DEFAULT_INTERIOR;

    /**
     * 内饰是否已生成完毕。生成流程把外门放到<b>关闭</b>状态，只有这里
     * 变 {@code true} 后外门才会打开（见物品/指令的生成序列），
     * {@code TardisManager#teleportInto} 也会拒绝未就绪的进入。
     * <p>默认 {@code true} 是给<b>旧存档</b>的兼容值——老 TARDIS 的内饰
     * 早就生成好了；新生成的 TARDIS 会被显式置 {@code false} 再开建。
     */
    private boolean interiorBuilt = true;

    // ============================================================
    //              新增：状态系统字段
    // ============================================================

    /** 当前状态。默认 LANDED。 */
    private TardisState state = TardisState.LANDED;
    /** 距离下一次状态事件的剩余 tick 数。 */
    private int stateTicks = 0;
    /** 目的地维度（未设置时为 null）。 */
    private ResourceKey<Level> targetDim = null;
    /** 目的地坐标（未设置时为 null）。 */
    private BlockPos targetPos = null;

    /**
     * 全局控件档位表：控件类型 id → 当前档位（0 基）。
     *
     * <h2>一份档位，全体控制台共用</h2>
     * 这是所有控件档位的<b>唯一权威来源</b>。一个 TARDIS 里的每台控制台
     * （以及其上的控件实体）都只是这份表的"视图"：
     * <ul>
     *   <li>新放置的控制台生成控件实体时读这张表对位动画（见
     *       {@code ConsoleControlEntity#syncFromTardis}）</li>
     *   <li>任何一台控制台上的操作经 {@code TardisManager#setControlTier}
     *       写这里，并广播到该 TARDIS 内所有同类型控件实体</li>
     * </ul>
     * key 是类型 id（如 {@code "throttle"}），因此<b>同类型的多个控件实例共享档位</b>
     * ——两台控制台的油门永远指向同一格。想要各自独立的功能，请用不同类型。
     */
    private final Map<String, Integer> controlTiers = new LinkedHashMap<>();

    // ============================================================
    //                      兼容构造器
    // ============================================================

    public TardisData(UUID id,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing) {
        this(id, null, exteriorDim, exteriorPos, exteriorFacing,
                interiorPos, interiorFacing, DEFAULT_APPEARANCE, List.of());
    }

    public TardisData(UUID id,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing,
                      List<BlockPos> spareDoors) {
        this(id, null, exteriorDim, exteriorPos, exteriorFacing,
                interiorPos, interiorFacing, DEFAULT_APPEARANCE, spareDoors);
    }

    public TardisData(UUID id, UUID owner,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing,
                      List<BlockPos> spareDoors) {
        this(id, owner, exteriorDim, exteriorPos, exteriorFacing,
                interiorPos, interiorFacing, DEFAULT_APPEARANCE, spareDoors);
    }

    /** 完整构造器。状态字段保持默认（LANDED / 0 / null / null / 0）。 */
    public TardisData(UUID id, UUID owner,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing,
                      Identifier appearanceId,
                      List<BlockPos> spareDoors) {
        this.id = id;
        this.owner = owner;
        this.exteriorDim = exteriorDim;
        this.exteriorPos = exteriorPos;
        this.exteriorFacing = exteriorFacing;
        this.interiorPos = interiorPos;
        this.interiorFacing = interiorFacing;
        this.appearanceId = appearanceId == null ? DEFAULT_APPEARANCE : appearanceId;
        this.spareDoors.addAll(spareDoors);
    }

    // ============================================================
    //                       CODEC
    // ============================================================

    public static final Codec<TardisData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(TardisData::id),
            UUIDUtil.CODEC.optionalFieldOf("owner", null).forGetter(TardisData::owner),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("exterior_dim").forGetter(TardisData::exteriorDim),
            BlockPos.CODEC.fieldOf("exterior_pos").forGetter(TardisData::exteriorPos),
            Direction.CODEC.fieldOf("exterior_facing").forGetter(TardisData::exteriorFacing),
            BlockPos.CODEC.fieldOf("interior_pos").forGetter(TardisData::interiorPos),
            Direction.CODEC.fieldOf("interior_facing").forGetter(TardisData::interiorFacing),
            Identifier.CODEC.optionalFieldOf("appearance_id", DEFAULT_APPEARANCE)
                    .forGetter(TardisData::appearanceId),
            BlockPos.CODEC.listOf().optionalFieldOf("spare_doors", List.of())
                    .forGetter(TardisData::spareDoors),

            // ---- 状态系统 ----
            TardisState.CODEC.optionalFieldOf("state", TardisState.LANDED)
                    .forGetter(TardisData::state),
            Codec.INT.optionalFieldOf("state_ticks", 0)
                    .forGetter(TardisData::stateTicks),
            ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("target_dim")
                    .forGetter(d -> Optional.ofNullable(d.targetDim)),
            BlockPos.CODEC.optionalFieldOf("target_pos")
                    .forGetter(d -> Optional.ofNullable(d.targetPos)),

            // ---- 全局控件档位表（key = 控件类型 id，value = 档位 0 基）----
            Codec.unboundedMap(Codec.STRING, Codec.INT)
                    .optionalFieldOf("control_tiers", Map.of())
                    .forGetter(TardisData::controlTiers),

            // ---- 内饰模板 ----
            // 基础 9 + 状态 4 + 档位 1 + 这 2 个 = 16，正好在 group 的 16 参数
            // 上限。再要加字段请先给 TardisData 分组。
            Identifier.CODEC.optionalFieldOf("interior_id", DEFAULT_INTERIOR)
                    .forGetter(TardisData::interiorId),
            Codec.BOOL.optionalFieldOf("interior_built", true)
                    .forGetter(TardisData::interiorBuilt)
    ).apply(inst, (id, owner, ed, ep, ef, ip, inf, app, spares,
                   state, ticks, tdim, tpos, tiers,
                   interior, built) -> {
        TardisData d = new TardisData(id, owner, ed, ep, ef, ip, inf, app, spares);
        d.state = state == null ? TardisState.LANDED : state;
        d.stateTicks = ticks;
        d.targetDim = tdim.orElse(null);
        d.targetPos = tpos.orElse(null);
        d.controlTiers.putAll(tiers);
        d.interiorId = interior == null ? DEFAULT_INTERIOR : interior;
        d.interiorBuilt = built;
        return d;
    }));

    // ============================================================
    //                      现有 getter
    // ============================================================

    public UUID id()                        { return id; }
    public UUID owner()                     { return owner; }
    public ResourceKey<Level> exteriorDim() { return exteriorDim; }
    public BlockPos exteriorPos()           { return exteriorPos; }
    public Direction exteriorFacing()       { return exteriorFacing; }
    public BlockPos interiorPos()           { return interiorPos; }
    public Direction interiorFacing()       { return interiorFacing; }
    public Identifier appearanceId()        { return appearanceId; }
    public List<BlockPos> spareDoors()      { return List.copyOf(spareDoors); }
    public Identifier interiorId()          { return interiorId; }
    public boolean interiorBuilt()          { return interiorBuilt; }

    // ============================================================
    //                      现有 setter
    // ============================================================

    public void setExterior(ResourceKey<Level> dim, BlockPos pos, Direction facing) {
        this.exteriorDim = dim; this.exteriorPos = pos; this.exteriorFacing = facing;
    }
    public void setInterior(BlockPos pos, Direction facing) {
        this.interiorPos = pos; this.interiorFacing = facing;
    }
    public void setAppearanceId(Identifier id) {
        this.appearanceId = id == null ? DEFAULT_APPEARANCE : id;
    }

    /** 设置内饰模板 ID（下次生成时生效）。 */
    public void setInteriorId(Identifier id) {
        this.interiorId = id == null ? DEFAULT_INTERIOR : id;
    }

    /** 标记内饰生成状态。仅由 {@link TardisInteriorLoader} 写。 */
    public void setInteriorBuilt(boolean built) {
        this.interiorBuilt = built;
    }

    public void addSpareDoor(BlockPos pos) {
        if (!spareDoors.contains(pos)) spareDoors.add(pos);
    }
    public boolean removeSpareDoor(BlockPos pos) { return spareDoors.remove(pos); }
    public BlockPos popSpareDoor() {
        return spareDoors.isEmpty() ? null : spareDoors.remove(0);
    }

    // ============================================================
    //                      状态系统
    // ============================================================

    public TardisState state() { return state; }
    public int stateTicks()    { return stateTicks; }
    public ResourceKey<Level> targetDim() { return targetDim; }
    public BlockPos targetPos() { return targetPos; }

    /** 设置状态 + 该状态的剩余 tick。 */
    public void setState(TardisState s, int ticks) {
        this.state = s == null ? TardisState.LANDED : s;
        this.stateTicks = ticks;
    }

    /** 仅更新剩余 tick。 */
    public void setStateTicks(int ticks) { this.stateTicks = ticks; }

    /** 设置目的地。 */
    public void setDestination(ResourceKey<Level> dim, BlockPos pos) {
        this.targetDim = dim;
        this.targetPos = pos;
    }

    /** 清空目的地。 */
    public void clearDestination() {
        this.targetDim = null;
        this.targetPos = null;
    }

    /** 是否已设置目的地。 */
    public boolean hasDestination() {
        return targetDim != null && targetPos != null;
    }

    // ============================================================
    //                  全局控件档位表
    // ============================================================
    // 唯一权威来源。业务层原有的具名 API（throttle / handbrake / ...）
    // 全部变成这张表的具名读写视图，签名不变，调用方无需改动。

    /** 只读视图（供 CODEC 序列化和外部遍历用）。 */
    public Map<String, Integer> controlTiers() {
        return Map.copyOf(controlTiers);
    }

    /** 读某类型控件的当前档位；未记录过返回 0。 */
    public int tier(String typeId) {
        return typeId == null ? 0 : controlTiers.getOrDefault(typeId, 0);
    }

    /**
     * 写某类型控件的档位。按 {@link ControlType} 注册的档位数夹取；
     * 类型未注册时夹取到 0（不应发生——只有交互链路会调这里）。
     * 注意：这只写数据，<b>不同步控件实体</b>；服务端请走
     * {@code TardisManager#setControlTier}（写表 + 广播动画对位）。
     */
    public void setTier(String typeId, int value) {
        if (typeId == null) return;
        ControlType type = ControlTypes.byId(typeId);
        int max = type == null ? 0 : type.defaultTiers() - 1;
        controlTiers.put(typeId, Math.max(0, Math.min(value, max)));
    }

    // ---- 具名视图：油门 ----

    /** 油门档位（0..4）。0 = 熄火，正数 = 飞行中该档速度。 */
    public int throttle() { return tier(ControlTypes.THROTTLE.id()); }

    /** 设置油门档位。范围夹取到 0..4。 */
    public void setThrottle(int value) { setTier(ControlTypes.THROTTLE.id(), value); }

    // ---- 具名视图：手刹 ----

    public int handbrake() { return tier(ControlTypes.HANDBRAKE.id()); }

    /** 设置手刹档位。范围夹取到 0..3。 */
    public void setHandbrake(int value) { setTier(ControlTypes.HANDBRAKE.id(), value); }

    // ---- 具名视图：驻车制动 ----

    public boolean parkingBrake() { return tier(ControlTypes.PARKING_BRAKE.id()) > 0; }

    public void setParkingBrake(boolean value) {
        setTier(ControlTypes.PARKING_BRAKE.id(), value ? 1 : 0);
    }

    // ---- 具名视图：坐标增量器 ----

    /**
     * 坐标控件单次点击的增量（格）。由 {@code coord_step} 控件的档位
     * 查 {@link IncrementBehavior#STEP_PER_TIER} 得出（档位 0 → 1 格 … 档位 5 → 100000 格）。
     */
    public int coordStep() {
        int tier = Math.max(0, Math.min(tier(ControlTypes.COORD_STEP.id()),
                IncrementBehavior.STEP_PER_TIER.length - 1));
        return IncrementBehavior.STEP_PER_TIER[tier];
    }
}