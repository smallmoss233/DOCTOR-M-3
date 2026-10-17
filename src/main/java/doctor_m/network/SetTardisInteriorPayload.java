package doctor_m.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record SetTardisInteriorPayload(UUID tardisId, Identifier interiorId) implements CustomPacketPayload {
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTardisInteriorPayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetTardisInteriorPayload::tardisId,
            Identifier.STREAM_CODEC, SetTardisInteriorPayload::interiorId,
            SetTardisInteriorPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return DMNetwork.SET_INTERIOR;
    }
}