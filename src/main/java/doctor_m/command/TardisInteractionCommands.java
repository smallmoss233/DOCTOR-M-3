package doctor_m.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import doctor_m.network.OpenAppearanceScreenPayload;
import doctor_m.network.OpenInteriorScreenPayload;
import doctor_m.tardis.interior.TardisInteriorRegistry;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 交互类子命令：门开关、打开外观 / 内饰选择界面。
 *
 * <p>这些命令的产物是"对 TARDIS 做一次即时操作"或"给玩家弹一个界面"，
 * 与生命周期（创建/删除）和飞行（目的地/油门）区分开。
 */
public final class TardisInteractionCommands {

    private TardisInteractionCommands() {}

    // =========================================================
    //                      门
    // =========================================================

    static int setDoor(CommandSourceStack source, TardisData data, boolean open) {
        TardisManager.setDoorOpen(source.getServer(), data, open);
        source.sendSuccess(() -> Component.translatable(
                open ? "doctor_m.command.tardis.door.opened" : "doctor_m.command.tardis.door.closed",
                data.id().toString()), false);
        return 1;
    }

    // =========================================================
    //                      外观界面
    // =========================================================

    static int openAppearanceNearby(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return TardisCommandSupport.executeNearby(ctx, (s, p, d) -> sendOpenAppearance(s, p, d));
    }

    static int openAppearanceById(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx, (s, p, d) -> sendOpenAppearance(s, p, d));
    }

    private static int sendOpenAppearance(CommandSourceStack source,
                                          @Nullable ServerPlayer player,
                                          TardisData data) {
        if (player == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.player_only"));
            return 0;
        }
        ServerPlayNetworking.send(player,
                new OpenAppearanceScreenPayload(data.id(), data.appearanceId()));
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.appearance.opened", data.id().toString()), false);
        return 1;
    }

    // =========================================================
    //                      内饰界面
    // =========================================================

    static int openInteriorNearby(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return TardisCommandSupport.executeNearby(ctx, (s, p, d) -> sendOpenInterior(s, p, d));
    }

    static int openInteriorById(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx, (s, p, d) -> sendOpenInterior(s, p, d));
    }

    private static int sendOpenInterior(CommandSourceStack source,
                                        @Nullable ServerPlayer player,
                                        TardisData data) {
        if (player == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.player_only"));
            return 0;
        }
        var list = TardisInteriorRegistry.getAvailableInteriors(source.getServer());
        ServerPlayNetworking.send(player,
                new OpenInteriorScreenPayload(data.id(), data.interiorId(), list));
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.interior.opened", data.id().toString()), false);
        return 1;
    }
}
