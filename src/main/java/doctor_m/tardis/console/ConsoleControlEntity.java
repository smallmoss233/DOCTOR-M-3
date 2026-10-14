package doctor_m.tardis.console;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.register.DMEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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

/**
 * 控件实体：一个"知道自己在哪个骨骼上"的交互锚点。
 *
 * <h2>职责边界</h2>
 * <b>只管</b>：位置 / 碰撞箱 / 生命期 / NBT / 挡位同步。<br>
 * <b>不管</b>：交互业务逻辑 —— 那在 {@link ControlHandler} 的子类里。
 *
 * <h2>consolePos 必须走 DataTracker</h2>
 * 客户端的动画控制器需要按 {@code consolePos} 把控件实体跟控制台 BE 关联，
 * 才能从正确的 clips 里挑片段。若走普通字段，客户端永远是 null —— 所有控件
 * 都会被渲染端跳过，动画不生效。
 */
public class ConsoleControlEntity extends Entity {

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

    @Override public boolean isPickable()  { return true; }
    @Override public boolean isPushable()  { return false; }
    @Override public boolean isAttackable() { return true; }

    @Override
    public void push(double x, double y, double z) {
        // 不被推开
    }

    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.@Nullable Entity other) {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;

        BlockPos cp = getConsolePos();
        if (cp != null) {
            var be = level().getBlockEntity(cp);
            if (!(be instanceof TardisConsoleBlockEntity)) {
                this.discard();
            }
        }
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
                ? ConsoleControlHandler.ClickType.SNEAK_RIGHT
                : ConsoleControlHandler.ClickType.RIGHT;
        ConsoleControlHandler.handleClick(sp, this, type);
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.getEntity() instanceof ServerPlayer sp) {
            var type = sp.isShiftKeyDown()
                    ? ConsoleControlHandler.ClickType.SNEAK_LEFT
                    : ConsoleControlHandler.ClickType.LEFT;
            ConsoleControlHandler.handleClick(sp, this, type);
        }
        return false;
    }

    public @Nullable ControlHandler handler() {
        return ControlHandlers.get(getControlTypeId());
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