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

/**
 * TARDIS 飞行系统：包含状态机流转（起飞/降落/坠机）、位移计算、以及外壳动画与音效。
 */
public final class TardisFlight {

    private TardisFlight() {}

    // ================================================================
    //                      状态系统常量
    // ================================================================

    /**
     * 起飞时长 = DEMAT 音效的"有效时长"（18 秒）。
     * 音频文件本身 21 秒，但末尾约 3 秒没有内容，砍掉后正好无缝衔接飞行循环声。
     */
    private static final int TAKEOFF_TICKS = 360;
    /**
     * 降落时长 = MAT 音效的"有效时长"（20 秒）。
     * 音频文件本身 23 秒，末尾约 3 秒静音，忽略。
     */
    private static final int LANDING_TICKS = 400;
    /** 坠机时长。比正常降落短得多 —— 强制就地砸下来。 */
    private static final int CRASH_TICKS = 100;
    /** 飞行循环音效间隔 = FLY 音效时长。 */
    private static final int FLYING_SOUND_INTERVAL = 50;
    /** 起飞后多久开始淡出门（tick）。前 30 tick 用于关门动画。 */
    private static final int FADE_START_DELAY = 30;

    /**
     * 静默方块操作标记：同步到客户端（2）+ 压掉掉落物（16，走 movedByPiston 语义），
     * 刻意不含邻居更新（1）—— 起飞拆外壳时不会触发
     * AbstractTardisDoorBlock#onRemove 的连锁反应（掉另一半、关门声、破坏粒子），
     * 外面的人看到的就是外壳淡出后安静消失。
     */
    private static final int SILENT_FLAGS = 2 | 16;

    // ================================================================
    //                      速度与位移计算
    // ================================================================

    /** 档位对应的速度（格/tick）。档位 ≤ 0 返回 0。 */
    public static double speedOf(int throttle) {
        return speedOf(throttle, 0);
    }

    /**
     * 档位 + 手刹对应的速度（格/tick）。油门 ≤ 0 返回 0。
     *
     * <p>手刹每档折半：{@code base / 2^handbrake}（手刹 3 档 = 1/8 速）。
     */
    public static double speedOf(int throttle, int handbrake) {
        if (throttle <= 0) return 0;
        // 2 × 2^tier：1→4, 2→8, 3→16, 4→32
        double base = 2.0 * (1 << throttle);
        return handbrake > 0 ? base / (1 << Math.min(handbrake, 3)) : base;
    }

    /**
     * 位移处理：更新 {@code exteriorPos} 朝目的地移动。
     * 只在状态机流转的 FLYING 阶段被调用。
     */
    private static void tickMovement(MinecraftServer server, TardisData data) {
        int throttle = data.throttle();
        double speed = speedOf(throttle, data.handbrake());
        if (speed <= 0) return;
        if (!data.hasDestination()) return;

        // 跨维度暂不处理：位置只在本维度有意义，直接返回等玩家手动切。
        if (!data.exteriorDim().equals(data.targetDim())) return;

        BlockPos current = data.exteriorPos();
        BlockPos target  = data.targetPos();

        Vec3 from = Vec3.atCenterOf(current);
        Vec3 to   = Vec3.atCenterOf(target);
        Vec3 delta = to.subtract(from);
        double distance = delta.length();

        if (distance < 1e-4) return;

        Vec3 step;
        if (distance <= speed) {
            // 本 tick 能到 → 吸附到位，避免高速越过目标来回震荡
            step = delta;
        } else {
            step = delta.normalize().scale(speed);
        }

        Vec3 next = from.add(step);
        BlockPos nextPos = BlockPos.containing(next);

        // 位置变了才写，避免无意义 dirty 标记
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

        data.setState(TardisState.LANDING, LANDING_TICKS);
        TardisManager.getRegistry(server).setDirty();

        playTardisSound(server, data, DMSounds.MAT, 1.0f, 1.0f);
        return true;
    }

    public static boolean startCrash(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        data.clearDestination();
        data.setThrottle(0);
        data.setState(TardisState.LANDING, CRASH_TICKS);
        TardisManager.getRegistry(server).setDirty();

        // MAT 低音调 = 坠毁感
        playTardisSound(server, data, DMSounds.MAT, 1.4f, 0.6f);
        return true;
    }

    /**
     * 状态机主 tick。同时包含位移计算、动画过渡与音效。
     * （原先的 TardisFlight.tick 与 TardisFlightManager.tickFlight 合并于此）
     */
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
                    // stateTicks=0 → 下一个 FLYING tick 立刻播第一声 FLY，
                    // DEMAT 有效内容（18s）结束的瞬间无缝衔接飞行循环声
                    data.setState(TardisState.FLYING, 0);
                    TardisManager.getRegistry(server).setDirty();
                } else {
                    data.setStateTicks(t);
                }
            }
            case FLYING -> {
                // 1. 先跑位移
                tickMovement(server, data);

                // 2. 油门归零 → 触发降落
                if (data.throttle() == 0) {
                    startLanding(server, data);
                    TardisManager.getRegistry(server).setDirty();
                    return;
                }

                // 3. 循环音效
                if (t <= 0) {
                    playTardisSound(server, data, DMSounds.FLY, 0.8f, 1.0f);
                    data.setStateTicks(FLYING_SOUND_INTERVAL);
                } else {
                    data.setStateTicks(t);
                }
            }
            case LANDING -> {
                if (t <= 0) {
                    applyDestination(server, data);
                    placeExteriorDoor(server, data);
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
            be.startFadeIn();
        }
    }

    // ================================================================
    //                      音效播放
    // ================================================================

    private static void playTardisSound(MinecraftServer server, TardisData data, SoundEvent sound, float volume, float pitch) {
        ServerLevel interior = TardisManager.getInteriorLevel(server, data);
        if (interior != null) {
            BlockPos center = data.interiorPos();
            interior.playSound(null, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
        }
        if (data.exteriorDim() != null && data.exteriorPos() != null) {
            ServerLevel exterior = server.getLevel(data.exteriorDim());
            if (exterior != null) {
                BlockPos center = data.exteriorPos();
                exterior.playSound(null, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
            }
        }
    }
}