package doctor_m.stp;

import doctor_m.tardis.TardisData;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import mosslib.dimension.DimensionTemplate;
import mosslib.dimension.DynamicDimensionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.UUID;

/**
 * STP 服务端：把目标维度的信息与区块数据提前送给客户端。
 *
 * <h2>分工</h2>
 * <ul>
 *   <li>本类负责"准备什么内容"：解析维度类型、序列化区块。</li>
 *   <li>{@link StpChunkPump} 负责"什么时候发"：把成百上千个区块摊到多个 tick。</li>
 *   <li>{@link StpTrigger} 负责"什么时候开始"：按玩家与门的距离触发。</li>
 * </ul>
 *
 * <h2>为什么区块要经 pump 而不是直接发</h2>
 * 一次维度切换客户端需要几百个区块。在同一个 tick 里发完会造成网络突发、
 * 并在客户端单帧内引入巨大解析开销 —— 那正是"传送瞬间的卡顿"。
 * 全部经 {@link StpChunkPump} 排队滴灌。
 */
public final class StpManager {

    private StpManager() {}

    // ============ 进 TARDIS ============

    public static void requestPreload(MinecraftServer server, ServerPlayer player, TardisData data) {
        UUID playerId = player.getUUID();
        UUID tardisId = data.id();
        if (!StpServerState.addPreloaded(playerId, tardisId)) return;

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(tardisId));
        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);

        // 进 TARDIS：内部维度很小，把整间房连周边一起排队。
        // 同样经 pump 而不是立即发送，避免一个 tick 里几十个包的突发。
        sendPrepare(server, player, tardisId, key, false, StpConfig.TARDIS_PRELOAD_RADIUS);

        if (level != null && data.interiorPos() != null) {
            BlockPos interior = data.interiorPos();
            StpChunkPump.enqueueRadius(player, level,
                    interior.getX(), interior.getZ(), StpConfig.TARDIS_PRELOAD_RADIUS);
            StpConfig.debug("enqueued TARDIS chunks around {} for {} (pending={})",
                    interior.toShortString(), player.getGameProfile().name(),
                    StpChunkPump.pending(playerId));
        }
    }

    public static void releasePreload(MinecraftServer server, ServerPlayer player, TardisData data) {
        StpServerState.removePreloaded(player.getUUID(), data.id());
    }

    public static void notifyEntering(ServerPlayer player, UUID sessionId, ResourceKey<Level> targetDim) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        sendPrepare(server, player, sessionId, targetDim, true, 0);
    }

    // ============ 出 TARDIS ============

    /**
     * 玩家在 TARDIS 里靠近内门时调用：把主世界落点周围的区块排进发送队列。
     *
     * <p>半径由 {@link StpConfig#WORLD_PRELOAD_RADIUS} 决定。为什么铺这么一大片：
     * 玩家出门后会立刻朝各个方向看，此刻才到的区块就是可见的"地形空洞"，
     * 而下载整片地形正是出 TARDIS 卡顿的主因。玩家从触发距离走到门口有几秒钟，
     * 足够把这几百个区块平稳铺完（见 {@link StpChunkPump}）。
     */
    public static void requestPreloadWorld(MinecraftServer server, ServerPlayer player,
                                           ResourceKey<Level> worldDim,
                                           int centerX, int centerZ) {
        UUID sessionId = player.getUUID();

        // 1. prepare 包（不触发传送，只让客户端构建空 ClientLevel）
        sendPrepare(server, player, sessionId, worldDim, false,
                StpConfig.WORLD_PRELOAD_RADIUS);

        // 2. 把 (2r+1)² 个区块排进滴灌队列
        ServerLevel level = server.getLevel(worldDim);
        if (level == null) return;

        StpChunkPump.enqueueRadius(player, level, centerX, centerZ,
                StpConfig.WORLD_PRELOAD_RADIUS);
        StpConfig.debug("enqueued world chunks around {}/{} for {} (pending={})",
                centerX, centerZ, player.getGameProfile().name(),
                StpChunkPump.pending(sessionId));
    }

    /** 玩家撞内门：确认进入主世界。 */
    public static void notifyEnteringWorld(ServerPlayer player, ResourceKey<Level> worldDim) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        sendPrepare(server, player, player.getUUID(), worldDim, true, 0);
    }

    // ============ 通用发送 ============

    private static void sendPrepare(MinecraftServer server, ServerPlayer player,
                                    UUID sessionId, ResourceKey<Level> targetDim,
                                    boolean enterNow, int preloadRadiusChunks) {
        ServerLevel target = server.getLevel(targetDim);
        if (target == null) {
            target = DynamicDimensionManager.getOrCreate(server, targetDim);
            if (target == null) return;
        }

        var dimTypeHolder = target.dimensionTypeRegistration();
        ResourceKey<DimensionType> dimTypeKey = dimTypeHolder.unwrapKey().orElse(null);
        if (dimTypeKey == null) return;

        long biomeZoomSeed = BiomeManager.obfuscateSeed(target.getSeed());
        int seaLevel = target.getSeaLevel();

        ServerPlayNetworking.send(player, new StpPackets.StpPrepareS2C(
                sessionId,
                targetDim.identifier(),
                dimTypeKey.identifier(),
                biomeZoomSeed,
                seaLevel,
                enterNow,
                preloadRadiusChunks
        ));
    }

    /**
     * 发送单个区块数据（用原版 {@code ClientboundLevelChunkWithLightPacket} 序列化）。
     *
     * <p>整个区块包（含光照）被原样序列化成字节数组，客户端解码后走与原版完全相同的
     * 数据路径 —— 这是"看起来和原版加载没有区别"的前提。
     */
    public static void sendChunk(MinecraftServer server, ServerPlayer player,
                                 ServerLevel level, ChunkPos pos) {
        try {
            LevelChunk chunk = level.getChunk(pos.x(), pos.z());

            ClientboundLevelChunkWithLightPacket pkt = new ClientboundLevelChunkWithLightPacket(
                    chunk,
                    level.getLightEngine(),
                    null,
                    null
            );

            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                    Unpooled.buffer(), server.registryAccess());
            try {
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, pkt);
                byte[] data = ByteBufUtil.getBytes(buf);

                ServerPlayNetworking.send(player, new StpPackets.StpChunkS2C(
                        level.dimension().identifier(), pos.x(), pos.z(), data));
            } finally {
                buf.release();
            }
        } catch (Throwable t) {
            StpConfig.warn("sendChunk {}/{} failed: {}", pos.x(), pos.z(), t.toString());
        }
    }
}
