package doctor_m.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.UUID;

/** 客户端 → 服务端：应用某个外观。 */
public record SetTardisAppearancePayload(
        UUID tardisId,
        Identifier appearanceId,
        Optional<Identifier> exteriorGeometry,
        Optional<Identifier> interiorGeometry
) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, SetTardisAppearancePayload> CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SetTardisAppearancePayload::tardisId,
                    Identifier.STREAM_CODEC, SetTardisAppearancePayload::appearanceId,
                    ByteBufCodecs.optional(Identifier.STREAM_CODEC),
                    SetTardisAppearancePayload::exteriorGeometry,
                    ByteBufCodecs.optional(Identifier.STREAM_CODEC),
                    SetTardisAppearancePayload::interiorGeometry,
                    SetTardisAppearancePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return DMNetwork.SET_APPEARANCE;
    }
}