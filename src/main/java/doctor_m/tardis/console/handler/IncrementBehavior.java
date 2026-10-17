package doctor_m.tardis.console.handler;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.console.ClickType;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlBehavior;
import net.minecraft.server.level.ServerLevel;

/**
 * 数值增量器控件。控制单次点击 Z/Y/X 坐标控件时的数值增量。
 *
 * <p>6 档 CYCLE，每档增量是上一档的 10 倍，最高一次点击 100000：
 * <pre>
 *   档位 0 → 1       档位 3 → 1 000
 *   档位 1 → 10      档位 4 → 10 000
 *   档位 2 → 100     档位 5 → 100 000（上限）
 * </pre>
 *
 * <p>增量存在 {@link TardisData#coordStep()}（随 TARDIS 持久化），
 * 而不是控件实体上 —— 这样坐标控件不需要反查实体，重生成控件后设置也不丢。
 */
public final class IncrementBehavior implements ControlBehavior {

    /** 每档对应的单次点击增量（下标 = 档位）。 */
    public static final int[] STEP_PER_TIER = {1, 10, 100, 1_000, 10_000, 100_000};

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              ClickType click, int oldTier, int newTier) {
        int tier = Math.max(0, Math.min(newTier, STEP_PER_TIER.length - 1));
        data.setCoordStep(STEP_PER_TIER[tier]);
    }
}
