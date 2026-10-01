package doctor_m.stp;

/** STP 配置常量。 */
public final class StpConfig {

    private StpConfig() {}

    /** 触发预加载的距离（格）。 */
    public static final double PRELOAD_DISTANCE = 8.0;
    public static final double PRELOAD_DISTANCE_SQ = PRELOAD_DISTANCE * PRELOAD_DISTANCE;

    /** 扫描间隔（tick）。 */
    public static final int CHECK_INTERVAL = 10;
}