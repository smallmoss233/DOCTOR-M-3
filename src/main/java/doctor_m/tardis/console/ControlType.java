package doctor_m.tardis.console;

/**
 * 一种控件类型。
 *
 * <h2>为什么不是枚举</h2>
 * 枚举要求每个类型在编译期就定义好，加新控件必须改枚举源码。改成可实例化
 * 的对象后，控件类型可以：
 * <ul>
 *   <li>在 {@link ControlTypes} 里集中注册</li>
 *   <li>由别的模组注册自己的类型</li>
 *   <li>携带各自的业务回调，无需 switch 分发</li>
 * </ul>
 *
 * <h2>id 与骨骼名</h2>
 * {@code id} 是 {@code ctrl_<id>} 的后缀：{@code "throttle"} 对应骨骼
 * {@code ctrl_throttle}。见 {@link ConsoleBoneMatcher}。
 */
public final class ControlType {

    private final String id;
    private final int defaultTiers;
    private final float defaultDuration;
    private final TierMode defaultTierMode;
    private ControlBehavior behavior = ControlBehavior.NONE;

    public ControlType(String id, int defaultTiers, float defaultDuration, TierMode defaultTierMode) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id must not be empty");
        if (defaultTiers < 1) throw new IllegalArgumentException("tiers must be >= 1");
        if (defaultDuration <= 0f) throw new IllegalArgumentException("duration must be > 0");

        this.id = id;
        this.defaultTiers = defaultTiers;
        this.defaultDuration = defaultDuration;
        this.defaultTierMode = defaultTierMode == null ? TierMode.CLAMP : defaultTierMode;
    }

    /** 挂上业务回调；返回 this 便于链式调用。 */
    public ControlType behavior(ControlBehavior behavior) {
        this.behavior = behavior == null ? ControlBehavior.NONE : behavior;
        return this;
    }

    public String id() { return id; }
    public int defaultTiers() { return defaultTiers; }
    public float defaultDuration() { return defaultDuration; }
    public TierMode defaultTierMode() { return defaultTierMode; }
    public ControlBehavior behavior() { return behavior; }

    /** 用类型的默认参数构造一份配置。 */
    public ControlConfig defaultConfig() {
        return new ControlConfig(this, defaultTiers, defaultDuration, defaultTierMode, null);
    }

    @Override
    public String toString() {
        return "ControlType[" + id + "]";
    }
}