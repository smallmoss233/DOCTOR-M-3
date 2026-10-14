package doctor_m.tardis.console;

import doctor_m.tardis.console.handler.ThrottleHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控件处理器的注册表。
 *
 * <h2>加新控件</h2>
 * <pre>{@code
 * // 1. 写一个 handler/BrakeHandler.java
 * // 2. 在这里注册一行：
 * public static final ControlHandler BRAKE = register("brake", new BrakeHandler());
 * }</pre>
 *
 * <p>id 必须与 {@link ControlTypes} 里对应类型的 id 一致——否则骨骼命名约定
 * 找不到处理器。
 */
public final class ControlHandlers {

    private ControlHandlers() {}

    private static final Map<String, ControlHandler> REGISTRY = new LinkedHashMap<>();

    // =========================================================
    //                        内置处理器
    // =========================================================

    public static final ControlHandler THROTTLE = register("throttle", new ThrottleHandler());
//    public static final ControlHandler LEVER    = register("lever",    new LeverHandler());
//    public static final ControlHandler BUTTON   = register("button",   new ButtonHandler());
    // MODE 暂时没有业务逻辑，用空实现占位

    /** 空实现：只播动画、无业务。 */
    private static final ControlHandler NOOP = new ControlHandler() {};
    public static final ControlHandler MODE = register("mode", NOOP);

    // =========================================================
    //                        注册 / 查询
    // =========================================================

    public static ControlHandler register(String id, ControlHandler handler) {
        if (id == null || handler == null) {
            throw new IllegalArgumentException("id/handler must not be null");
        }
        ControlHandler prev = REGISTRY.putIfAbsent(id, handler);
        if (prev != null) {
            throw new IllegalStateException("Duplicate control handler id: " + id);
        }
        return handler;
    }

    /** 按 id 查找；未注册返回 null。 */
    public static @Nullable ControlHandler get(String id) {
        return id == null ? null : REGISTRY.get(id);
    }

    public static Map<String, ControlHandler> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}