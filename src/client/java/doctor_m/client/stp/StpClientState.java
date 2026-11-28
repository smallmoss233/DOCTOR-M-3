package doctor_m.client.stp;

import doctor_m.client.mixin.ClientPacketListenerAccessor;
import doctor_m.stp.StpConfig;
import doctor_m.stp.StpPackets;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/**
 * STP 客户端状态机。
 *
 * <h2>目标</h2>
 * 穿门时不出现加载界面、不出现地形空洞：需要的一切在玩家跨过门前就已就绪。
 *
 * <h2>三个阶段</h2>
 * <ol>
 * <li><b>prepare</b>：服务端在玩家靠近门时下发目标维度信息与区块数据。客户端据此
 * 预构建一个 {@link ClientLevel}，并把区块（含光照）灌进它的 {@code ClientChunkCache}
 * 与 {@code LevelLightEngine}。</li>
 * <li><b>换场</b>：玩家跨门时服务端发来原版维度切换包（{@code handleRespawn}），
 * Mixin 拦截其中新建 {@code ClientLevel} 与 {@code ClientLevelData} 的两处调用，
 * 换成预构建实例，并抑制加载界面。</li>
 * <li><b>复用</b>：预构建实例被换入后立即失效，等待下一轮 prepare。</li>
 * </ol>
 *
 * <h2>光照落库的完整链条（对齐原版 {@code handleLevelChunkWithLight}）</h2>
 * <ol>
 * <li>{@code replaceWithPacketData} 写入方块；</li>
 * <li>{@code queueSectionData} 把光照层<b>入队</b>（不是落库！）；</li>
 * <li>{@code updateSectionStatus} 登记 section 非空状态——这是传播引擎
 * 处理该 section 的前提，漏掉则引擎当它是空的直接跳过；</li>
 * <li>{@code setLightEnabled} 打开该区块的光照；</li>
 * <li>{@code runLightUpdates()} 泵干任务队列——预构建 level 是休眠的，
 * 没有原版的每 tick 泵送，必须手动排空，光照才真正落进存储。</li>
 * </ol>
 * 任何一环缺失，表现都是方块几何正常但光照异常。
 */
public final class StpClientState {

    private StpClientState() {}

    // ============================================================
    // 预构建实例
    // ============================================================

    /** 一份预构建结果：与其生效条件绑在一起，不一致就整体作废。 */
    private static final class Prepared {
        final UUID sessionId;
        /** 实际解析出的维度 key —— 必须与换场时请求的维度一致。 */
        final ResourceKey<Level> dimension;
        final ResourceKey<DimensionType> dimensionType;
        final ClientLevel level;
        final ClientLevel.ClientLevelData levelData;
        long createdAtMs;

        /**
         * 对侧维度的 dayTime 快照（prepare 时刻的累计 tick）；-1 = 服务端未同步
         * （老协议兜底，渲染端按白天处理）。
         *
         * <p>为什么渲染端需要它：lightmap 是全局唯一纹理、由玩家所在维度驱动——
         * 玩家站在 {@code fixed_time: 6000} 的内饰里，主 lightmap 恒为正午曲线，
         * FBO 借用原版管线时对侧的天光区域（sky=15）恒亮。prepared level 自己的
         * levelData 时间是默认值（没同步过），救不了场；所以这里存服务端给的
         * <b>目标维度</b>时间，门户渲染端拿它自行计算昼夜压暗
         * （见 {@code PortalViewManager.computeSkyFactor}），绕过 lightmap 的
         * 时间源错位。
         *
         * <p>非 final：reusing 路径要刷新快照，否则首次 prepare 之后时间冻结。
         * volatile：写入发生在包处理线程，读取发生在渲染线程，可见性廉价买断。
         */
        volatile long dayTime = -1;

        Prepared(UUID sessionId, ResourceKey<Level> dimension, ResourceKey<DimensionType> dimensionType,
                 ClientLevel level, ClientLevel.ClientLevelData levelData) {
            this.sessionId = sessionId;
            this.dimension = dimension;
            this.dimensionType = dimensionType;
            this.level = level;
            this.levelData = levelData;
            this.createdAtMs = System.currentTimeMillis();
        }

        boolean isExpired(long nowMs) {
            return nowMs - createdAtMs > StpConfig.PREPARE_TIMEOUT_MS;
        }

        /** 门户渲染仍在使用这份 level 时刷新时间戳，避免被过期逻辑清掉。 */
        void touch() {
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 取当前预构建的对侧 ClientLevel（不消费）。门户渲染专用。
     *
     * <p>对偶语义：站在门外 → prepared 是内饰维度；站在门内 → prepared 是外维度。
     * 「prepared 就是对面」这个不变量恒成立，无需额外维度查询。
     * 返回 null 表示当前没有可用的对侧数据（门未接近 / 已消费 / 已过期），门户隐藏。
     */
    public static ClientLevel portalLevel() {
        Prepared p = prepared;
        return p == null ? null : p.level;
    }

    /**
     * 对侧维度当前 dayTime 快照（prepare 时刻的累计 tick）。
     * -1 = 服务端未同步（老协议/未改包），渲染端按白天处理。
     *
     * <p>注意语义：这是 prepare 那一刻的快照，不是实时钟——新鲜度取决于服务端
     * 重发 prepare 的频率。长时间盯着门看时间不动，是服务端侧的心跳问题，
     * 不是这里的 bug。
     */
    public static long portalDayTime() {
        Prepared p = prepared;
        return p == null ? -1 : p.dayTime;
    }

    private static volatile Prepared prepared = null;

    /** 抑制下一次加载界面的意图。 */
    private static volatile boolean suppressNextLoading = false;

    /** 服务端明确要求“立即进入”时置位，与 suppressNextLoading 配合。 */
    private static volatile boolean skipLoadingThisRespawn = false;

    // ============================================================
    // prepare 包
    // ============================================================

    public static void onPrepare(StpPackets.StpPrepareS2C payload) {
        Prepared current = prepared;

        // 命中条件：同一会话 + 同一维度 + 同一维度类型 + 未过期。
        // 任一不符都重建，绝不把不属于目标维度的实例换进去。
        boolean reusable = current != null
                && !current.isExpired(System.currentTimeMillis())
                && payload.sessionId().equals(current.sessionId)
                && payload.dimensionId().equals(current.dimension.identifier())
                && payload.dimensionTypeId().equals(current.dimensionType.identifier());

        if (reusable) {
            // 复用也要刷新时间快照：level 本体可以复用，时间不能。
            // 漏了这行 → 首次 prepare 之后时间冻结，之后每次重发都白来。
            current.dayTime = payload.dayTime();
            StpConfig.debug("prepare: reusing prepared level for {}", payload.dimensionId());
        } else {
            if (current != null) {
                StpConfig.debug("prepare: dropping prepared level (session/dim/type mismatch)");
            }
            prepared = null;
            if (!build(payload)) {
                // 构建失败：绝不能抑制加载界面，否则玩家会卡在空世界里。
                suppressNextLoading = false;
                return;
            }
        }

        if (payload.enterNow()) {
            suppressNextLoading = prepared != null;
            StpConfig.debug("prepare: enterNow for {}, suppressLoading={}",
                    payload.dimensionId(), suppressNextLoading);
        }
    }

    private static boolean build(StpPackets.StpPrepareS2C payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return false;

        var dimTypeReg = conn.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        ResourceKey<DimensionType> dimTypeKey = ResourceKey.create(
                Registries.DIMENSION_TYPE, payload.dimensionTypeId());
        var holderOpt = dimTypeReg.get(dimTypeKey);
        if (holderOpt.isEmpty()) {
            StpConfig.warn("dimension type {} not found; cannot prebuild", payload.dimensionTypeId());
            return false;
        }

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, payload.dimensionId());

        ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
                mc.level != null ? mc.level.getDifficulty() : Difficulty.NORMAL,
                false, false);

        int chunkRadius = ((ClientPacketListenerAccessor) conn).doctor_m$getServerChunkRadius();
        int simDist = ((ClientPacketListenerAccessor) conn).doctor_m$getServerSimulationDistance();

        try {
            ClientLevel level = new ClientLevel(conn, levelData, dimKey, holderOpt.get(),
                    chunkRadius, simDist, mc.levelExtractor, false,
                    payload.biomeZoomSeed(), payload.seaLevel());
            prepared = new Prepared(payload.sessionId(), dimKey, dimTypeKey, level, levelData);
            prepared.dayTime = payload.dayTime();
            StpConfig.debug("prebuilt ClientLevel for {} (chunkRadius={}, simDist={}, dayTime={})",
                    payload.dimensionId(), chunkRadius, simDist, prepared.dayTime);
            return true;
        } catch (Throwable t) {
            StpConfig.warn("prebuilding ClientLevel for {} failed: {}", payload.dimensionId(), t.toString());
            return false;
        }
    }

    // ============================================================
    // 区块数据
    // ============================================================

    /** 一个已解码、等待写入 ClientChunkCache 的区块（含光照）。 */
    private record PendingChunk(int x, int z,
                                ClientboundLevelChunkPacketData data,
                                ClientboundLightUpdatePacketData light) {}

    /**
     * 待写入队列。
     *
     * <p>区块的反序列化发生在网络线程（不占渲染帧），但写入 {@code ClientChunkCache}
     * 与光照引擎必须回到主线程。一次维度切换会涌来几百个区块，同一帧全写会让主线程
     * 停住——正是“传送瞬间卡顿”的主要来源之一。按 {@link StpConfig#CLIENT_APPLY_PER_TICK}
     * 摊到多帧，用几帧的微小延迟换取帧率平稳。
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingChunk> PENDING =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 已写入预构建 level 的区块版本号（单调递增）。 */
    private static volatile int chunkRevision;

    /** 当前预构建 level 的区块版本号，渲染端按它决定是否重烘焙门户网格。 */
    public static int chunkRevision() {
        return chunkRevision;
    }

    /** 由网络线程调用：解码区块包并放进待写入队列。 */
    public static void onChunkData(StpPackets.StpChunkS2C payload) {
        Prepared target = prepared;
        if (target == null) {
            StpConfig.debug("chunk {}/{} arrived with no prepared level, ignored",
                    payload.chunkX(), payload.chunkZ());
            return;
        }
        if (target.isExpired(System.currentTimeMillis())) {
            StpConfig.debug("chunk {}/{} arrived after prepared level expired",
                    payload.chunkX(), payload.chunkZ());
            return;
        }
        if (!target.dimension.identifier().equals(payload.dimensionId())) {
            StpConfig.debug("chunk {}/{} for {} does not match prepared {}",
                    payload.chunkX(), payload.chunkZ(),
                    payload.dimensionId(), target.dimension.identifier());
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;

        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(payload.packetData()), conn.registryAccess());
            ClientboundLevelChunkWithLightPacket pkt =
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);

            // 队列满了丢最旧的：积压说明网络比主线程快得多，保留最新的更有价值。
            while (PENDING.size() >= StpConfig.CLIENT_QUEUE_LIMIT) {
                PENDING.poll();
            }
            PENDING.add(new PendingChunk(pkt.x(), pkt.z(), pkt.chunkData(), pkt.lightData()));
        } catch (Throwable t) {
            StpConfig.warn("decoding chunk {}/{} failed: {}",
                    payload.chunkX(), payload.chunkZ(), t.toString());
        }
    }

    /**
     * 每 tick 的光照泵送轮数预算：一轮 {@code runLightUpdates} 处理一批挂起任务，
     * 跑不完就留到下一 tick 接着排，绝不半途截断传播。
     */
    private static final int LIGHT_PUMP_ROUNDS_PER_TICK = 64;

    /** 光照引擎尚有积压任务时置位；进入终态后翻回 false 并 bump 一次 revision。 */
    private static volatile boolean lightPumpDirty;

    /**
     * 由客户端主线程每 tick 调用：限量写入待处理队列。
     *
     * <p>写入的是 {@link Prepared#level} 自己的缓存——换场时整个 level 被换入，
     * 区块与光照自然就位。区块入账后泵送光照引擎；引擎有积压时（{@code lightPumpDirty}）
     * 即使本 tick 没有新区块也要继续泵，直到排空为止。
     */
    public static void tick() {
        if (PENDING.isEmpty() && !lightPumpDirty) return;

        Prepared target = prepared;
        if (target == null) {
            PENDING.clear();
            lightPumpDirty = false;
            return;
        }
        if (target.isExpired(System.currentTimeMillis())) {
            StpConfig.debug("dropping {} pending chunks (prepared level expired)", PENDING.size());
            PENDING.clear();
            lightPumpDirty = false;
            return;
        }

        int applied = 0;
        PendingChunk chunk;
        while (applied < StpConfig.CLIENT_APPLY_PER_TICK && (chunk = PENDING.poll()) != null) {
            try {
                target.level.getChunkSource()
                        .replaceWithPacketData(chunk.x(), chunk.z(), chunk.data());
                applyLight(target.level, chunk.light(), chunk.x(), chunk.z());

                // 原版 enableChunkLight 等价物：登记 section 非空状态 + 标记相邻一圈脏。
                // 没有这步，光照引擎把 section 当空处理，传播被整体跳过。
                enableChunkLight(target.level, chunk.x(), chunk.z());
            } catch (Throwable t) {
                StpConfig.warn("applying chunk {}/{} failed: {}", chunk.x(), chunk.z(), t.toString());
            }
            applied++;
            chunkRevision++;
        }

        if (applied > 0) {
            StpConfig.debug("applied {} chunk(s), {} still pending", applied, PENDING.size());
        }

        // 即便本 tick 没有新区块入账，也要继续消化上一批留下的传播任务。
        lightPump(target.level);
    }

    /**
     * 渐进式泵送预构建 level 的光照引擎任务队列。
     *
     * <p>为什么必须手动泵：预构建 level 休眠期间没有被换进 {@code Minecraft.level}，
     * 没人 tick 它，原版依赖每 tick 光照更新来消化 {@code queueSectionData}
     * 的挂起数据。不泵的话光照永远躺在队列里。
     *
     * <p>为什么是渐进而不是一次排空：单帧 while 到底会在区块涌入 tick 把主线程停住，
     * 且单次循环截断会让跨界面的传播修正永远停在半途。这里按
     * {@link #LIGHT_PUMP_ROUNDS_PER_TICK} 的预算每 tick 跑一部分，跨 tick 排空；
     * 排空（终态）那一刻 bump 一次 {@code chunkRevision}，让门户网格按终态光照
     * 重烘最后一次——之前几次是半成品光照，这次才是最终形态。
     */
    private static void lightPump(ClientLevel level) {
        LevelLightEngine engine = level.getChunkSource().getLightEngine();
        for (int i = 0; i < LIGHT_PUMP_ROUNDS_PER_TICK; i++) {
            if (engine.runLightUpdates() <= 0) {
                if (lightPumpDirty) {
                    lightPumpDirty = false;
                    chunkRevision++;
                }
                return;
            }
        }
        lightPumpDirty = true;
    }

    /**
     * 原版 {@code ClientPacketListener.enableChunkLight} 等价物。
     *
     * <p>把区块各 section 的“非空”状态登记进光照引擎——这是传播引擎处理
     * 该 section 的前提条件。原版在 {@code handleLevelChunkWithLight} 里于
     * {@code applyLightData} 之后、区块数据写入之后调用它，这里保持相同顺序。
     *
     * <p><b>必须在 {@link #lightPump} 的消化范围内调用</b>：登记本身只改引擎的
     * 状态位，但登记结果与队列里的光照数据在 {@code runLightUpdates} 时一起
     * 生效——先登记后泵，顺序才与原版 {@code queueLightUpdate} 回调一致。
     */
    private static void enableChunkLight(ClientLevel level, int x, int z) {
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
        if (chunk == null) return;

        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos chunkPos = new ChunkPos(x, z);
        for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
            int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
            lightEngine.updateSectionStatus(
                    SectionPos.of(chunkPos, sectionY),
                    sections[sectionIndex].hasOnlyAir());
        }

        // 原版照搬：相邻一圈一起标脏，让跨界面的光照/网格在边缘处正确重算。
        level.setSectionRangeDirty(x - 1, level.getMinSectionY(), z - 1,
                x + 1, level.getMaxSectionY(), z + 1);
    }

    /**
     * 把区块附带的光照数据写入光照引擎。
     *
     * <p>照原版 {@code ClientPacketListener#handleLevelChunkWithLight → applyLightData}
     * 的做法：逐层写入天空光与方块光，空层标记为空，最后打开这个区块的光照传播。
     * 注意此时数据只是<b>入队</b>——真正的落库由 {@link #lightPump} 跨 tick 完成。
     */
    private static void applyLight(ClientLevel level, ClientboundLightUpdatePacketData light, int x, int z) {
        if (light == null) return;
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        ChunkPos chunkPos = new ChunkPos(x, z);

        applyLightLayer(lightEngine, LightLayer.SKY, chunkPos,
                light.skyYMask(), light.emptySkyYMask(), light.skyUpdates());
        applyLightLayer(lightEngine, LightLayer.BLOCK, chunkPos,
                light.blockYMask(), light.emptyBlockYMask(), light.blockUpdates());

        lightEngine.setLightEnabled(chunkPos, true);
    }

    /**
     * 写入单层光照（对应原版 {@code ClientPacketListener.readSectionList}）。
     *
     * <p><b>坐标语义（本案修正之一）</b>：必须用
     * {@code SectionPos.of(ChunkPos, sectionY)} 这个重载——它按区块坐标直接打包，
     * 与原版 {@code readSectionList} 一致。不能用
     * {@code SectionPos.of(int x, int y, int z)} 传区块坐标：那个重载收的是
     * <b>世界方块坐标</b>（内部对 x/z 各做 {@code >>4}），传区块坐标会把所有区块的
     * 光照数据错位堆进 (0..15, y, 0..15) 附近的 section。
     *
     * <p><b>Y 公式</b>：光照包 bitset 的位索引以 {@code minLightSection} 为基准，
     * {@code sectionY = getMinLightSection() + sectionIndex}，与原版一致。
     *
     * <p>与原版的两处细节对齐：数据层先 {@code clone()} 再入队（防 buffer
     * 复用被改）；空层用 {@code new DataLayer()}（空的非 null 层）。
     */
    private static void applyLightLayer(LevelLightEngine engine, LightLayer layer, ChunkPos pos,
                                        BitSet filledMask, BitSet emptyMask, List<byte[]> updates) {
        if (filledMask == null || emptyMask == null || updates == null) return;

        int minLightSection = engine.getMinLightSection();
        int index = 0;
        for (int y = filledMask.nextSetBit(0); y >= 0; y = filledMask.nextSetBit(y + 1)) {
            if (index >= updates.size()) break;
            byte[] data = updates.get(index++);
            engine.queueSectionData(layer, SectionPos.of(pos, y + minLightSection),
                    new DataLayer(data.clone()));
        }
        for (int y = emptyMask.nextSetBit(0); y >= 0; y = emptyMask.nextSetBit(y + 1)) {
            engine.queueSectionData(layer, SectionPos.of(pos, y + minLightSection),
                    new DataLayer());
        }
    }

    // ============================================================
    // 换场（供 Mixin 调用）
    // ============================================================

    /**
     * 取出与目标维度匹配的预构建 {@link ClientLevel}，并使其立即失效。
     *
     * <p><b>维度守卫</b>：只有预构建实例的维度与这次换场的目标维度一致时才返回它。
     * 不一致就丢弃并返回 {@code null} 让原版照常新建——宁可多一次正常加载，
     * 也不能把错误的世界换给玩家。
     *
     * @param requestedDimension 原版这次想切换到的维度
     */
    public static ClientLevel consumePreparedLevel(ResourceKey<Level> requestedDimension) {
        Prepared p = prepared;
        if (p == null) return null;
        if (p.isExpired(System.currentTimeMillis())) {
            StpConfig.debug("prepared level for {} expired, discarded", p.dimension.identifier());
            prepared = null;
            return null;
        }
        if (requestedDimension != null && !requestedDimension.equals(p.dimension)) {
            StpConfig.warn("prepared level is for {} but respawn wants {}; " +
                            "discarding prepared data and letting vanilla build",
                    p.dimension.identifier(), requestedDimension.identifier());
            prepared = null;
            return null;
        }
        prepared = null;
        StpConfig.debug("handing over prepared ClientLevel for {}", p.dimension.identifier());
        return p.level;
    }

    // ============================================================
    // 门户渲染（BOTI）访问
    // ============================================================

    /**
     * 门户渲染每帧调用：预构建 level 正在被门户视图使用，刷新过期时间戳。
     */
    public static void portalKeepAlive() {
        Prepared p = prepared;
        if (p != null) p.touch();
    }

    /**
     * 取指定维度的预构建 {@link ClientLevel}（不消费），供门户渲染读取方块 / 光照。
     * 维度不匹配或尚无预构建实例时返回 {@code null}——门户视图应当隐藏。
     */
    public static ClientLevel portalLevelFor(net.minecraft.resources.Identifier dimensionId) {
        Prepared p = prepared;
        if (p == null) return null;
        if (!p.dimension.identifier().equals(dimensionId)) return null;
        return p.level;
    }

    public static boolean consumeSuppress() {
        boolean r = suppressNextLoading || skipLoadingThisRespawn;
        suppressNextLoading = false;
        skipLoadingThisRespawn = false;
        return r;
    }

    public static void clear() {
        prepared = null;
        suppressNextLoading = false;
        skipLoadingThisRespawn = false;
        PENDING.clear();
        lightPumpDirty = false;
    }
}