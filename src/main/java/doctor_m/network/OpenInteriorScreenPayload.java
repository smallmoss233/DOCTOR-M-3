package doctor_m.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

public record OpenInteriorScreenPayload(UUID tardisId, Identifier currentInterior, List<Identifier> availableInteriors) implements CustomPacketPayload {
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenInteriorScreenPayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, OpenInteriorScreenPayload::tardisId,
            Identifier.STREAM_CODEC, OpenInteriorScreenPayload::currentInterior,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenInteriorScreenPayload::availableInteriors,
            OpenInteriorScreenPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return DMNetwork.OPEN_INTERIOR_SCREEN;
    }
}