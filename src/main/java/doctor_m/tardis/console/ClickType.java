package doctor_m.tardis.console;

/**
 * 控件交互类型。
 *
 * <p>原先是 {@code ConsoleControlHandler} 的嵌套枚举；坐标类控件的行为需要
 * 知道点击方向，{@code ControlBehavior} 也要引用它，提升为顶层类型。
 */
public enum ClickType {
    RIGHT,          // 右键：挡位 +1
    SNEAK_RIGHT,    // 潜行右键：直接拉到最大
    LEFT,           // 左键：挡位 -1
    SNEAK_LEFT;     // 潜行左键：直接拉到最小

    /** 方向语义：右键 = +1，左键 = -1（潜行变体同向）。坐标类控件用。 */
    public int direction() {
        return (this == RIGHT || this == SNEAK_RIGHT) ? 1 : -1;
    }
}
