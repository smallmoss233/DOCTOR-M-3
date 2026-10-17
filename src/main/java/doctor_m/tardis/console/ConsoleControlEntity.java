package doctor_m.tardis.console;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.register.DMEntityTypes;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 控件实体：一个"知道自己在哪个骨骼上"的交互锚点。
 *
 * <h2>职责边界</h2>
 * <b>只管</b>：位置 / 碰撞箱 / 生命期 / NBT / 挡位视图同步。<br>
 * <b>不管</b>：交互业务逻辑 —— 那在 {@link ControlBehavior} 的实现里。
 *
 * <h2>档位：实体是视图，TardisData 是权威</h2>
 * 同一 TARDIS 内所有控制台的同类控件共享一份档位（存在 {@code TardisData}）。
 * 实体上的 tier/target_time 只服务于客户端动画对位：
 * <ul>
 *   <li>操作某台控制台 → {@code TardisManager#setControlTier} 写表并广播，
 *       所有同类型实体 {@link #applyTier}</li>
 *   <li>实体生成后第一个 tick → {@link #syncFromTardis} 自动对位，
 *       新控制台因此无需任何额外配置就与其它控制台状态一致</li>
 * </ul>
 *
 * <h2>consolePos 必须走 DataTracker</h2>
 * 客户端的动画控制器需要按 {@code consolePos} 把控件实体跟控制台 BE 关联，
 * 才能从正确的 clips 里挑片段。若走普通字段，客户端永远是 null —— 所有控件
 * 都会被渲染端跳过，动画不生效。
 */
public class ConsoleControlEntity extends Entity {

    /** 控制台 BE 存活检查的间隔（tick）。没必要每 tick 都查方块实体。 */
    private static final int CONSOLE_CHECK_INTERVAL = 20;

    // =========================================================
    //                      同步字段
    // =========================================================

    private static final EntityDataAccessor<String> DATA_CONTROL_TYPE =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_BONE_NAME =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_TARGET_TIME =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.FLOAT);

    /** 碰撞箱三轴尺寸（格）。客户端读它来重建 AABB。 */
    private static final EntityDataAccessor<Float> DATA_SIZE_X =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SIZE_Y =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SIZE_Z =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.FLOAT);

    /**
     * 所属控制台方块位置。{@link BlockPos#ZERO} 当"未设置"的哨兵值 ——
     * 控制台本身永远不在 (0,0,0)（TARDIS 维度里内门/房间都不在原点）。
     */
    private static final EntityDataAccessor<BlockPos> DATA_CONSOLE_POS =
            SynchedEntityData.defineId(ConsoleControlEntity.class, EntityDataSerializers.BLOCK_POS);

    public ConsoleControlEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    // =========================================================
    //                      生成
    // =========================================================

    public static ConsoleControlEntity spawn(ServerLevel level,
                                             BlockPos consolePos,
                                             ConsoleControlLayout.ControlBounds bounds,
                                             int initialTier) {
        ConsoleControlEntity e = new ConsoleControlEntity(DMEntityTypes.CONSOLE_CONTROL, level);
        e.setConsolePos(consolePos);
        e.setBoneName(bounds.boneName());
        e.setControlTypeId(bounds.config().type().id());
        e.setTier(initialTier);
        e.setTargetTime(bounds.config().timeForTier(initialTier));
        e.applyBounds(bounds.worldBox());
        level.addFreshEntity(e);
        return e;
    }

    // =========================================================
    //                      位置与尺寸
    // =========================================================

    public void applyBounds(AABB worldBox) {
        float sx = (float) (worldBox.maxX - worldBox.minX);
        float sy = (float) (worldBox.maxY - worldBox.minY);
        float sz = (float) (worldBox.maxZ - worldBox.minZ);

        this.entityData.set(DATA_SIZE_X, sx);
        this.entityData.set(DATA_SIZE_Y, sy);
        this.entityData.set(DATA_SIZE_Z, sz);

        Vec3 center = worldBox.getCenter();
        this.setPos(center.x, center.y, center.z);
    }

    @Override
    protected AABB makeBoundingBox(Vec3 pos) {
        float sx = this.entityData.get(DATA_SIZE_X);
        float sy = this.entityData.get(DATA_SIZE_Y);
        float sz = this.entityData.get(DATA_SIZE_Z);
        return new AABB(
                pos.x - sx / 2, pos.y - sy / 2, pos.z - sz / 2,
                pos.x + sx / 2, pos.y + sy / 2, pos.z + sz / 2
        );
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float sx = this.entityData.get(DATA_SIZE_X);
        float sy = this.entityData.get(DATA_SIZE_Y);
        float sz = this.entityData.get(DATA_SIZE_Z);
        return EntityDimensions.fixed(Math.max(Math.max(sx, sz), 0.01f), Math.max(sy, 0.01f));
    }

    // =========================================================
    //                      同步更新回调
    // =========================================================

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == DATA_SIZE_X || key == DATA_SIZE_Y || key == DATA_SIZE_Z) {
            this.refreshDimensions();
        }
    }

    // =========================================================
    //                      实体行为
    // =========================================================

    @Override public boolean isPickable()   { return true; }
    @Override public boolean isPushable()   { return false; }
    @Override public boolean isAttackable() { return true; }

    @Override
    public void push(double x, double y, double z) {
        // 不被推开
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;

        // 第一个服务端 tick：从所属 TARDIS 的全局档位表对位动画。
        // 覆盖两种情况：新放置的控制台（实体刚生成）与存档重载（实体从 NBT 回来）。
        // NBT 里的 tier 只是缓存，以 TardisData 为准。
        if (this.tickCount == 1) syncFromTardis();

        // 降频检查：控制台 BE 被拆掉时丢弃控件实体。
        // 每秒查一次足够 —— 拆方块是低频事件，且延迟 1 秒不可感知。
        if (this.tickCount % CONSOLE_CHECK_INTERVAL != 0) return;

        BlockPos cp = getConsolePos();
        if (cp != null && !(level().getBlockEntity(cp) instanceof TardisConsoleBlockEntity)) {
            this.discard();
        }
    }

    // =========================================================
    //                  全局档位视图
    // =========================================================
    // 档位的唯一权威在 TardisData（key = 控件类型 id）。实体上的
    // DATA_TIER / DATA_TARGET_TIME 只是"视图缓存"，供客户端做动画对位。

    /**
     * 写档位并按<b>自身</b>的控件配置刷新动画目标时间。
     * 被动入口：由 {@code TardisManager#setControlTier} 在广播时调用 ——
     * 同一 TARDIS 里每个同类型实体都会收到，各自的 duration 可能不同
     * （不同外观），所以目标时间各自算。
     */
    public void applyTier(int tier) {
        setTier(tier);
        setTargetTime(resolveTimeForTier(tier));
    }

    /**
     * 从所属 TARDIS 的全局档位表对位自己的动画（服务端）。
     *
     * <p>在两个时机被调用：
     * <ul>
     *   <li>生成后第一个服务端 tick（覆盖新放置控制台 / 区块重载两种情况）</li>
     *   <li>控制台 BE 生成实体后也可以显式调用（早于第一个 tick 生效）</li>
     * </ul>
     * 效果：新控制台的每个控件自动跳到当前档位对应的动画位置，与其它控制台一致。
     */
    public void syncFromTardis() {
        if (!(level() instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();
        BlockPos cp = getConsolePos();
        if (server == null || cp == null
                || !(sl.getBlockEntity(cp) instanceof TardisConsoleBlockEntity be)) {
            return;
        }
        UUID tardisId = be.getTardisId();
        if (tardisId == null) return;
        TardisData data = TardisManager.get(server, tardisId);
        ControlType type = controlType();
        if (data == null || type == null) return;

        applyTier(data.tier(type.id()));
    }

    /** 查自身外观配置，算出某档位对应的动画时间点；无法解析时 0（回静止位）。 */
    private float resolveTimeForTier(int tier) {
        BlockPos cp = getConsolePos();
        if (cp == null || !(level() instanceof ServerLevel sl)) return 0f;
        if (!(sl.getBlockEntity(cp) instanceof TardisConsoleBlockEntity be)) return 0f;

        TardisConsoleAppearance app = TardisConsoleRegistry.get(be.getAppearanceId());
        if (app == null) return 0f;
        ControlConfig config = ConsoleBoneMatcher.resolve(getBoneName(), app);
        return config == null ? 0f : config.timeForTier(tier);
    }

    // =========================================================
    //                      交互
    // =========================================================

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        var type = sp.isShiftKeyDown()
                ? ClickType.SNEAK_RIGHT
                : ClickType.RIGHT;
        ConsoleControlHandler.handleClick(sp, this, type);
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.getEntity() instanceof ServerPlayer sp) {
            var type = sp.isShiftKeyDown()
                    ? ClickType.SNEAK_LEFT
                    : ClickType.LEFT;
            ConsoleControlHandler.handleClick(sp, this, type);
        }
        return false;
    }

    /** 控件类型；id 未注册时返回 null。 */
    public @Nullable ControlType controlType() {
        return ControlTypes.byId(getControlTypeId());
    }

    /** 控件行为；类型未注册时返回 null。 */
    public @Nullable ControlBehavior behavior() {
        ControlType type = controlType();
        return type == null ? null : type.behavior();
    }

    // =========================================================
    //              名称 / ID（其它模组视角 + 名称显示）
    // =========================================================

    /**
     * 实体显示 ID：按控件类型自动派生，例如
     * {@code doctor_m:console_control.throttle}。
     *
     * <p>所有控件共用一个 EntityType，其它模组（物品浏览、指令反馈、
     * 调试工具等）看到的默认名字全是同一个，无法区分控件。这里按实例的
     * 控件类型改写 {@code getName} —— {@code getDisplayName()} 在没有
     * custom name 时会走到这里，所以其它模组看到的就是派生 ID。
     *
     * <p><b>不占用 custom name 字段</b>：{@code setCustomName} 仍然空着，
     * 留给之后的控件名称显示功能做玩家可见的覆盖名。
     */
    @Override
    public Component getName() {
        String typeId = getControlTypeId();
        if (typeId.isEmpty()) return super.getName();
        return Component.translatable("entity.doctor_m.console_control." + typeId);
    }

    /**
     * 控件名是否对玩家可见（铭牌式显示）。<b>目前是空判断条件，恒
     * {@code false} —— 名字完全不显示。</b>
     *
     * <p>之后做名称显示功能时在这里加条件（如：玩家正瞄准 / 距离阈值 /
     * 潜行查看），显示文本取 {@link #getName()} 或 custom name；
     * {@code ConsoleControlRenderer} 的名字渲染必须经过本条件，不允许绕过。
     */
    public boolean shouldShowName(@Nullable Player viewer) {
        return false;
    }

    // =========================================================
    //                      同步字段的访问器
    // =========================================================

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_CONTROL_TYPE, "");
        builder.define(DATA_BONE_NAME, "");
        builder.define(DATA_TIER, 0);
        builder.define(DATA_TARGET_TIME, 0f);
        builder.define(DATA_SIZE_X, 0.1f);
        builder.define(DATA_SIZE_Y, 0.1f);
        builder.define(DATA_SIZE_Z, 0.1f);
        builder.define(DATA_CONSOLE_POS, BlockPos.ZERO);
    }

    public String getControlTypeId()  { return this.entityData.get(DATA_CONTROL_TYPE); }
    public void setControlTypeId(String id) { this.entityData.set(DATA_CONTROL_TYPE, id == null ? "" : id); }

    public String getBoneName()       { return this.entityData.get(DATA_BONE_NAME); }
    public void setBoneName(String n) { this.entityData.set(DATA_BONE_NAME, n == null ? "" : n); }

    public int getTier()              { return this.entityData.get(DATA_TIER); }
    public void setTier(int t)        { this.entityData.set(DATA_TIER, t); }

    public float getTargetTime()      { return this.entityData.get(DATA_TARGET_TIME); }
    public void setTargetTime(float t){ this.entityData.set(DATA_TARGET_TIME, t); }

    /**
     * 所属控制台位置。未设置返回 null（而不是 BlockPos.ZERO）——
     * 这样客户端 `pos.equals(entity.getConsolePos())` 在未同步前自然失败。
     */
    public @Nullable BlockPos getConsolePos() {
        BlockPos p = this.entityData.get(DATA_CONSOLE_POS);
        return p.equals(BlockPos.ZERO) ? null : p;
    }

    public void setConsolePos(@Nullable BlockPos pos) {
        this.entityData.set(DATA_CONSOLE_POS, pos == null ? BlockPos.ZERO : pos);
    }

    // =========================================================
    //                      存档
    // =========================================================

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        BlockPos cp = getConsolePos();
        if (cp != null) output.putLong("console_pos", cp.asLong());
        output.putString("control_type", getControlTypeId());
        output.putString("bone_name", getBoneName());
        output.putInt("tier", getTier());
        output.putFloat("target_time", getTargetTime());
        output.putFloat("size_x", this.entityData.get(DATA_SIZE_X));
        output.putFloat("size_y", this.entityData.get(DATA_SIZE_Y));
        output.putFloat("size_z", this.entityData.get(DATA_SIZE_Z));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        long packed = input.getLongOr("console_pos", Long.MIN_VALUE);
        if (packed != Long.MIN_VALUE) {
            setConsolePos(BlockPos.of(packed));      // ← 走 setter 写进 DataTracker
        }
        setControlTypeId(input.getStringOr("control_type", ""));
        setBoneName(input.getStringOr("bone_name", ""));
        setTier(input.getIntOr("tier", 0));
        setTargetTime(input.getFloatOr("target_time", 0f));
        this.entityData.set(DATA_SIZE_X, input.getFloatOr("size_x", 0.1f));
        this.entityData.set(DATA_SIZE_Y, input.getFloatOr("size_y", 0.1f));
        this.entityData.set(DATA_SIZE_Z, input.getFloatOr("size_z", 0.1f));
    }
}