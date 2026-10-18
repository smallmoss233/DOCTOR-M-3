package doctor_m.tardis.console.handler;

import doctor_m.tardis.TardisManager;
import doctor_m.tardis.console.ClickType;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlBehavior;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 驻车制动控件。2 档开关：0 = 解除，1 = 拉起。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>拉起时</b>：油门推到任何档位都不会起飞（拦截在
 *       {@link ThrottleHandler} 里检查 {@link TardisData#parkingBrake()}）。</li>
 *   <li><b>飞行中拉起 → 坠机</b>：{@link TardisManager#startCrash} 强制就地降落。</li>
 *   <li><b>落地且油门已推起时解除</b> → 立即起飞（与"油门从 0 推起即起飞"的
 *       语义保持一致：制动一松，该发生的事就发生）。</li>
 * </ul>
 */
public final class ParkingBrakeBehavior implements ControlBehavior {

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              @Nullable ServerPlayer player,
                              ClickType click, int oldTier, int newTier) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        boolean engaged = newTier > 0;
        data.setParkingBrake(engaged);

        // 飞行中拉起 → 坠机
        if (engaged && data.state() == TardisState.FLYING) {
            TardisManager.startCrash(server, data);
            return;
        }

        // 落地 + 油门已推起 + 松开制动 → 立即起飞
        if (!engaged && data.state() == TardisState.LANDED && data.throttle() > 0) {
            TardisManager.startTakeoff(server, data);
        }
    }
}
