package doctor_m.stp;

import mosslib.core.api.PayloadRegistrar;
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
     *
     * <p>8 个字段，超出 {@code StreamCodec.composite} 的 6 参数上限，手写 codec。
     *
     * <p><b>dayTime</b>：构造时刻<b>目标维度</b>的 {@code getDayTime()} 累计 tick 快照，
     * -1 = 未同步（渲染端按白天处理）。为什么客户端需要它：lightmap 是全局唯一纹理、
     * 由玩家所在维度驱动——玩家站在 {@code fixed_time: 6000} 的内饰里时主 lightmap
     * 恒为正午，门户 FBO 借用原版管线时对侧的天光区域恒亮。客户端拿这个快照
     * 自行计算昼夜压暗（见 PortalViewManager），绕过 lightmap 的时间源错位。
     * 方向性：inTardis 触发时目标维度 = exterior 世界；主世界触发时目标维度 =
     * TARDIS 内饰（内饰 hasSkyLight=false，客户端会直接短路不消费此值，填什么都安全）。
     */
    public record StpPrepareS2C(
            UUID sessionId,
            Identifier dimensionId,
            Identifier dimensionTypeId,
            long biomeZoomSeed,
            int seaLevel,
            boolean enterNow,
            int preloadRadiusChunks,
            long dayTime
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
                                buf.readVarInt(),
                                buf.readVarLong()
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
                        buf.writeVarLong(v.dayTime());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return PREPARE_S2C;
        }
    }

    public record StpChunkRequestC2S(
            Identifier dimensionId,
            int chunkX,
            int chunkZ
    ) implements CustomPacketPayload {
        public static final StreamCodec<RegistryFriendlyByteBuf, StpChunkRequestC2S> STREAM_CODEC =
                StreamCodec.composite(
                        Identifier.STREAM_CODEC, StpChunkRequestC2S::dimensionId,
                        ByteBufCodecs.VAR_INT, StpChunkRequestC2S::chunkX,
                        ByteBufCodecs.VAR_INT, StpChunkRequestC2S::chunkZ,
                        StpChunkRequestC2S::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CHUNK_REQUEST_C2S;
        }
    }

    public record StpChunkS2C(
            Identifier dimensionId,
            int chunkX,
            int chunkZ,
            byte[] packetData
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
        public Type<? extends CustomPacketPayload> type() {
            return CHUNK_S2C;
        }
    }
}