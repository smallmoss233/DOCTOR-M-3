package doctor_m.tardis.data;

import com.mojang.serialization.Codec;
import doctor_m.tardis.console.control.ControlType;
import doctor_m.tardis.console.control.ControlTypes;
import doctor_m.tardis.console.control.behavior.IncrementBehavior;
import mosslib.core.api.MossLibCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;

public final class TardisData {

    /** 默认外观 ID。 */
    public static final Identifier DEFAULT_APPEARANCE =
            Identifier.fromNamespaceAndPath("doctor_m", "tt_capsule");

    /** 默认内饰 ID。 */
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
    //              内饰模板字段
    // ============================================================
    private Identifier interiorId = DEFAULT_INTERIOR;
    private boolean interiorBuilt = true;
    private Identifier pendingInteriorId = null;
    private BlockPos swapClearMin = null;
    private BlockPos swapClearMax = null;
    private int interiorSwapDelay = 0;

    // ============================================================
    //              状态系统字段
    // ============================================================
    private TardisState state = TardisState.LANDED;
    private int stateTicks = 0;
    private ResourceKey<Level> targetDim = null;
    private BlockPos targetPos = null;
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
    //                      模块化 CODEC 定义
    // ============================================================

    /** 位置与外观模块。 */
    public record LocationState(Identifier appearanceId,
                                ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                                BlockPos interiorPos, Direction interiorFacing,
                                List<BlockPos> spareDoors) {
        public static final Codec<LocationState> CODEC = MossLibCodec.of(
                LocationState::new,
                MossLibCodec.o("appearance_id", Identifier.CODEC, DEFAULT_APPEARANCE, LocationState::appearanceId),
                MossLibCodec.f("exterior_dim", ResourceKey.codec(Registries.DIMENSION), LocationState::exteriorDim),
                MossLibCodec.f("exterior_pos", BlockPos.CODEC, LocationState::exteriorPos),
                MossLibCodec.f("exterior_facing", Direction.CODEC, LocationState::exteriorFacing),
                MossLibCodec.f("interior_pos", BlockPos.CODEC, LocationState::interiorPos),
                MossLibCodec.f("interior_facing", Direction.CODEC, LocationState::interiorFacing),
                MossLibCodec.o("spare_doors", BlockPos.CODEC.listOf(), List.of(), LocationState::spareDoors)
        );
    }

    /** 飞行状态与控件模块。 */
    public record FlightState(TardisState state, int stateTicks,
                              ResourceKey<Level> targetDim, BlockPos targetPos,
                              Map<String, Integer> controlTiers) {
        public static final Codec<FlightState> CODEC = MossLibCodec.of(
                // 这里的 dimOpt 和 posOpt 就是 Optional 类型，直接 orElse(null) 安全又省心
                (state, ticks, dimOpt, posOpt, tiers) -> new FlightState(state, ticks, dimOpt.orElse(null), posOpt.orElse(null), tiers),
                MossLibCodec.o("state", TardisState.CODEC, TardisState.LANDED, FlightState::state),
                MossLibCodec.o("state_ticks", Codec.INT, 0, FlightState::stateTicks),
                MossLibCodec.opt("target_dim", ResourceKey.codec(Registries.DIMENSION), FlightState::targetDim),
                MossLibCodec.opt("target_pos", BlockPos.CODEC, FlightState::targetPos),
                MossLibCodec.o("control_tiers", Codec.unboundedMap(Codec.STRING, Codec.INT), Map.of(), FlightState::controlTiers)
        );
    }

    public record InteriorState(Identifier interiorId, boolean built, Identifier pendingId,
                                BlockPos clearMin, BlockPos clearMax) {
        public static final Codec<InteriorState> CODEC = MossLibCodec.of(
                (id, built, pendingOpt, minOpt, maxOpt) -> new InteriorState(id, built, pendingOpt.orElse(null), minOpt.orElse(null), maxOpt.orElse(null)),
                MossLibCodec.o("id", Identifier.CODEC, DEFAULT_INTERIOR, InteriorState::interiorId),
                MossLibCodec.o("built", Codec.BOOL, true, InteriorState::built),
                MossLibCodec.opt("pending_id", Identifier.CODEC, InteriorState::pendingId),
                MossLibCodec.opt("clear_min", BlockPos.CODEC, InteriorState::clearMin),
                MossLibCodec.opt("clear_max", BlockPos.CODEC, InteriorState::clearMax)
        );
    }

    // 视图提取器
    public LocationState locationState() {
        return new LocationState(appearanceId, exteriorDim, exteriorPos, exteriorFacing, interiorPos, interiorFacing, spareDoors);
    }

    public FlightState flightState() {
        return new FlightState(state, stateTicks, targetDim, targetPos, controlTiers);
    }

    public InteriorState interiorState() {
        return new InteriorState(interiorId, interiorBuilt, pendingInteriorId, swapClearMin, swapClearMax);
    }

    // ============================================================
    //                      主 CODEC
    // ============================================================

    public static final Codec<TardisData> CODEC = MossLibCodec.of(
            (id, ownerOpt, loc, flight, interior) -> {
                TardisData d = new TardisData(
                        id, ownerOpt.orElse(null),
                        loc.exteriorDim(), loc.exteriorPos(), loc.exteriorFacing(),
                        loc.interiorPos(), loc.interiorFacing(),
                        loc.appearanceId(), loc.spareDoors()
                );
                d.state = flight.state();
                d.stateTicks = flight.stateTicks();
                d.targetDim = flight.targetDim();
                d.targetPos = flight.targetPos();
                d.controlTiers.putAll(flight.controlTiers());

                d.interiorId = interior.interiorId();
                d.interiorBuilt = interior.built();
                d.pendingInteriorId = interior.pendingId();
                d.swapClearMin = interior.clearMin();
                d.swapClearMax = interior.clearMax();
                return d;
            },
            MossLibCodec.f("id", UUIDUtil.CODEC, TardisData::id),
            // 改为 opt
            MossLibCodec.opt("owner", UUIDUtil.CODEC, TardisData::owner),
            MossLibCodec.f("location", LocationState.CODEC, TardisData::locationState),
            MossLibCodec.f("flight", FlightState.CODEC, TardisData::flightState),
            MossLibCodec.o("interior", InteriorState.CODEC, new InteriorState(DEFAULT_INTERIOR, true, null, null, null), TardisData::interiorState)
    );

    // ============================================================
    //                      Getter / Setter / 状态系统 / 控件
    // ============================================================
    // (这部分保持你原来的代码原封不动，因为逻辑非常清晰，没必要改动)

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
    public Identifier pendingInteriorId()   { return pendingInteriorId; }
    public boolean isInteriorChanging()     { return pendingInteriorId != null; }
    public BlockPos swapClearMin()          { return swapClearMin; }
    public BlockPos swapClearMax()          { return swapClearMax; }
    public int interiorSwapDelay()          { return interiorSwapDelay; }
    public void setInteriorSwapDelay(int ticks) { this.interiorSwapDelay = Math.max(0, ticks); }

    public void setExterior(ResourceKey<Level> dim, BlockPos pos, Direction facing) {
        this.exteriorDim = dim; this.exteriorPos = pos; this.exteriorFacing = facing;
    }
    public void setInterior(BlockPos pos, Direction facing) {
        this.interiorPos = pos; this.interiorFacing = facing;
    }
    public void setAppearanceId(Identifier id) {
        this.appearanceId = id == null ? DEFAULT_APPEARANCE : id;
    }
    public void setInteriorId(Identifier id) {
        this.interiorId = id == null ? DEFAULT_INTERIOR : id;
    }
    public void setInteriorBuilt(boolean built) {
        this.interiorBuilt = built;
    }

    public void beginInteriorChange(Identifier newId, BlockPos clearMin, BlockPos clearMax) {
        this.pendingInteriorId = newId;
        this.swapClearMin = clearMin;
        this.swapClearMax = clearMax;
        this.interiorSwapDelay = 0;
    }

    public void endInteriorChange() {
        this.pendingInteriorId = null;
        this.swapClearMin = null;
        this.swapClearMax = null;
        this.interiorSwapDelay = 0;
    }

    public void addSpareDoor(BlockPos pos) {
        if (!spareDoors.contains(pos)) spareDoors.add(pos);
    }
    public boolean removeSpareDoor(BlockPos pos) { return spareDoors.remove(pos); }
    public BlockPos popSpareDoor() {
        return spareDoors.isEmpty() ? null : spareDoors.remove(0);
    }
    public void clearSpareDoors() { spareDoors.clear(); }

    public TardisState state() { return state; }
    public int stateTicks()    { return stateTicks; }
    public ResourceKey<Level> targetDim() { return targetDim; }
    public BlockPos targetPos() { return targetPos; }

    public void setState(TardisState s, int ticks) {
        this.state = s == null ? TardisState.LANDED : s;
        this.stateTicks = ticks;
    }

    public void setStateTicks(int ticks) { this.stateTicks = ticks; }

    public void setDestination(ResourceKey<Level> dim, BlockPos pos) {
        this.targetDim = dim;
        this.targetPos = pos;
    }

    public void clearDestination() {
        this.targetDim = null;
        this.targetPos = null;
    }

    public boolean hasDestination() {
        return targetDim != null && targetPos != null;
    }

    public Map<String, Integer> controlTiers() {
        return Map.copyOf(controlTiers);
    }

    public int tier(String typeId) {
        return typeId == null ? 0 : controlTiers.getOrDefault(typeId, 0);
    }

    public void setTier(String typeId, int value) {
        if (typeId == null) return;
        ControlType type = ControlTypes.byId(typeId);
        int max = type == null ? 0 : type.defaultTiers() - 1;
        controlTiers.put(typeId, Math.max(0, Math.min(value, max)));
    }

    public int throttle() { return tier(ControlTypes.THROTTLE.id()); }
    public void setThrottle(int value) { setTier(ControlTypes.THROTTLE.id(), value); }

    public int handbrake() { return tier(ControlTypes.HANDBRAKE.id()); }
    public void setHandbrake(int value) { setTier(ControlTypes.HANDBRAKE.id(), value); }

    public boolean parkingBrake() { return tier(ControlTypes.PARKING_BRAKE.id()) > 0; }
    public void setParkingBrake(boolean value) {
        setTier(ControlTypes.PARKING_BRAKE.id(), value ? 1 : 0);
    }

    public int coordStep() {
        int tier = Math.max(0, Math.min(tier(ControlTypes.COORD_STEP.id()),
                IncrementBehavior.STEP_PER_TIER.length - 1));
        return IncrementBehavior.STEP_PER_TIER[tier];
    }
}