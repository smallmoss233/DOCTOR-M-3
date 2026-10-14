package doctor_m.tardis.console;

import doctor_m.tardis.TardisData;
import net.minecraft.server.level.ServerLevel;

/**
 * 控件挡位变化时的业务回调。
 *
 * <p>只在<b>服务端</b>调用。控件类型通过它把挡位变化映射到 TARDIS 的具体行为
 * （起飞 / 降落 / 切换模式……）。
 *
 * <p>用函数式接口而不是抽象类，是为了让"加新控件"只需一行注册：
 * <pre>{@code
 * ControlTypes.register(new ControlType("throttle", 5, 5f, TierMode.CLAMP)
 *         .behavior((level, data, oldTier, newTier) -> data.travel().takeoff()));
 * }</pre>
 */
@FunctionalInterface
public interface ControlBehavior {

    /**
     * @param level   服务端世界
     * @param data    该控件所属的 TARDIS
     * @param oldTier 变化前的挡位
     * @param newTier 变化后的挡位
     */
    void onTierChanged(ServerLevel level, TardisData data, int oldTier, int newTier);

    /** 空行为：控件只有动画、没有业务。 */
    ControlBehavior NONE = (level, data, oldTier, newTier) -> {};
}