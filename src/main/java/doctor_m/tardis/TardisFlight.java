package doctor_m.tardis;

import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.register.DMBlocks;
import doctor_m.register.DMSounds;
import doctor_m.tardis.console.handler.LandingFacingBehavior;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisState;
import doctor_m.tardis.door.TardisDoorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

public final class TardisFlight {

    private TardisFlight() {}

    // ================================================================
    //                      状态系统常量
    // ================================================================

    private static final int TAKEOFF_TICKS = 360;
    private static final int LANDING_TICKS = 400;
    private static final int CRASH_TICKS = 100;
    private static final int FLYING_SOUND_INTERVAL = 50;
    private static final int FADE_START_DELAY = 30;

    private static final int SILENT_FLAGS = 2 | 16;

    // ================================================================
    //                      速度与位移计算
    // ================================================================

    public static double speedOf(int throttle) {
        return speedOf(throttle, 0);
    }

    public static double speedOf(int throttle, int handbrake) {
        if (throttle <= 0) return 0;
        double base = 2.0 * (1 << throttle);
        return handbrake > 0 ? base / (1 << Math.min(handbrake, 3)) : base;
    }

    private static void tickMovement(MinecraftServer server, TardisData data) {
        int throttle = data.throttle();
        double speed = speedOf(throttle, data.handbrake());
        if (speed <= 0) return;
        if (!data.hasDestination()) return;

        if (!data.exteriorDim().equals(data.targetDim())) return;

        BlockPos current = data.exteriorPos();
        BlockPos target  = data.targetPos();

        Vec3 from = Vec3.atCenterOf(current);
        Vec3 to   = Vec3.atCenterOf(target);
        Vec3 delta = to.subtract(from);
        double distance = delta.length();

        if (distance < 1e-4) return;

        Vec3 step = (distance <= speed) ? delta : delta.normalize().scale(speed);
        Vec3 next = from.add(step);
        BlockPos nextPos = BlockPos.containing(next);

        if (!nextPos.equals(current)) {
            data.setExterior(data.exteriorDim(), nextPos, data.exteriorFacing());
        }
    }

    // ================================================================
    //                      状态流转与每 tick 逻辑
    // ================================================================

    public static boolean startTakeoff(MinecraftServer server, TardisData data) {
        if (data.isInteriorChanging()) return false;
        if (data.state() != TardisState.LANDED) return false;

        TardisDoorManager.setDoorOpen(server, data, false);
        data.setState(TardisState.TAKEOFF, TAKEOFF_TICKS);
        TardisManager.getRegistry(server).setDirty();

        playTardisSound(server, data, DMSounds.DEMAT, 1.0f, 1.0f);
        return true;
    }

    public static boolean startLanding(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        // 【修复 2】立即结算目的地坐标，让塔迪斯瞬间移动到最终降落点
        applyDestination(server, data);

        // 【修复 1 & 3】立即在目标位置放置外壳门，并触发淡入（耗时 60 tick / 3 秒）
        // 这样在 20 秒的降落音效播放期间，门就已经提前逐渐显形了
        placeExteriorDoor(server, data);

        data.setState(TardisState.LANDING, LANDING_TICKS);
        TardisManager.getRegistry(server).setDirty();

        // 【修复 2 & 3】在更新后的目标坐标播放音效，彻底解决“错位幽灵声”和“外面听到内部声”的问题
        playTardisSound(server, data, DMSounds.MAT, 1.0f, 1.0f);
        return true;
    }

    public static boolean startCrash(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        data.clearDestination();
        data.setThrottle(0);

        // 坠机同样是立即就地放置门
        applyDestination(server, data);
        placeExteriorDoor(server, data);

        data.setState(TardisState.LANDING, CRASH_TICKS);
        TardisManager.getRegistry(server).setDirty();

        playTardisSound(server, data, DMSounds.MAT, 1.4f, 0.6f);
        return true;
    }

    public static void tick(MinecraftServer server, TardisData data) {
        TardisState s = data.state();
        int t = data.stateTicks() - 1;

        switch (s) {
            case TAKEOFF -> {
                int elapsed = TAKEOFF_TICKS - t;
                if (elapsed == FADE_START_DELAY) {
                    triggerExteriorFade(server, data);
                }
                if (elapsed == FADE_START_DELAY + TardisDoorBlockEntity.FADE_DURATION) {
                    removeExteriorDoor(server, data);
                }

                if (t <= 0) {
                    data.setState(TardisState.FLYING, 0);
                    TardisManager.getRegistry(server).setDirty();
                } else {
                    data.setStateTicks(t);
                }
            }
            case FLYING -> {
                tickMovement(server, data);

                if (data.throttle() == 0) {
                    startLanding(server, data);
                    TardisManager.getRegistry(server).setDirty();
                    return;
                }

                if (t <= 0) {
                    playTardisSound(server, data, DMSounds.FLY, 0.8f, 1.0f);
                    data.setStateTicks(FLYING_SOUND_INTERVAL);
                } else {
                    data.setStateTicks(t);
                }
            }
            case LANDING -> {
                // 门已经提前放好了，这里只需要等待降落倒计时结束即可
                if (t <= 0) {
                    data.setState(TardisState.LANDED, 0);
                    data.setThrottle(0);
                    TardisManager.getRegistry(server).setDirty();
                } else {
                    data.setStateTicks(t);
                }
            }
            default -> {}
        }
    }

    private static void applyDestination(MinecraftServer server, TardisData data) {
        Direction landingFacing = LandingFacingBehavior.targetFacing(data);
        if (data.hasDestination()) {
            data.setExterior(data.targetDim(), data.targetPos(), landingFacing);
            data.clearDestination();
        } else {
            data.setExterior(data.exteriorDim(), data.exteriorPos(), landingFacing);
        }
        TardisManager.getRegistry(server).setDirty();
    }

    // ================================================================
    //                      外壳门动画与生成
    // ================================================================

    private static void triggerExteriorFade(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level != null && level.getBlockEntity(data.exteriorPos()) instanceof TardisDoorBlockEntity be) {
            be.startFadeOut();
        }
    }

    private static void removeExteriorDoor(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        BlockState lowerState = level.getBlockState(lower);
        if (lowerState.getBlock() instanceof AbstractTardisDoorBlock) {
            level.setBlock(lower, Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
        }
        BlockState upperState = level.getBlockState(lower.above());
        if (upperState.getBlock() instanceof AbstractTardisDoorBlock) {
            level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
        }
    }

    private static void placeExteriorDoor(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        Direction facing = data.exteriorFacing();

        BlockState lowerState = DMBlocks.TARDIS_EXTERIOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, facing)
                .setValue(AbstractTardisDoorBlock.OPEN, false);

        level.setBlock(lower, lowerState, 3);
        level.setBlock(lower.above(), lowerState.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        if (level.getBlockEntity(lower) instanceof TardisDoorBlockEntity be) {
            be.setTardisId(data.id());
            be.setAppearance(data.appearanceId());
            be.setExterior(true);
            be.startFadeIn(); // 这里开始淡入，耗时 60 tick，彻底满足降落实体提前现形
        }
    }

    // ================================================================
    //                      音效播放
    // ================================================================

    private static void playTardisSound(MinecraftServer server, TardisData data, SoundEvent sound, float volume, float pitch) {
        // 1. 内部维度音效
        ServerLevel interior = TardisManager.getInteriorLevel(server, data);
        if (interior != null) {
            BlockPos center = data.interiorPos();
            interior.playSound(null, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
        }
        // 2. 外部维度音效（严格使用更新后的 exteriorPos 播放）
        if (data.exteriorDim() != null && data.exteriorPos() != null) {
            ServerLevel exterior = server.getLevel(data.exteriorDim());
            if (exterior != null) {
                BlockPos center = data.exteriorPos();
                exterior.playSound(null, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
            }
        }
    }
}