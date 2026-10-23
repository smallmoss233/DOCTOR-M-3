package doctor_m.tardis.console.control;

/**
 * 挡位到顶后的行为。
 *
 * <p>大多数控件是 {@link #CLAMP}：油门推到最大就停在最大，再点不动作。
 * 模式切换旋钮之类才需要 {@link #CYCLE}。
 *
 * <p>挡位折算逻辑从原来的 {@code ConsoleControlHandler.nextTier} 移到这里 ——
 * 每个模式自己负责把目标挡位折算进合法区间，消除 switch 里的 if/else 分支。
 */
public enum TierMode {
    /** 到顶后不再变化，也不播放动画。 */
    CLAMP {
        @Override public int apply(int target, int maxTier) {
            return Math.max(0, Math.min(target, maxTier));
        }
    },
    /** 到顶后回到 0（循环）。 */
    CYCLE {
        @Override public int apply(int target, int maxTier) {
            int range = maxTier + 1;
            return ((target % range) + range) % range;
        }
    };

    /**
     * 把目标挡位折算进 {@code [0, maxTier]}。
     *
     * @param target  折算前的目标挡位（可能越界）
     * @param maxTier 最大合法挡位（{@code tiers - 1}，恒 >= 0）
     */
    public abstract int apply(int target, int maxTier);
}
