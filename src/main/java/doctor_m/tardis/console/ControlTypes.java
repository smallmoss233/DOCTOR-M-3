package doctor_m.tardis.console;

import doctor_m.tardis.TardisData;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控件类型的全局注册表。
 *
 * <h2>加新控件只需一行</h2>
 * <pre>{@code
 * public static final ControlType TELEPORT = register(
 *         new ControlType("teleport", 1, 0.3f, TierMode.CLAMP)
 *                 .behavior((level, data, oldTier, newTier) -> data.travel().teleport()));
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

    /** 油门：多档、长行程。 */
    public static final ControlType THROTTLE = register(
            new ControlType("throttle", 5, 5.0f, TierMode.CLAMP)
                    .behavior(ControlTypes::onThrottle));

    /** 拉杆：常见 2 档短行程。 */
    public static final ControlType LEVER = register(
            new ControlType("lever", 2, 0.5f, TierMode.CLAMP));

    /** 按钮：单档，按一下。 */
    public static final ControlType BUTTON = register(
            new ControlType("button", 1, 0.2f, TierMode.CLAMP));

    /** 模式旋钮：多档循环。 */
    public static final ControlType MODE = register(
            new ControlType("mode", 3, 0.6f, TierMode.CYCLE));

    // =========================================================
    //                        注册 / 查询
    // =========================================================

    /** 注册一个类型。id 重复会抛异常。 */
    public static ControlType register(ControlType type) {
        ControlType prev = REGISTRY.putIfAbsent(type.id(), type);
        if (prev != null) {
            throw new IllegalStateException(
                    "Duplicate control type id: " + type.id());
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

    // =========================================================
    //                        内置行为
    // =========================================================

    /**
     * 油门默认行为：挡位越大，行程越"深入"。具体映射由 TARDIS 侧决定。
     *
     * <p>这里只提供"挡位 → 做了什么"的骨架，具体的飞行逻辑（起飞、加速等）
     * 需要 TARDIS 侧有对应 API 后接上。
     */
    private static void onThrottle(ServerLevel level, TardisData data,
                                   int oldTier, int newTier) {
        // TODO: 接上 TARDIS 的飞行状态机后，把挡位映射到 travel().setThrottle(newTier)
        // 目前只是占位。
    }
}