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
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/**
 * STP 客户端状态机。
 *
 * <h2>目标</h2>
 * 让穿门时<b>不出现加载界面、不出现地形空洞</b>：需要的一切在玩家跨过门前就已经
 * 在客户端准备完毕。
 *
 * <h2>三个阶段</h2>
 * <ol>
 *   <li><b>prepare</b>：服务端在玩家靠近门时下发目标维度信息与区块数据。
 *       客户端据此预构建一个 {@link ClientLevel}，并把这些区块直接灌进它的
 *       {@code ClientChunkCache}。</li>
 *   <li><b>换场</b>：玩家真的跨过门时，服务端发来原版的维度切换包
 *       （{@code handleRespawn}）。Mixin 拦截其中新建 {@code ClientLevel} 与
 *       {@code ClientLevelData} 的两处调用，换成我们预构建的实例，
 *       并抑制加载界面。</li>
 *   <li><b>复用</b>：预构建实例被换入后立即失效，等待下一轮 prepare。</li>
 * </ol>
 *
 * <h2>本版修掉的结构问题</h2>
 * <ul>
 *   <li><b>维度守卫</b>：旧版只要"会话 ID 相同"就复用预构建实例，从不校验维度。
 *       预构建的是 A 维度、实际要切到 B 时，会把 A 的世界换进去 ——
 *       表现为穿门后看到错误的场景。现在按 {@code (sessionId, dimensionId,
 *       dimensionTypeId)} 三者匹配，任一不同就丢弃重建。</li>
 *   <li><b>过期丢弃</b>：prepare 后玩家没有真的穿门（转身走开 / 掉线 / 被传送走），
 *       预构建实例会一直挂着，并可能在很久以后被错误换入。现在带时间戳，
 *       超过 {@link StpConfig#PREPARE_TIMEOUT_MS} 自动失效。</li>
 *   <li><b>光照</b>：旧版把区块数据灌进去后完全丢弃光照数据，导致门后一片漆黑，
 *       要等服务端后续的光照更新才亮起来。现在按原版路径把天空光 / 方块光
 *       一并写入光照引擎。</li>
 *   <li><b>区块可见性</b>：写入区块后标记渲染脏位，避免"数据到了但没重绘"。</li>
 * </ul>
 */
public final class StpClientState {

    private StpClientState() {}

    // ============================================================
    //                      预构建实例
    // ============================================================

    /** 一份预构建结果：与其生效条件绑在一起，不一致就整体作废。 */
    private static final class Prepared {
        final UUID sessionId;
        /** 实际解析出的维度 key —— 必须与换场时请求的维度一致。 */
        final ResourceKey<Level> dimension;
        final ResourceKey<DimensionType> dimensionType;
        final ClientLevel level;
        final ClientLevel.ClientLevelData levelData;
        final long createdAtMs;

        Prepared(UUID sessionId,
                 ResourceKey<Level> dimension,
                 ResourceKey<DimensionType> dimensionType,
                 ClientLevel level,
                 ClientLevel.ClientLevelData levelData) {
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
    }

    private static volatile Prepared prepared = null;

    /** 抑制下一次加载界面的意图。 */
    private static volatile boolean suppressNextLoading = false;
    /** 服务端明确要求"立即进入"时置位，与 suppressNextLoading 配合。 */
    private static volatile boolean skipLoadingThisRespawn = false;

    // ============================================================
    //                      prepare 包
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
            StpConfig.debug("prepare: reusing prepared level for {}", payload.dimensionId());
        } else {
            if (current != null && !reusable) {
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
        ResourceKey<DimensionType> dimTypeKey =
                ResourceKey.create(Registries.DIMENSION_TYPE, payload.dimensionTypeId());
        var holderOpt = dimTypeReg.get(dimTypeKey);
        if (holderOpt.isEmpty()) {
            StpConfig.warn("dimension type {} not found; cannot prebuild",
                    payload.dimensionTypeId());
            return false;
        }

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, payload.dimensionId());

        ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
                mc.level != null ? mc.level.getDifficulty() : Difficulty.NORMAL,
                false, false);

        int chunkRadius = ((ClientPacketListenerAccessor) conn).doctor_m$getServerChunkRadius();
        int simDist = ((ClientPacketListenerAccessor) conn).doctor_m$getServerSimulationDistance();

        try {
            ClientLevel level = new ClientLevel(
                    conn, levelData, dimKey, holderOpt.get(),
                    chunkRadius, simDist, mc.levelExtractor, false,
                    payload.biomeZoomSeed(), payload.seaLevel());

            prepared = new Prepared(payload.sessionId(), dimKey, dimTypeKey, level, levelData);
            StpConfig.debug("prebuilt ClientLevel for {} (chunkRadius={}, simDist={})",
                    payload.dimensionId(), chunkRadius, simDist);
            return true;
        } catch (Throwable t) {
            StpConfig.warn("prebuilding ClientLevel for {} failed: {}",
                    payload.dimensionId(), t.toString());
            return false;
        }
    }

    // ============================================================
    //                      区块数据
    // ============================================================

    /** 一个已解码、等待写入 ClientChunkCache 的区块。 */
    private record PendingChunk(int x, int z,
                                ClientboundLevelChunkPacketData data,
                                ClientboundLightUpdatePacketData light) {}

    /**
     * 待写入队列。
     *
     * <p>为什么要排队而不是收到就写：区块数据的<b>反序列化</b>发生在网络线程
     * （不占渲染帧），但写入 {@code ClientChunkCache} 必须回到主线程。一次维度切换
     * 会涌来几百个区块，如果在同一帧里全部写入，主线程就会停住 ——
     * 这正是"传送那一瞬间的卡顿"的主要来源之一。
     *
     * <p>把写入按 {@link StpConfig#CLIENT_APPLY_PER_TICK} 摊到多帧上，
     * 用几帧的微小延迟换取帧率平稳。
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingChunk> PENDING =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

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
                    Unpooled.wrappedBuffer(payload.packetData()),
                    conn.registryAccess());

            ClientboundLevelChunkWithLightPacket pkt =
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);

            // 队列满了就丢最旧的：积压说明网络比主线程快得多，保留最新的更有价值。
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
     * 由客户端主线程每 tick 调用：限量写入待处理队列。
     *
     * <p>必须在预构建实例<b>尚未换入</b>时就完成写入，所以这里写进的是
     * {@link Prepared#level} 自己的区块缓存 —— 换场时整个 level 被换入，
     * 区块自然就位。
     */
    public static void tick() {
        if (PENDING.isEmpty()) return;

        Prepared target = prepared;
        if (target == null) {
            PENDING.clear();
            return;
        }
        if (target.isExpired(System.currentTimeMillis())) {
            StpConfig.debug("dropping {} pending chunks (prepared level expired)", PENDING.size());
            PENDING.clear();
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        int applied = 0;
        PendingChunk chunk;
        while (applied < StpConfig.CLIENT_APPLY_PER_TICK
                && (chunk = PENDING.poll()) != null) {

            try {
                target.level.getChunkSource()
                        .replaceWithPacketData(chunk.x(), chunk.z(), chunk.data());

                applyLight(target.level, chunk.light(), chunk.x(), chunk.z());

                for (int y = target.level.getMinSectionY();
                     y <= target.level.getMaxSectionY(); y++) {
                    mc.levelExtractor.setSectionDirty(chunk.x(), y, chunk.z());
                }
            } catch (Throwable t) {
                StpConfig.warn("applying chunk {}/{} failed: {}",
                        chunk.x(), chunk.z(), t.toString());
            }
            applied++;
        }

        if (applied > 0) {
            StpConfig.debug("applied {} chunk(s), {} still pending", applied, PENDING.size());
        }
    }

    /**
     * 把区块附带的光照数据写入光照引擎。
     *
     * <p>照原版 {@code ClientPacketListener#handleLevelChunkWithLight} 的做法：
     * 逐层写入天空光与方块光，把空的层标记为空，最后打开这个区块的光照传播。
     */
    private static void applyLight(ClientLevel level,
                                   ClientboundLightUpdatePacketData light,
                                   int x, int z) {
        if (light == null) return;

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        ChunkPos chunkPos = new ChunkPos(x, z);

        applyLightLayer(lightEngine, LightLayer.SKY,
                chunkPos,
                light.skyYMask(),
                light.emptySkyYMask(),
                light.skyUpdates());

        applyLightLayer(lightEngine, LightLayer.BLOCK,
                chunkPos,
                light.blockYMask(),
                light.emptyBlockYMask(),
                light.blockUpdates());

        lightEngine.setLightEnabled(chunkPos, true);
    }

    private static void applyLightLayer(LevelLightEngine engine,
                                        LightLayer layer,
                                        ChunkPos pos,
                                        BitSet filledMask,
                                        BitSet emptyMask,
                                        List<byte[]> updates) {
        if (filledMask == null || emptyMask == null || updates == null) return;

        int index = 0;
        for (int y = filledMask.nextSetBit(0); y >= 0; y = filledMask.nextSetBit(y + 1)) {
            if (index >= updates.size()) break;
            byte[] data = updates.get(index++);
            engine.queueSectionData(layer,
                    SectionPos.of(pos.x(), y, pos.z()),
                    new DataLayer(data));
        }
        for (int y = emptyMask.nextSetBit(0); y >= 0; y = emptyMask.nextSetBit(y + 1)) {
            engine.queueSectionData(layer,
                    SectionPos.of(pos.x(), y, pos.z()),
                    null);
        }
    }

    // ============================================================
    //                      换场（供 Mixin 调用）
    // ============================================================

    /**
     * 取出与目标维度匹配的预构建 {@link ClientLevel}，并使其立即失效。
     *
     * <p><b>维度守卫</b>：只有当预构建实例的维度与这次换场的目标维度一致时才返回它。
     * 不一致就丢弃并返回 {@code null}，让原版照常新建 —— 宁可多一次正常加载，
     * 也不能把错误的世界换给玩家。
     *
     * @param requestedDimension 原版这次想切换到的维度
     */
    public static ClientLevel consumePreparedLevel(ResourceKey<Level> requestedDimension) {
        Prepared p = prepared;
        if (p == null) return null;

        if (p.isExpired(System.currentTimeMillis())) {
            StpConfig.debug("prepared level for {} expired, discarded",
                    p.dimension.identifier());
            prepared = null;
            return null;
        }
        if (requestedDimension != null && !requestedDimension.equals(p.dimension)) {
            StpConfig.warn("prepared level is for {} but respawn wants {}; "
                            + "discarding prepared data and letting vanilla build",
                    p.dimension.identifier(), requestedDimension.identifier());
            prepared = null;
            return null;
        }

        prepared = null;
        StpConfig.debug("handing over prepared ClientLevel for {}",
                p.dimension.identifier());
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
    }
}
