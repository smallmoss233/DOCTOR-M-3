package doctor_m.network;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import mosslib.api.PayloadRegistrar;
import mosslib.api.ServerHandlers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class DMNetwork {

    private DMNetwork() {}

    private static final PayloadRegistrar REG = new PayloadRegistrar("doctor_m");

    /** UI 里点"应用" → C2S 提交。 */
    public static final CustomPacketPayload.Type<SetTardisAppearancePayload> SET_APPEARANCE =
            REG.c2s("set_appearance", SetTardisAppearancePayload.CODEC);

    /** 服务端指令 → S2C 打开 UI。 */
    public static final CustomPacketPayload.Type<OpenAppearanceScreenPayload> OPEN_APPEARANCE_SCREEN =
            REG.s2c("open_appearance_screen", OpenAppearanceScreenPayload.CODEC);

    /** 在 common 初始化器里调用一次。 */
    public static void register() {
        REG.commit();
        ServerHandlers.handle(SET_APPEARANCE, DMNetwork::handleSetAppearance);
    }

    // ============================================================
    //                      服务端处理
    // ============================================================

    private static void handleSetAppearance(SetTardisAppearancePayload p, ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        TardisData data = TardisManager.get(server, p.tardisId());
        if (data == null) return;

        boolean isOwner = player.getUUID().equals(data.owner());
        boolean isOp = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!isOwner && !isOp) return;

        // 外观注册表是客户端资源包内容，服务端并非总有一份（专用服务器上可能为空）。
        // 若服务端知道这个 ID 就校验一次，避免把不存在的 ID 写进存档；
        // 不知道则放行 —— 渲染只发生在客户端，服务端不需要该资源。
        if (!TardisAppearanceRegistry.all().isEmpty()
                && TardisAppearanceRegistry.get(p.appearanceId()) == null) {
            return;
        }

        data.setAppearanceId(p.appearanceId());
        TardisManager.getRegistry(server).setDirty();

        TardisManager.syncDoorAppearance(server, data);
    }
}