package doctor_m.tardis.console;

/**
 * 挡位到顶后的行为。
 *
 * <p>大多数控件是 {@link #CLAMP}：油门推到最大就停在最大，再点不动作。
 * 模式切换旋钮之类才需要 {@link #CYCLE}。
 */
public enum TierMode {
    /** 到顶后不再变化，也不播放动画。 */
    CLAMP,
    /** 到顶后回到 0（循环）。 */
    CYCLE
}