package doctor_m.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisState;
import mosslib.core.dimension.DynamicDimensionManager;
import mosslib.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 飞行相关子命令：目的地坐标、油门、驻车制动。
 *
 * <p>三者都直接改 {@link TardisData} 里的档位 / 目的地字段，然后
 * {@code TardisFlight} 在 tick 里据此推进状态机。
 */
public final class TardisFlightCommands {

    private TardisFlightCommands() {}

    // =========================================================
    //                      油门 / 制动
    // =========================================================

    static int setThrottle(CommandSourceStack source, TardisData data, int tier) {
        data.setThrottle(tier);

        if (tier > 0) {
            data.setParkingBrake(false);
            if (data.state() == TardisState.LANDED) {
                if (!TardisManager.startTakeoff(source.getServer(), data)) {
                    source.sendFailure(Component.translatable("doctor_m.command.tardis.fly.fail"));
                    return 0;
                }
            }
        }

        TardisManager.getRegistry(source.getServer()).setDirty();
        source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.throttle.set", tier), true);
        return 1;
    }

    static int setParkingBrake(CommandSourceStack source, TardisData data, boolean state) {
        data.setParkingBrake(state);
        TardisManager.getRegistry(source.getServer()).setDirty();
        source.sendSuccess(() -> Component.translatable(
                state ? "doctor_m.command.tardis.brake.on" : "doctor_m.command.tardis.brake.off"),
                true);
        return 1;
    }

    // =========================================================
    //                      目的地
    // =========================================================

    static int setDestNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeNearby(ctx,
                (s, p, d) -> doSetDest(s, d, p.level().dimension(), TardisCommandSupport.readXyz(ctx)));
    }

    static int setDestNearbyWithDim(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return TardisCommandSupport.executeNearby(ctx,
                (s, p, d) -> doSetDestWithDim(s, d,
                        TardisCommandSupport.readXyz(ctx),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "dim")));
    }

    static int setDestById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx,
                (s, p, d) -> doSetDest(s, d, s.getLevel().dimension(), TardisCommandSupport.readXyz(ctx)));
    }

    static int setDestByIdWithDim(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx,
                (s, p, d) -> doSetDestWithDim(s, d,
                        TardisCommandSupport.readXyz(ctx),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "dim")));
    }

    private static int doSetDest(CommandSourceStack source, TardisData data,
                                 ResourceKey<Level> dim, BlockPos pos) {
        TardisManager.setDestination(source.getServer(), data, dim, pos);
        source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.dest.set",
                pos.getX(), pos.getY(), pos.getZ(), dim.identifier().toString()), true);
        return 1;
    }

    private static int doSetDestWithDim(CommandSourceStack source, TardisData data,
                                        BlockPos pos, String dimInput) {
        Identifier dimId = TardisCommandSupport.parseDestDimInput(dimInput);
        if (dimId == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.tardis.dest.bad_dim", dimInput));
            return 0;
        }

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

        // TARDIS 动态维度可能尚未加载 —— 显式创建一次
        if (targetLevel == null && TardisManager.isTardisDimension(dimKey)) {
            targetLevel = DynamicDimensionManager.getOrCreate(source.getServer(), dimKey);
        }
        if (targetLevel == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.tardis.dest.dim_not_loaded", dimId.toString()));
            return 0;
        }
        return doSetDest(source, data, dimKey, pos);
    }
}
