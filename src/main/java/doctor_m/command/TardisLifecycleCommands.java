package doctor_m.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.register.DMBlocks;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import mosslib.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.UUID;

/**
 * 生命周期类子命令：创建 / 传送 / 删除 / 查询 / 列表。
 *
 * <p>从 {@code TardisCommand} 拆出，与"交互类"（门、界面）和"飞行类"（目的地、
 * 油门、制动）分开维护。
 */
public final class TardisLifecycleCommands {

    private TardisLifecycleCommands() {}

    // =========================================================
    //                      创建
    // =========================================================

    static int createTardis(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        ServerLevel level = player.level();

        Direction facing = player.getDirection();
        if (!facing.getAxis().isHorizontal()) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.create.bad_facing"));
            return 0;
        }

        BlockPos target = player.blockPosition().relative(facing);
        if (!level.getBlockState(target).canBeReplaced()
                || !level.getBlockState(target.above()).canBeReplaced()) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.create.no_space"));
            return 0;
        }
        if (TardisManager.findByExterior(server, level.dimension(), target) != null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.create.occupied"));
            return 0;
        }

        TardisData data = TardisManager.createNewTardis(
                server, player.getUUID(), level.dimension(), target, facing);

        BlockState lower = DMBlocks.TARDIS_EXTERIOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, facing)
                .setValue(AbstractTardisDoorBlock.OPEN, false);
        level.setBlock(target, lower, 3);
        level.setBlock(target.above(),
                lower.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        if (data.interiorBuilt()) {
            TardisManager.setDoorOpen(server, data, true);
        } else {
            TardisManager.syncDoorAppearance(server, data);
        }

        UUID id = data.id();
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.create.success", id.toString()),
                true);
        return 1;
    }

    // =========================================================
    //                      传送
    // =========================================================

    static int tpNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeNearby(ctx, (source, player, data) -> {
            if (!TardisManager.teleportInto(player, data)) {
                source.sendFailure(Component.translatable("doctor_m.command.tardis.tp.fail"));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable(
                    "doctor_m.command.tardis.tp.success", data.id().toString()), false);
            return 1;
        });
    }

    static int tpById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx, (source, player, data) -> {
            if (player == null || !TardisManager.teleportInto(player, data)) {
                source.sendFailure(Component.translatable("doctor_m.command.tardis.tp.fail"));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable(
                    "doctor_m.command.tardis.tp.success", data.id().toString()), false);
            return 1;
        });
    }

    // =========================================================
    //                      删除
    // =========================================================

    static int releaseById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx, (source, player, data) -> {
            UUID id = data.id();
            TardisManager.delete(source.getServer(), id);
            clearExteriorBlock(source.getServer(), data);
            source.sendSuccess(() -> Component.translatable(
                    "doctor_m.command.tardis.delete.success", id.toString()), true);
            return 1;
        });
    }

    /** 清掉外门方块（TARDIS 记录删除后，方块不会自己消失）。 */
    public static void clearExteriorBlock(MinecraftServer server, TardisData data) {
        if (data.exteriorDim() == null || data.exteriorPos() == null) return;
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;
        BlockState s = level.getBlockState(data.exteriorPos());
        if (!(s.getBlock() instanceof AbstractTardisDoorBlock)) return;
        BlockPos lower = s.getValue(AbstractTardisDoorBlock.HALF) == DoubleBlockHalf.LOWER
                ? data.exteriorPos() : data.exteriorPos().below();
        level.setBlock(lower, Blocks.AIR.defaultBlockState(), 35);
        level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), 35);
    }

    // =========================================================
    //                      查询
    // =========================================================

    static int infoNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeNearby(ctx, (s, p, d) -> showInfo(s, d));
    }

    static int infoById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TardisCommandSupport.executeById(ctx, (s, p, d) -> showInfo(s, d));
    }

    private static int showInfo(CommandSourceStack source, TardisData data) {
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.info.header", data.id().toString()), false);
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.info.owner",
                data.owner() == null ? "-" : data.owner().toString()), false);
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.info.exterior",
                data.exteriorDim().identifier().toString(),
                data.exteriorPos().toShortString(),
                data.exteriorFacing().getName()), false);
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.info.interior",
                data.interiorPos().toShortString(),
                data.interiorFacing().getName()), false);
        source.sendSuccess(() -> Text.tr(
                "doctor_m.command.tardis.info.spares", data.spareDoors().size()), false);
        source.sendSuccess(() -> Component.translatable(
                "doctor_m.command.tardis.info.state", data.state().getSerializedName()), false);
        return 1;
    }

    static int listAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var all = TardisManager.getRegistry(source.getServer()).all();
        if (all.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("doctor_m.command.tardis.list.empty"), false);
            return 0;
        }
        int count = all.size();
        source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.list.header", count), false);
        for (TardisData d : all) {
            source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.list.entry",
                    d.id().toString(),
                    d.exteriorDim().identifier().toString(),
                    d.exteriorPos().toShortString(),
                    d.spareDoors().size()), false);
        }
        return count;
    }
}
