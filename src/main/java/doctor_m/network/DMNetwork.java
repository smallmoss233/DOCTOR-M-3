package doctor_m.network;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import mosslib.core.api.PayloadRegistrar;
import mosslib.core.api.ServerHandlers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class DMNetwork {

    private DMNetwork() {}

    private static final PayloadRegistrar REG = new PayloadRegistrar("doctor_m");

    /** UI 里点"应用" → C2S 提交。 */
    public static final CustomPacketPayload.Type<SetTardisAppearancePayload> SET_APPEARANCE =
            REG.c2s("set_appearance", SetTardisAppearancePayload.CODEC);

    /** 服务端指令 → S2C 打开 UI。 */
    public static final CustomPacketPayload.Type<OpenAppearanceScreenPayload> OPEN_APPEARANCE_SCREEN =
            REG.s2c("open_appearance_screen", OpenAppearanceScreenPayload.CODEC);

    /** 客户端 → 服务端：上报控制台控件的世界 AABB。 */
    public static final CustomPacketPayload.Type<SyncConsoleControlsPayload> SYNC_CONSOLE_CONTROLS =
            REG.c2s("sync_console_controls", SyncConsoleControlsPayload.CODEC);

    /** 在 common 初始化器里调用一次。 */
    public static void register() {
        REG.commit();
        ServerHandlers.handle(SET_APPEARANCE, DMNetwork::handleSetAppearance);
        ServerHandlers.handle(SYNC_CONSOLE_CONTROLS, DMNetwork::handleSyncConsoleControls);
        ServerHandlers.handle(SET_INTERIOR, DMNetwork::handleSetInterior);
    }

    /** 服务端指令 → S2C 打开内饰 UI。 */
    public static final CustomPacketPayload.Type<OpenInteriorScreenPayload> OPEN_INTERIOR_SCREEN =
            REG.s2c("open_interior_screen", OpenInteriorScreenPayload.CODEC);

    /** UI 里点"应用" → C2S 提交内饰。 */
    public static final CustomPacketPayload.Type<SetTardisInteriorPayload> SET_INTERIOR =
            REG.c2s("set_interior", SetTardisInteriorPayload.CODEC);

    private static void handleSetInterior(SetTardisInteriorPayload p, ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        TardisData data = TardisManager.get(server, p.tardisId());
        if (data == null) return;

        boolean isOwner = player.getUUID().equals(data.owner());
        boolean isOp = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!isOwner && !isOp) return;

        // 调用 GLM 的底层接口
        boolean success = TardisManager.requestInteriorChange(server, data, p.interiorId());
        if (!success) {
            // 失败反馈（可能是在飞行中、已经有更新在进行等）
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("无法更换内饰：条件不满足（可能正在飞行或模板不存在）").withStyle(net.minecraft.ChatFormatting.RED));
        }
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

    private static void handleSyncConsoleControls(SyncConsoleControlsPayload p, ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockEntity be = level.getBlockEntity(p.consolePos());

        if (!(be instanceof TardisConsoleBlockEntity console)) {
            // 方块可能已被拆、或者客户端发得比方块加载早 —— 静默忽略
            return;
        }

        console.applyClientControls(p.entries());
    }
}