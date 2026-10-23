package doctor_m.tardis.console.control.behavior;

import doctor_m.tardis.console.control.ClickType;
import doctor_m.tardis.console.control.ConsoleControlEntity;
import doctor_m.tardis.console.control.ControlBehavior;
import doctor_m.tardis.data.TardisData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 手刹控件。
 *
 * <p>3 档（0 = 松开）。拉得越紧飞行越慢：每拉一档，航速减半 ——
 * {@code 实际航速 = 基础航速 / 2^手刹档位}（折减在 {@code TardisFlight.speedOf} 里做）：
 * <pre>
 *   档位 0 → 全速      档位 2 → 1/4 速
 *   档位 1 → 1/2 速    档位 3 → 1/8 速
 * </pre>
 *
 * <p>任何状态都可以操作（落地时预设、飞行中随时拉紧），只写状态不触发事件。
 */
public final class HandbrakeBehavior implements ControlBehavior {

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              @Nullable ServerPlayer player,
                              ClickType click, int oldTier, int newTier) {
        data.setHandbrake(newTier);
    }
}
