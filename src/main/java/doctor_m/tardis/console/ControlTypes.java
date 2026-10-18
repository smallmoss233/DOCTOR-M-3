package doctor_m.tardis.console;

import doctor_m.tardis.console.handler.*;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控件类型的<b>唯一</b>注册表。
 *
 * <h2>加新控件</h2>
 * <pre>{@code
 * // 1. 写一个 handler/XxxBehavior.java（实现 ControlBehavior）
 * // 2. 在这里注册一行：
 * public static final ControlType XXX = register(
 *         new ControlType("xxx", 2, 0.5f, TierMode.CLAMP).behavior(new XxxBehavior()));
 * }</pre>
 *
 * <p>别的模组也可以在初始化时调 {@link #register} 注册自己的类型。
 */
public final class ControlTypes {

    private ControlTypes() {}

    private static final Map<String, ControlType> REGISTRY = new LinkedHashMap<>();

    // =========================================================
    //                        内置类型
    // =========================================================

    /** 油门：5 档长行程，带起飞/降落业务（受驻车制动联锁）。 */
    public static final ControlType THROTTLE = register(
            new ControlType("throttle", 5, 5.0f, TierMode.CLAMP)
                    .behavior(new ThrottleHandler()));

    /** 手刹：3 档拉紧 + 0 档松开（共 4 个位置），每拉一档飞行航速减半。 */
    public static final ControlType HANDBRAKE = register(
            new ControlType("handbrake", 4, 1.2f, TierMode.CLAMP)
                    .behavior(new HandbrakeBehavior()));

    /** 驻车制动：2 档开关（拉起禁起飞，飞行中拉起坠机）。 */
    public static final ControlType PARKING_BRAKE = register(
            new ControlType("parking_brake", 2, 0.4f, TierMode.CLAMP)
                    .behavior(new ParkingBrakeBehavior()));

    /** 目的地 X 坐标：2 档 CYCLE 脉冲，每次点击 ±增量。 */
    public static final ControlType COORD_X = register(
            new ControlType("coord_x", 2, 0.15f, TierMode.CYCLE)
                    .behavior(new CoordinateBehavior(CoordinateBehavior.Axis.X)));

    /** 目的地 Y 坐标：同上。 */
    public static final ControlType COORD_Y = register(
            new ControlType("coord_y", 2, 0.15f, TierMode.CYCLE)
                    .behavior(new CoordinateBehavior(CoordinateBehavior.Axis.Y)));

    /** 目的地 Z 坐标：同上。 */
    public static final ControlType COORD_Z = register(
            new ControlType("coord_z", 2, 0.15f, TierMode.CYCLE)
                    .behavior(new CoordinateBehavior(CoordinateBehavior.Axis.Z)));

    /** 数值增量器：6 档 CYCLE，单次点击增量 1 → 100 000（上限）。 */
    public static final ControlType COORD_STEP = register(
            new ControlType("coord_step", 6, 0.8f, TierMode.CYCLE)
                    .behavior(new IncrementBehavior()));

    /** 降落朝向罗盘：4 档 CYCLE，档位 = 外壳下次降落的朝向（北→东→南→西顺时针）。 */
    public static final ControlType LANDING_FACING = register(
            new ControlType("landing_facing", 4, 0.6f, TierMode.CYCLE)
                    .behavior(new LandingFacingBehavior()));

    /** 拉杆：常见 2 档短行程。 */
    public static final ControlType LEVER = register(
            new ControlType("lever", 2, 0.5f, TierMode.CLAMP));

    /** 按钮：单档，按一下。 */
    public static final ControlType BUTTON = register(
            new ControlType("button", 1, 0.2f, TierMode.CLAMP));

    /** 模式旋钮：多档循环。目前无业务逻辑，走 {@link ControlBehavior#NONE}。 */
    public static final ControlType MODE = register(
            new ControlType("mode", 3, 0.6f, TierMode.CYCLE));

    // =========================================================
    //                        注册 / 查询
    // =========================================================

    /** 注册一个类型。id 重复会抛异常。 */
    public static ControlType register(ControlType type) {
        ControlType prev = REGISTRY.putIfAbsent(type.id(), type);
        if (prev != null) {
            throw new IllegalStateException("Duplicate control type id: " + type.id());
        }
        return type;
    }

    /** 按 id 查找；未注册返回 null。 */
    public static @Nullable ControlType byId(String id) {
        return id == null ? null : REGISTRY.get(id);
    }

    /** 已注册的全部类型（不可变视图）。 */
    public static Map<String, ControlType> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}