package doctor_m.stp;

import mosslib.api.PayloadRegistrar;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public final class StpPackets {

    private StpPackets() {}

    private static final PayloadRegistrar REG = new PayloadRegistrar("doctor_m");

    public static final CustomPacketPayload.Type<StpPrepareS2C> PREPARE_S2C =
            REG.s2c("stp_prepare", StpPrepareS2C.STREAM_CODEC);

    public static final CustomPacketPayload.Type<StpChunkRequestC2S> CHUNK_REQUEST_C2S =
            REG.c2s("stp_chunk_req", StpChunkRequestC2S.STREAM_CODEC);

    public static final CustomPacketPayload.Type<StpChunkS2C> CHUNK_S2C =
            REG.s2c("stp_chunk", StpChunkS2C.STREAM_CODEC);

    public static void register() {
        REG.commit();
    }

    /**
     * 服务端 → 客户端：准备进入目标维度。
     * <p>7 个字段，超出 {@code StreamCodec.composite} 的 6 参数上限，手写 codec。
     */
    public record StpPrepareS2C(
            UUID sessionId,
            Identifier dimensionId,
            Identifier dimensionTypeId,
            long biomeZoomSeed,
            int seaLevel,
            boolean enterNow,
            int preloadRadiusChunks
    ) implements CustomPacketPayload {

        public static final StreamCodec<RegistryFriendlyByteBuf, StpPrepareS2C> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public StpPrepareS2C decode(RegistryFriendlyByteBuf buf) {
                        return new StpPrepareS2C(
                                UUIDUtil.STREAM_CODEC.decode(buf),
                                Identifier.STREAM_CODEC.decode(buf),
                                Identifier.STREAM_CODEC.decode(buf),
                                buf.readVarLong(),
                                buf.readVarInt(),
                                buf.readBoolean(),
                                buf.readVarInt()
                        );
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, StpPrepareS2C v) {
                        UUIDUtil.STREAM_CODEC.encode(buf, v.sessionId());
                        Identifier.STREAM_CODEC.encode(buf, v.dimensionId());
                        Identifier.STREAM_CODEC.encode(buf, v.dimensionTypeId());
                        buf.writeVarLong(v.biomeZoomSeed());
                        buf.writeVarInt(v.seaLevel());
                        buf.writeBoolean(v.enterNow());
                        buf.writeVarInt(v.preloadRadiusChunks());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() { return PREPARE_S2C; }
    }

    public record StpChunkRequestC2S(
            Identifier dimensionId, int chunkX, int chunkZ
    ) implements CustomPacketPayload {

        public static final StreamCodec<RegistryFriendlyByteBuf, StpChunkRequestC2S> STREAM_CODEC =
                StreamCodec.composite(
                        Identifier.STREAM_CODEC, StpChunkRequestC2S::dimensionId,
                        ByteBufCodecs.VAR_INT, StpChunkRequestC2S::chunkX,
                        ByteBufCodecs.VAR_INT, StpChunkRequestC2S::chunkZ,
                        StpChunkRequestC2S::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() { return CHUNK_REQUEST_C2S; }
    }

    public record StpChunkS2C(
            Identifier dimensionId, int chunkX, int chunkZ, byte[] packetData
    ) implements CustomPacketPayload {

        public static final StreamCodec<RegistryFriendlyByteBuf, StpChunkS2C> STREAM_CODEC =
                StreamCodec.composite(
                        Identifier.STREAM_CODEC, StpChunkS2C::dimensionId,
                        ByteBufCodecs.VAR_INT, StpChunkS2C::chunkX,
                        ByteBufCodecs.VAR_INT, StpChunkS2C::chunkZ,
                        ByteBufCodecs.BYTE_ARRAY, StpChunkS2C::packetData,
                        StpChunkS2C::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() { return CHUNK_S2C; }
    }
}