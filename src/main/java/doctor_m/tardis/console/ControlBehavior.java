package doctor_m.tardis.console;

import doctor_m.tardis.TardisData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 控件的行为（合并自原 {@code ControlBehavior} + 抽象类 {@code ControlHandler}）。
 *
 * <p>合并后的接口既是 {@code @FunctionalInterface}（保留 lambda 一行注册），
 * 又通过 default 方法支持覆写 {@link #canInteract}。
 *
 * <h2>加新控件</h2>
 * <pre>{@code
 * public static final ControlType TELEPORT = ControlTypes.register(
 *         new ControlType("teleport", 1, 0.3f, TierMode.CLAMP)
 *                 .behavior((level, data, entity, player, click, oldTier, newTier) -> {
 *                     // 挡位变化时做什么
 *                 }));
 * }</pre>
 *
 * <h2>回调参数</h2>
 * <ul>
 *   <li>{@code entity} —— 触发变化的控件实体（可读骨骼名 / 挡位）</li>
 *   <li>{@code player} —— 触发交互的玩家。用于动作栏 / 聊天反馈；
 *       广播类回调（如多控制台同步）里它只是"操作者"，不是唯一的观察者</li>
 *   <li>{@code click}  —— 本次交互类型。坐标类控件靠它区分加/减；
 *       普通控件用不到时忽略即可（挡位变化本身已含方向信息）</li>
 * </ul>
 */
@FunctionalInterface
public interface ControlBehavior {

    /**
     * 挡位变化时的业务回调。<b>只在服务端调用。</b>
     *
     * @param level   服务端世界
     * @param data    该控件所属的 TARDIS
     * @param entity  触发变化的控件实体
     * @param player  触发交互的玩家
     * @param click   本次交互类型（右/左、是否潜行）
     * @param oldTier 变化前的挡位
     * @param newTier 变化后的挡位
     */
    void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                       @Nullable ServerPlayer player,
                       ClickType click, int oldTier, int newTier);

    /**
     * 交互前置检查。<b>返回 false 则挡位不推进、动画不播放。</b>
     *
     * <p>典型用途：燃料不够、TARDIS 正在飞行、玩家不是主人……默认放行。
     *
     * @return true 表示可以交互
     */
    default boolean canInteract(ServerPlayer player, ConsoleControlEntity entity, TardisData data) {
        return true;
    }

    /** 空行为：控件只有动画、没有业务。 */
    ControlBehavior NONE = (level, data, entity, player, click, oldTier, newTier) -> {};
}
