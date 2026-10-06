package doctor_m.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** 服务端 → 客户端：打开外观选择 UI，携带 TARDIS ID 与当前外观。 */
public record OpenAppearanceScreenPayload(
        UUID tardisId,
        Identifier appearanceId
) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAppearanceScreenPayload> CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, OpenAppearanceScreenPayload::tardisId,
                    Identifier.STREAM_CODEC, OpenAppearanceScreenPayload::appearanceId,
                    OpenAppearanceScreenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return DMNetwork.OPEN_APPEARANCE_SCREEN;
    }
}