package doctor_m.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import mosslib.core.dimension.DimensionDebug;
import mosslib.core.dimension.DynamicDimensionManager;
import mosslib.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TardisDebugCommand {
    private TardisDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, LiteralCommandNode<CommandSourceStack> root) {
        root.addChild(Commands.literal("debug")
                .then(Commands.literal("dims").executes(TardisDebugCommand::listDims))
                .then(Commands.literal("save").executes(TardisDebugCommand::saveDims))
                .then(Commands.literal("reload").executes(TardisDebugCommand::reloadDims))
                .then(Commands.literal("purge").executes(TardisDebugCommand::purgeAll))
                .then(Commands.literal("createdim")
                        .executes(TardisDebugCommand::createDimRandom)
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(TardisDebugCommand::createDimById)))
                .then(Commands.literal("tpd")
                        .then(Commands.argument("dim", StringArgumentType.word())
                                .suggests(TardisCommand.DIMENSION_IDS)
                                .executes(TardisDebugCommand::tpDimDefault)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(TardisDebugCommand::tpDimAt))))))
                .build());
    }

    private static int listDims(CommandContext<CommandSourceStack> ctx) {
        var list = DimensionDebug.listDynamic(ctx.getSource().getServer());
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("doctor_m.command.debug.list.empty"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Text.tr("doctor_m.command.debug.list.header", list.size()), false);
        for (var snap : list) {
            ctx.getSource().sendSuccess(() -> Text.tr("doctor_m.command.debug.list.entry",
                    snap.key().identifier(), snap.playerCount(), snap.entityCount(),
                    snap.loadedChunks(), snap.pathExists() ? "§a✓" : "§c✗",
                    DimensionDebug.formatSize(snap.pathSizeBytes())), false);
        }
        return list.size();
    }

    private static int saveDims(CommandContext<CommandSourceStack> ctx) {
        DimensionDebug.saveAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("doctor_m.command.debug.save.success"), false);
        return 1;
    }

    private static int reloadDims(CommandContext<CommandSourceStack> ctx) {
        DimensionDebug.unloadAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("doctor_m.command.debug.reload.success"), false);
        return 1;
    }

    private static int purgeAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        List<TardisData> snapshot = List.copyOf(TardisManager.getRegistry(server).all());
        int tardisCount = 0;
        for (TardisData data : snapshot) {
            TardisManager.delete(server, data.id());
            TardisCommand.clearExteriorBlock(server, data);
            tardisCount++;
        }
        int dimCount = 0;
        for (var snap : DimensionDebug.listDynamic(server)) {
            if (DynamicDimensionManager.delete(server, snap.key())) dimCount++;
        }
        final int ft = tardisCount;
        final int fd = dimCount;
        source.sendSuccess(() -> Text.tr("doctor_m.command.debug.purge.success", ft, fd), true);
        return tardisCount + dimCount;
    }

    private static int createDimRandom(CommandContext<CommandSourceStack> ctx) {
        return createDim(ctx, UUID.randomUUID().toString());
    }

    private static int createDimById(CommandContext<CommandSourceStack> ctx) {
        return createDim(ctx, StringArgumentType.getString(ctx, "id"));
    }

    private static int createDim(CommandContext<CommandSourceStack> ctx, String input) {
        CommandSourceStack source = ctx.getSource();
        Identifier dimId = TardisCommand.parseDimInput(input);
        if (dimId == null) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.createdim.invalid", input));
            return 0;
        }

        if (!dimId.getPath().startsWith("tardis/")) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.createdim.not_tardis"));
            return 0;
        }

        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel level = DynamicDimensionManager.getOrCreate(source.getServer(), key);
        if (level == null) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.createdim.fail", dimId.toString()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("doctor_m.command.debug.createdim.success", dimId.toString()), true);
        return 1;
    }

    private static int tpDimDefault(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return tpDim(ctx, 0, 100, 0);
    }

    private static int tpDimAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return tpDim(ctx,
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static int tpDim(CommandContext<CommandSourceStack> ctx, int x, int y, int z) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String input = StringArgumentType.getString(ctx, "dim").trim();

        Identifier dimId = TardisCommand.parseDimInput(input);
        if (dimId == null) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.tpd.invalid", input));
            return 0;
        }

        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);

        // 【铁闸 1】必须是 TARDIS 维度
        if (!TardisManager.isTardisDimension(key)) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.tpd.fail.not_tardis", input));
            return 0;
        }

        // 【铁闸 2】必须是已注册的 UUID 维度
        UUID tardisId = TardisManager.tardisIdFromDimension(key);
        if (TardisManager.get(source.getServer(), tardisId) == null) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.tpd.fail.not_registered", input));
            return 0;
        }

        // 【铁闸 3】必须已经被服务器加载
        ServerLevel level = source.getServer().getLevel(key);
        if (level == null) {
            source.sendFailure(Component.translatable("doctor_m.command.debug.tpd.fail.not_loaded", input));
            return 0;
        }

        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), false);
        source.sendSuccess(() -> Text.tr("doctor_m.command.debug.tpd.success", dimId.toString(), x, y, z), false);
        return 1;
    }
}