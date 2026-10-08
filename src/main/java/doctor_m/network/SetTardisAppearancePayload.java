package doctor_m.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * 客户端 → 服务端：应用某个外观。
 *
 * <p>只传外观 ID。服务端能自己从注册表查出该外观引用的几何 / 贴图，
 * 无需客户端代为告知 —— 旧版曾额外携带两个几何 ID，而那两个值在服务端
 * 被写进 TARDIS 数据后再也没有任何读取方，是纯粹的死数据，已移除。
 */
public record SetTardisAppearancePayload(
        UUID tardisId,
        Identifier appearanceId
) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, SetTardisAppearancePayload> CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SetTardisAppearancePayload::tardisId,
                    Identifier.STREAM_CODEC, SetTardisAppearancePayload::appearanceId,
                    SetTardisAppearancePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return DMNetwork.SET_APPEARANCE;
    }
}
