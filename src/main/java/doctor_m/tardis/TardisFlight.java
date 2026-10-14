package doctor_m.tardis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * TARDIS 飞行位移的每 tick 处理。
 *
 * <p>只在 {@link TardisState#FLYING} 状态下调用。有目的地时朝目的地走，
 * 没目的地时悬停（速度算出来但没有目标方向）。速度由
 * {@link TardisData#throttle()} 决定。
 *
 * <h2>速度公式</h2>
 * {@code 2 × 2^档位} 格/tick：
 * <pre>
 *   档位 0  →  0（不移动）
 *   档位 1  →  4
 *   档位 2  →  8
 *   档位 3  → 16
 *   档位 4  → 32（满速）
 * </pre>
 */
public final class TardisFlight {

    private TardisFlight() {}

    /** 档位对应的速度（格/tick）。档位 ≤ 0 返回 0。 */
    public static double speedOf(int throttle) {
        if (throttle <= 0) return 0;
        // 2 × 2^tier：1→4, 2→8, 3→16, 4→32
        return 2.0 * (1 << throttle);
    }

    /**
     * 每 tick 调用一次。更新 {@code exteriorPos} 朝目的地移动。
     *
     * <p>不改变 {@link TardisData#state()}——状态转换由
     * {@link TardisManager#tickOne} 处理。
     */
    public static void tick(MinecraftServer server, TardisData data) {
        int throttle = data.throttle();
        double speed = speedOf(throttle);
        if (speed <= 0) return;                    // 熄火 → 悬停

        if (!data.hasDestination()) return;        // 无目的地 → 悬停

        // 跨维度暂不处理：位置只在本维度有意义，直接返回等玩家手动切。
        if (!data.exteriorDim().equals(data.targetDim())) return;

        BlockPos current = data.exteriorPos();
        BlockPos target  = data.targetPos();

        Vec3 from = Vec3.atCenterOf(current);
        Vec3 to   = Vec3.atCenterOf(target);
        Vec3 delta = to.subtract(from);
        double distance = delta.length();

        if (distance < 1e-4) return;               // 已到达

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
}