package doctor_m.stp;

import net.minecraft.resources.Identifier;

/** STP 配置常量。集中在这里，避免散落在各处的魔数。 */
public final class StpConfig {

    private StpConfig() {}

    // ============================================================
    //                      服务端触发
    // ============================================================

    /**
     * 触发预加载的距离（格）。
     *
     * <p>这个值本质上决定了"预加载有多少时间预算"。玩家朝门走过去时会先在
     * 这个距离触发排队，然后 {@link StpChunkPump} 在玩家走完这段路的时间里
     * 把区块平稳发完。所以它同时也是"能铺多少区块"的上限：
     * 走完 24 格约 4.3 秒，按 {@link #CHUNK_SEND_PER_TICK} 每 tick 4 个算，
     * 约能铺 340 个区块 —— 足够覆盖半径 8（17×17=289）。
     */
    public static final double PRELOAD_DISTANCE = 24.0;
    public static final double PRELOAD_DISTANCE_SQ = PRELOAD_DISTANCE * PRELOAD_DISTANCE;

    /** 扫描间隔（tick）。5 tick = 0.25 秒，玩家冲刺也只移动约 1.4 格。 */
    public static final int CHECK_INTERVAL = 5;

    /**
     * 出 TARDIS 时预加载主世界落点周围的区块半径。
     *
     * <p>这是"出 TARDIS 卡不卡"最关键的一个数。旧值是 1（只铺 9 个区块）——
     * 而主世界视距内的区块数以百计，9 个区块对卡顿几乎没有任何帮助，
     * 玩家出门后仍然要在原地等其余地形下载。
     *
     * <p>取 8 时铺 289 个区块。配合 {@link #PRELOAD_DISTANCE} = 24 格的行进时间，
     * 可以在玩家走到门前把这一片全部铺完。
     */
    public static final int WORLD_PRELOAD_RADIUS = 8;

    /**
     * 进入 TARDIS 时预加载的区块半径。
     *
     * <p>TARDIS 内部维度本身很小（控制室约 9×11），半径 6 足以把整个房间
     * 连周边一起铺完，进门基本无感。
     */
    public static final int TARDIS_PRELOAD_RADIUS = 6;

    // ============================================================
    //                      发送节流
    // ============================================================

    /**
     * 每个玩家每 tick 最多发多少个区块包。
     *
     * <p>这是"不突发"与"来得及"之间的平衡点：
     * <ul>
     *   <li>太大 → 网络突发，客户端单帧解析过多，掉帧；</li>
     *   <li>太小 → 玩家走到门前还没铺完，白等。</li>
     * </ul>
     * 4 个/tick = 80 个/秒。单个区块包通常几 KB 到几十 KB，即数百 KB/s ~ 数 MB/s，
     * 对现代连接压力不大，而 289 个区块约 3.6 秒铺完。
     */
    public static final int CHUNK_SEND_PER_TICK = 4;

    // ============================================================
    //                      客户端预算
    // ============================================================

    /**
     * 预构建的 ClientLevel 在多长时间内有效（毫秒）。
     *
     * <p>服务端发出 prepare 后若玩家没有真的穿门（掉线、转身走开、被传送走），
     * 这份预构建数据就会一直挂在客户端上。过期后必须丢弃，否则：
     * <ul>
     *   <li>占着内存不放；</li>
     *   <li>更糟的是 —— 它可能在很久以后被错误地换进一个早已过时的场景。</li>
     * </ul>
     * <p>必须大于"从触发预加载到玩家走到门"的最长用时，否则区块还没灌完就过期了。
     */
    public static final long PREPARE_TIMEOUT_MS = 30_000L;

    /**
     * 客户端每 tick 最多把多少个收到的区块写入 ClientChunkCache。
     *
     * <p>区块数据的<b>反序列化</b>发生在网络线程（不占渲染帧），
     * 但写入 {@code ClientChunkCache} 必须回到主线程。如果一帧里写入几百个，
     * 主线程就会卡住 —— 这正是"传送那一瞬间的卡顿"最可能的来源之一。
     *
     * <p>限制每 tick 写入数量，把写入摊到多帧上，用极小的延迟换取帧率平稳。
     */
    public static final int CLIENT_APPLY_PER_TICK = 12;

    /**
     * 等待写入的区块队列上限。超过则丢弃最旧的 ——
     * 积压太多说明网络比主线程快得多，此时保留最新的更有价值。
     */
    public static final int CLIENT_QUEUE_LIMIT = 512;

    // ============================================================
    //                      调试
    // ============================================================

    /** 是否把 STP 的关键节点写进日志（排查"传送有缝"时开）。 */
    public static boolean debugLogging = false;

    /** 统一的日志出口。 */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("doctor_m/STP");

    public static void debug(String format, Object... args) {
        if (debugLogging) LOGGER.info("[STP] " + format, args);
    }

    public static void info(String format, Object... args) {
        LOGGER.info("[STP] " + format, args);
    }

    public static void warn(String format, Object... args) {
        LOGGER.warn("[STP] " + format, args);
    }

    /** 便于把 Identifier 之类直接放进日志。 */
    public static String name(Identifier id) {
        return id == null ? "null" : id.toString();
    }
}
