package doctor_m.stp;

import doctor_m.tardis.TardisData;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import mosslib.dimension.DimensionTemplate;
import mosslib.dimension.DynamicDimensionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class StpManager {

    private StpManager() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/STP");

    // ============ 进 TARDIS ============

    public static void requestPreload(MinecraftServer server, ServerPlayer player, TardisData data) {
        UUID playerId = player.getUUID();
        UUID tardisId = data.id();
        if (!StpServerState.addPreloaded(playerId, tardisId)) return;

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(tardisId));
        DynamicDimensionManager.getOrCreate(server, key);

        // 进 TARDIS：半径 0（TARDIS 维度本身很小，客户端直接预构建整个 ClientLevel）
        sendPrepare(server, player, tardisId, key, false, 0);
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
     * 玩家在 TARDIS 里靠近内门时调用：预加载主世界落点周围 3x3 区块。
     */
    public static void requestPreloadWorld(MinecraftServer server, ServerPlayer player,
                                           ResourceKey<Level> worldDim,
                                           int centerX, int centerZ) {
        UUID sessionId = player.getUUID();

        // 1. prepare 包（不触发传送，只让客户端构建空 ClientLevel）
        sendPrepare(server, player, sessionId, worldDim, false, 1);

        // 2. 3x3 区块数据
        ServerLevel level = server.getLevel(worldDim);
        if (level == null) return;

        int cx = centerX >> 4;
        int cz = centerZ >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                sendChunk(server, player, level, new ChunkPos(cx + dx, cz + dz));
            }
        }
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
            LOGGER.warn("[STP] sendChunk {} {} failed", pos.x(), pos.z(), t);
        }
    }
}