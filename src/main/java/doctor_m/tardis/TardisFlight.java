package doctor_m.tardis;

import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.register.DMSounds;
import doctor_m.tardis.console.control.behavior.LandingFacingBehavior;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisState;
import doctor_m.tardis.door.TardisDoorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * 飞行状态机。
 *
 * <h2>状态与时序</h2>
 * <pre>
 *   LANDED ──油门>0──► TAKEOFF(360t) ──倒计时结束──► FLYING
 *                                                      │
 *          ┌────────────油门==0───────────────┐         │
 *          ▼                                  │         │
 *      LANDING(400t) ◄────────────────────────┴─────────┘
 *          │
 *          └──倒计时结束──► LANDED(油门归零)
 *
 *   FLYING + 驻车制动拉起 ──► LANDING(100t)（坠机）
 * </pre>
 *
 * <h2>为什么降落一开始就放门</h2>
 * MAT 音效长达 20 秒。若等倒计时结束才放外壳，玩家会先听到声音、后看到门凭空出现。
 * 所以在 {@code startLanding} 里就结算落点坐标 + 放门 + 启动淡入，
 * 音效播放期间门逐渐显形。
 *
 * <h2>可见副作用在哪</h2>
 * 方块与声音都在 {@link TardisFlightEffects} —— 本类只管时序与状态流转。
 */
public final class TardisFlight {

    private TardisFlight() {}

    // =========================================================
    //                      时序常量
    // =========================================================

    /** 起飞时长（tick）= DEMAT 音效时长 18 秒。 */
    private static final int TAKEOFF_TICKS = 360;
    /** 降落时长（tick）= MAT 音效时长 20 秒。 */
    private static final int LANDING_TICKS = 400;
    /** 坠机时长（tick）：比正常降落短得多。 */
    private static final int CRASH_TICKS = 100;
    /** 飞行循环音效间隔（tick）。 */
    private static final int FLYING_SOUND_INTERVAL = 50;
    /** 起飞后多久开始淡出外壳（前面的时间留给关门动画）。 */
    private static final int FADE_START_DELAY = 30;

    // =========================================================
    //                      速度
    // =========================================================

    /**
     * 当前航速（格/tick）。
     *
     * <p>油门每档翻倍；手刹每拉一档减半（最多按 3 档计）。
     *
     * @param throttle  油门档位（0 = 停）
     * @param handbrake 手刹档位
     */
    public static double speedOf(int throttle, int handbrake) {
        if (throttle <= 0) return 0;
        double base = 2.0 * (1 << throttle);
        return handbrake > 0 ? base / (1 << Math.min(handbrake, 3)) : base;
    }

    /** 每个 tick 朝目的地推进一次（同一维度内才移动）。 */
    private static void tickMovement(MinecraftServer server, TardisData data) {
        double speed = speedOf(data.throttle(), data.handbrake());
        if (speed <= 0) return;
        if (!data.hasDestination()) return;
        if (!data.exteriorDim().equals(data.targetDim())) return;

        BlockPos current = data.exteriorPos();
        Vec3 from = Vec3.atCenterOf(current);
        Vec3 to = Vec3.atCenterOf(data.targetPos());
        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        if (distance < 1e-4) return;

        Vec3 step = (distance <= speed) ? delta : delta.normalize().scale(speed);
        BlockPos nextPos = BlockPos.containing(from.add(step));

        if (!nextPos.equals(current)) {
            data.setExterior(data.exteriorDim(), nextPos, data.exteriorFacing());
        }
    }

    // =========================================================
    //                      状态入口
    // =========================================================

    public static boolean startTakeoff(MinecraftServer server, TardisData data) {
        if (data.isInteriorChanging()) return false;
        if (data.state() != TardisState.LANDED) return false;

        TardisDoorManager.setDoorOpen(server, data, false);
        data.setState(TardisState.TAKEOFF, TAKEOFF_TICKS);
        TardisManager.getRegistry(server).setDirty();

        TardisFlightEffects.playSound(server, data, DMSounds.DEMAT, 1.0f, 1.0f);
        return true;
    }

    public static boolean startLanding(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        // 立刻结算落点，让后续音效与门都发生在最终位置
        applyDestination(server, data);
        TardisFlightEffects.placeExterior(server, data);

        data.setState(TardisState.LANDING, LANDING_TICKS);
        TardisManager.getRegistry(server).setDirty();

        TardisFlightEffects.playSound(server, data, DMSounds.MAT, 1.0f, 1.0f);
        return true;
    }

    public static boolean startCrash(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        data.clearDestination();
        data.setThrottle(0);

        applyDestination(server, data);
        TardisFlightEffects.placeExterior(server, data);

        data.setState(TardisState.LANDING, CRASH_TICKS);
        TardisManager.getRegistry(server).setDirty();

        TardisFlightEffects.playSound(server, data, DMSounds.MAT, 1.4f, 0.6f);
        return true;
    }

    // =========================================================
    //                      每 tick 推进
    // =========================================================

    public static void tick(MinecraftServer server, TardisData data) {
        int t = data.stateTicks() - 1;
        switch (data.state()) {
            case TAKEOFF -> tickTakeoff(server, data, t);
            case FLYING  -> tickFlying(server, data, t);
            case LANDING -> tickLanding(server, data, t);
            default      -> {}
        }
    }

    /** 起飞：中途淡出外壳，倒计时结束转入 FLYING。 */
    private static void tickTakeoff(MinecraftServer server, TardisData data, int remaining) {
        int elapsed = TAKEOFF_TICKS - remaining;

        if (elapsed == FADE_START_DELAY) {
            TardisFlightEffects.startFadeOut(server, data);
        }
        // 淡出动画播完才真正移除方块
        if (elapsed == FADE_START_DELAY + TardisDoorBlockEntity.FADE_DURATION) {
            TardisFlightEffects.removeExterior(server, data);
        }

        if (remaining > 0) {
            data.setStateTicks(remaining);
            return;
        }
        data.setState(TardisState.FLYING, 0);
        TardisManager.getRegistry(server).setDirty();
    }

    /** 飞行：推进位移、按油门决定降落、循环播放飞行音效。 */
    private static void tickFlying(MinecraftServer server, TardisData data, int remaining) {
        tickMovement(server, data);

        // 油门归零 → 立即降落（不等倒计时）
        if (data.throttle() == 0) {
            startLanding(server, data);
            TardisManager.getRegistry(server).setDirty();
            return;
        }

        if (remaining > 0) {
            data.setStateTicks(remaining);
            return;
        }
        TardisFlightEffects.playSound(server, data, DMSounds.FLY, 0.8f, 1.0f);
        data.setStateTicks(FLYING_SOUND_INTERVAL);
    }

    /** 降落：门在进入 LANDING 时已放好，这里只需等倒计时。 */
    private static void tickLanding(MinecraftServer server, TardisData data, int remaining) {
        if (remaining > 0) {
            data.setStateTicks(remaining);
            return;
        }
        data.setState(TardisState.LANDED, 0);
        data.setThrottle(0);
        TardisManager.getRegistry(server).setDirty();
    }

    // =========================================================
    //                      落点结算
    // =========================================================

    /**
     * 把目的地结算进 {@code exteriorPos}，并采用降落朝向罗盘指定的朝向。
     *
     * <p>没有目的地时"原地降落"，但仍会应用罗盘朝向。
     */
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
}
