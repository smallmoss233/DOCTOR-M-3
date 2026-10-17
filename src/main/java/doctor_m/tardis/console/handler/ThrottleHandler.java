package doctor_m.tardis.console.handler;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.TardisState;
import doctor_m.tardis.console.ClickType;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlBehavior;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 油门控件。
 *
 * <p>档位同时管两件事：
 * <ul>
 *   <li><b>起飞触发</b>：LANDED 时档位从 0 变正数 → {@link TardisManager#startTakeoff}
 *       （驻车制动拉起时被拦截，见 {@code ParkingBrakeBehavior}）</li>
 *   <li><b>飞行速度</b>：档位写入 {@link TardisData#setThrottle}，
 *       实际航速再被手刹档位折减（见 {@code TardisFlight.speedOf}）</li>
 * </ul>
 *
 * <p>档位归零 + 处于 FLYING → 触发降落。
 */
public final class ThrottleHandler implements ControlBehavior {

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              ClickType click, int oldTier, int newTier) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        // 1. 更新档位（速度）
        data.setThrottle(newTier);

        // 2. 从 0 → 正数 且处于 LANDED → 触发起飞
        //    驻车制动拉起时禁止起飞（油门仍然记录，松开制动后按 ParkingBrakeBehavior 处理）
        if (oldTier == 0 && newTier > 0 && data.state() == TardisState.LANDED) {
            if (data.parkingBrake()) return;
            TardisManager.startTakeoff(server, data);
            return;
        }

        // 3. 归零且处于 FLYING → 触发降落
        //    （FLYING tick 里也会兜底检查，这里只是即时响应，不重复触发）
        if (newTier == 0 && data.state() == TardisState.FLYING) {
            TardisManager.startLanding(server, data);
        }
    }

    @Override
    public boolean canInteract(ServerPlayer player, ConsoleControlEntity entity, TardisData data) {
        // 降落过程中锁死控件，其余状态允许操作。
        return data.state() != TardisState.LANDING;
    }
}