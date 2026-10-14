package doctor_m.tardis.console;

import doctor_m.tardis.TardisData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一种控件的交互逻辑。
 *
 * <p>每种控件一个子类（{@code ThrottleHandler}、{@code BrakeHandler}…），
 * 由 {@link ControlHandlers} 按 id 索引。加新控件时只做两件事：
 * <ol>
 *   <li>写一个子类，覆写 {@link #onTierChanged}</li>
 *   <li>在 {@link ControlHandlers} 里注册它</li>
 * </ol>
 *
 * <h2>与 {@link ControlType} 的分工</h2>
 * {@code ControlType} 只管<b>数据</b>（tiers / duration / tierMode）；
 * {@code ControlHandler} 只管<b>行为</b>（挡位变化时做什么）。
 * 两者用同一个 id 关联。
 */
public abstract class ControlHandler {

    /**
     * 挡位变化时的业务回调。<b>只在服务端调用。</b>
     *
     * <p>默认空实现——只做动画、没有业务逻辑的控件不用覆写它。
     *
     * @param level   服务端世界
     * @param data    该控件所属的 TARDIS
     * @param entity  控件实体（可读 tier / boneName）
     * @param oldTier 变化前的挡位
     * @param newTier 变化后的挡位
     */
    public void onTierChanged(ServerLevel level, TardisData data,
                              ConsoleControlEntity entity,
                              int oldTier, int newTier) {
        // 默认什么都不做
    }

    /**
     * 交互前置检查。<b>返回 false 则挡位不推进、动画不播放。</b>
     *
     * <p>典型用途：燃料不够、TARDIS 正在飞行、玩家不是主人……默认放行。
     *
     * @return true 表示可以交互
     */
    public boolean canInteract(ServerPlayer player, ConsoleControlEntity entity, TardisData data) {
        return true;
    }
}