package doctor_m.client.stp;

import doctor_m.client.mixin.ClientPacketListenerAccessor;
import doctor_m.stp.StpPackets;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.UUID;

public final class StpClientState {

    private StpClientState() {}

    private static volatile UUID preparedSessionId = null;
    private static volatile ClientLevel preparedLevel = null;
    private static volatile ClientLevel.ClientLevelData preparedLevelData = null;
    private static volatile boolean suppressLoading = false;
    private static volatile boolean skipLoadingThisRespawn = false;

    // ============================================================
    //                      prepare 包
    // ============================================================

    public static void onPrepare(StpPackets.StpPrepareS2C payload) {
        boolean needBuild = preparedLevel == null || !payload.sessionId().equals(preparedSessionId);
        boolean ok = !needBuild || build(payload);

        if (payload.enterNow()) {
            suppressLoading = ok && preparedLevel != null;
        }
    }

    private static boolean build(StpPackets.StpPrepareS2C payload) {
        preparedLevel = null;
        preparedLevelData = null;
        preparedSessionId = null;

        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return false;

        var dimTypeReg = conn.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        ResourceKey<DimensionType> dimTypeKey =
                ResourceKey.create(Registries.DIMENSION_TYPE, payload.dimensionTypeId());
        var holderOpt = dimTypeReg.get(dimTypeKey);
        if (holderOpt.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger("STP")
                    .warn("[STP] dimension type {} not found", payload.dimensionTypeId());
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
            preparedLevel = level;
            preparedLevelData = levelData;
            preparedSessionId = payload.sessionId();
            return true;
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("STP")
                    .warn("[STP] prebuild ClientLevel failed", t);
            return false;
        }
    }

    // ============================================================
    //                      区块数据
    // ============================================================

    public static void onChunkData(StpPackets.StpChunkS2C payload) {
        ClientLevel target = preparedLevel;
        if (target == null) return;
        if (!target.dimension().identifier().equals(payload.dimensionId())) return;

        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;

        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(payload.packetData()),
                    conn.registryAccess());

            ClientboundLevelChunkWithLightPacket pkt =
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);

            int x = pkt.x();
            int z = pkt.z();

            target.getChunkSource().replaceWithPacketData(x, z, pkt.chunkData());

            for (int y = target.getMinSectionY(); y <= target.getMaxSectionY(); y++) {
                mc.levelExtractor.setSectionDirty(x, y, z);
            }
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("STP")
                    .warn("[STP] decode chunk failed", t);
        }
    }

    // ============================================================
    //                      状态查询 / 消费
    // ============================================================

    public static boolean consumeSuppress() {
        boolean r = suppressLoading || skipLoadingThisRespawn;
        suppressLoading = false;
        skipLoadingThisRespawn = false;
        return r;
    }

    public static void markSkipLoading() {
        skipLoadingThisRespawn = true;
    }

    public static ClientLevel.ClientLevelData peekPreparedLevelData() {
        return preparedLevelData;
    }

    public static ClientLevel consumePreparedLevel() {
        ClientLevel l = preparedLevel;
        preparedLevel = null;
        preparedLevelData = null;
        preparedSessionId = null;
        return l;
    }

    public static void clear() {
        preparedSessionId = null;
        preparedLevel = null;
        preparedLevelData = null;
        suppressLoading = false;
        skipLoadingThisRespawn = false;
    }
}