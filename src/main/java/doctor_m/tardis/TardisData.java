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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TardisData {

    /** 默认外观 ID。 */
    public static final Identifier DEFAULT_APPEARANCE =
            Identifier.fromNamespaceAndPath("doctor_m", "tt_capsule");

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
    /** 当前油门档位（0..4）。0 = 熄火，正数 = 飞行中该档速度。 */
    private int throttle = 0;
    /** 控件状态。独立成 record 是因为 RecordCodecBuilder.group 最多 16 个字段，
     *  14 个基础字段 + 控件状态合 1 个 = 15 个，留有余量。 */
    private ControlState controlState = ControlState.DEFAULT;

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
            Codec.INT.optionalFieldOf("throttle", 0)
                    .forGetter(TardisData::throttle),

            // ---- 控件状态（合一个 record，绕开 group 的 16 参数上限）----
            ControlState.CODEC.optionalFieldOf("control_state", ControlState.DEFAULT)
                    .forGetter(TardisData::controlState)
    ).apply(inst, (id, owner, ed, ep, ef, ip, inf, app, spares,
                   state, ticks, tdim, tpos, throt, ctrl) -> {
        TardisData d = new TardisData(id, owner, ed, ep, ef, ip, inf, app, spares);
        d.state = state == null ? TardisState.LANDED : state;
        d.stateTicks = ticks;
        d.targetDim = tdim.orElse(null);
        d.targetPos = tpos.orElse(null);
        d.throttle = Math.max(0, Math.min(throt, 4));
        d.controlState = ctrl.sanitized();
        return d;
    }));

    /**
     * 控件相关状态（手刹 / 驻车制动 / 坐标增量）。
     * 旧存档没有 {@code control_state} 字段时整体取 {@link #DEFAULT}。
     */
    public record ControlState(int handbrake, boolean parkingBrake, int coordStep) {
        public static final ControlState DEFAULT = new ControlState(0, false, 1);

        public static final Codec<ControlState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.INT.optionalFieldOf("handbrake", 0).forGetter(ControlState::handbrake),
                Codec.BOOL.optionalFieldOf("parking_brake", false).forGetter(ControlState::parkingBrake),
                Codec.INT.optionalFieldOf("coord_step", 1).forGetter(ControlState::coordStep)
        ).apply(inst, ControlState::new));

        /** 越界值夹取回合法范围。 */
        ControlState sanitized() {
            return new ControlState(
                    Math.max(0, Math.min(handbrake, 3)),
                    parkingBrake,
                    Math.max(1, Math.min(coordStep, 100_000)));
        }
    }

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
    //                      油门
    // ============================================================

    public int throttle() { return throttle; }

    /** 设置油门档位。范围夹取到 0..4。 */
    public void setThrottle(int value) {
        this.throttle = Math.max(0, Math.min(value, 4));
    }

    // ============================================================
    //                      控件状态
    // ============================================================

    public ControlState controlState() { return controlState; }

    public int handbrake() { return controlState.handbrake(); }

    /** 设置手刹档位。范围夹取到 0..3。 */
    public void setHandbrake(int value) {
        int hb = Math.max(0, Math.min(value, 3));
        this.controlState = new ControlState(hb, controlState.parkingBrake(), controlState.coordStep());
    }

    public boolean parkingBrake() { return controlState.parkingBrake(); }

    public void setParkingBrake(boolean value) {
        this.controlState = new ControlState(controlState.handbrake(), value, controlState.coordStep());
    }

    /** 坐标控件单次点击的增量（格）。 */
    public int coordStep() { return controlState.coordStep(); }

    /** 设置坐标增量。夹取到 1..100000（单次点击上限 10 万）。 */
    public void setCoordStep(int value) {
        int step = Math.max(1, Math.min(value, 100_000));
        this.controlState = new ControlState(controlState.handbrake(), controlState.parkingBrake(), step);
    }
}