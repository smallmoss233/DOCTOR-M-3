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
    private Identifier exteriorCollisionGeometry = null;
    private Identifier interiorCollisionGeometry = null;

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

    /** 完整构造器。状态字段保持默认（LANDED / 0 / null / null）。 */
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

            // ---- 碰撞几何体 ----
            Identifier.CODEC.optionalFieldOf("exterior_collision_geometry")
                    .forGetter(d -> Optional.ofNullable(d.exteriorCollisionGeometry)),
            Identifier.CODEC.optionalFieldOf("interior_collision_geometry")
                    .forGetter(d -> Optional.ofNullable(d.interiorCollisionGeometry)),

            // ---- 状态系统 ----
            TardisState.CODEC.optionalFieldOf("state", TardisState.LANDED)
                    .forGetter(TardisData::state),
            Codec.INT.optionalFieldOf("state_ticks", 0)
                    .forGetter(TardisData::stateTicks),
            ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("target_dim")
                    .forGetter(d -> Optional.ofNullable(d.targetDim)),
            BlockPos.CODEC.optionalFieldOf("target_pos")
                    .forGetter(d -> Optional.ofNullable(d.targetPos))
    ).apply(inst, (id, owner, ed, ep, ef, ip, inf, app, spares,
                   ecg, icg,
                   state, ticks, tdim, tpos) -> {
        TardisData d = new TardisData(id, owner, ed, ep, ef, ip, inf, app, spares);
        d.exteriorCollisionGeometry = ecg.orElse(null);
        d.interiorCollisionGeometry = icg.orElse(null);
        d.state = state == null ? TardisState.LANDED : state;
        d.stateTicks = ticks;
        d.targetDim = tdim.orElse(null);
        d.targetPos = tpos.orElse(null);
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
    public Identifier exteriorCollisionGeometry() { return exteriorCollisionGeometry; }
    public Identifier interiorCollisionGeometry() { return interiorCollisionGeometry; }

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
    public void setExteriorCollisionGeometry(Identifier id) {
        this.exteriorCollisionGeometry = id;
    }
    public void setInteriorCollisionGeometry(Identifier id) {
        this.interiorCollisionGeometry = id;
    }

    // ============================================================
    //                      新增：状态系统
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
}