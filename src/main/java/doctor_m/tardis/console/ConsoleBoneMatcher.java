package doctor_m.tardis.console;

import doctor_m.tardis.console.control.ControlConfig;
import doctor_m.tardis.console.control.ControlType;
import doctor_m.tardis.console.control.ControlTypes;
import org.jetbrains.annotations.Nullable;

/**
 * 骨骼名 → 控件配置的解析器。
 *
 * <h2>命名约定</h2>
 * 骨骼名前缀 {@code ctrl_}，后缀是 {@link ControlType#id()}：
 * <pre>
 *   ctrl_throttle  → throttle
 *   ctrl_lever     → lever
 *   ctrl_button    → button
 * </pre>
 *
 * <h2>解析优先级</h2>
 * <ol>
 *   <li>外观 JSON 的 {@code controls} 里显式配置的 → 用它</li>
 *   <li>没配置但骨骼名符合 {@code ctrl_<id>} 且该 id 已注册 → 用该类型的默认参数</li>
 *   <li>都不满足 → 不是控件</li>
 * </ol>
 */
public final class ConsoleBoneMatcher {

    public static final String PREFIX = "ctrl_";
    private static final int PREFIX_LENGTH = PREFIX.length();

    private ConsoleBoneMatcher() {}

    /** 骨骼名是否符合控件命名约定。 */
    public static boolean isControlBone(String boneName) {
        return boneName != null
                && boneName.startsWith(PREFIX)
                && boneName.length() > PREFIX_LENGTH;
    }

    /** 从骨骼名提取 type id；不符合约定返回 null。 */
    public static @Nullable String typeId(String boneName) {
        return isControlBone(boneName) ? boneName.substring(PREFIX_LENGTH) : null;
    }

    /**
     * 综合解析一个骨骼的控件配置。
     *
     * @return 该骨骼的控件配置；不是控件返回 null
     */
    public static @Nullable ControlConfig resolve(String boneName, TardisConsoleAppearance app) {
        if (boneName == null) return null;

        // 1. 显式配置优先
        if (app != null) {
            ControlConfig explicit = app.controls().get(boneName);
            if (explicit != null) return explicit;
        }

        // 2. 命名约定 → 已注册类型的默认参数
        ControlType type = ControlTypes.byId(typeId(boneName));
        return type == null ? null : type.defaultConfig();
    }

    /**
     * 该控件的动画片段名。
     *
     * @return 片段名；无可解析时返回 null
     */
    public static @Nullable String animationNameFor(String boneName, ControlConfig config) {
        if (config != null && config.animationId() != null) {
            return config.animationId();
        }
        return boneName;
    }
}
