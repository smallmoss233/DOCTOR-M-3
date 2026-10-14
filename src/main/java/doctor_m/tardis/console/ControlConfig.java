package doctor_m.tardis.console;

import org.jetbrains.annotations.Nullable;

/**
 * 单个控件的配置。
 *
 * <p>外观 JSON 的 {@code controls} 字段可以写一部分，其余走命名约定。
 *
 * @param type        控件类型
 * @param tiers       挡位数（1 = 单档按钮）
 * @param duration    动画总时长（秒）。每个挡位均匀占据 {@code duration / (tiers-1)} 秒
 * @param tierMode    到顶后的行为
 * @param animationId 该控件使用的动画片段名。null 时走命名约定：
 *                    骨骼 {@code ctrl_<x>} → 片段 {@code <x>}
 */
public record ControlConfig(
        ControlType type,
        int tiers,
        float duration,
        TierMode tierMode,
        @Nullable String animationId
) {
    public ControlConfig {
        if (tiers < 1) tiers = 1;
        if (duration <= 0f) duration = 0.01f;
    }

    /** 用类型的默认参数构造。 */
    public static ControlConfig of(ControlType type) {
        return type.defaultConfig();
    }

    /** 覆盖动画名。 */
    public ControlConfig withAnimation(String animationId) {
        return new ControlConfig(type, tiers, duration, tierMode, animationId);
    }

    /** 每档对应的动画时间步长（秒）。 */
    public float stepDuration() {
        return tiers <= 1 ? 0f : duration / (tiers - 1);
    }

    /** 第 {@code tier} 档对应的动画时间点（秒）。 */
    public float timeForTier(int tier) {
        if (tiers <= 1) return 0f;
        int clamped = Math.max(0, Math.min(tier, tiers - 1));
        return clamped * stepDuration();
    }
}