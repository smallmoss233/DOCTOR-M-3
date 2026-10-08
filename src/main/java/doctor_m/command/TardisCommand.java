package doctor_m.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.network.OpenAppearanceScreenPayload;
import doctor_m.register.DMBlocks;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.TardisState;
import mosslib.dimension.DimensionDebug;
import mosslib.dimension.DynamicDimensionManager;
import mosslib.util.Text;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TardisCommand {

    private TardisCommand() {}

    /** 补全：列出所有 TARDIS UUID。 */
    private static final SuggestionProvider<CommandSourceStack> TARDIS_IDS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            builder.suggest(d.id().toString());
        }
        return builder.buildFuture();
    };

    /** 补全：主世界 / 下界 / 末地 / 所有 TARDIS 内部维度。 */
    private static final SuggestionProvider<CommandSourceStack> DIMENSION_IDS = (ctx, builder) -> {
        builder.suggest("overworld");
        builder.suggest("the_nether");
        builder.suggest("the_end");

        MinecraftServer server = ctx.getSource().getServer();
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            builder.suggest("tardis/" + d.id());
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("doctor_m")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("tardis")

                        // ---------- create ----------
                        .then(Commands.literal("create")
                                .executes(TardisCommand::createTardis))

                        // ---------- tp [id] ----------
                        .then(Commands.literal("tp")
                                .executes(TardisCommand::tpNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::tpById)))

                        // ---------- release <id> ----------
                        .then(Commands.literal("release")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::releaseById)))

                        // ---------- info [id] ----------
                        .then(Commands.literal("info")
                                .executes(TardisCommand::infoNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::infoById)))

                        // ---------- list ----------
                        .then(Commands.literal("list")
                                .executes(TardisCommand::listAll))

                        // ---------- door open|close [id] ----------
                        .then(Commands.literal("door")
                                .then(Commands.literal("open")
                                        .executes(ctx -> setNearbyDoor(ctx, true))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> setDoorById(ctx, true))))
                                .then(Commands.literal("close")
                                        .executes(ctx -> setNearbyDoor(ctx, false))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> setDoorById(ctx, false)))))

                        // ---------- appearance [id] ----------
                        .then(Commands.literal("appearance")
                                .executes(TardisCommand::openAppearanceNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::openAppearanceById)))

                        // ---------- dest <x> <y> <z> [dim]  （就近） ----------
                        // ---------- dest <id> <x> <y> <z> [dim]  （指定） ----------
                        .then(Commands.literal("dest")
                                // 就近分支：首参是数字
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(TardisCommand::setDestNearby)
                                                        .then(Commands.argument("dim", StringArgumentType.string())
                                                                .suggests(DIMENSION_IDS)
                                                                .executes(TardisCommand::setDestNearbyWithDim)))))
                                // 指定分支：首参是 UUID（含连字符，不会匹配 double）
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                .executes(TardisCommand::setDestById)
                                                                .then(Commands.argument("dim", StringArgumentType.string())
                                                                        .suggests(DIMENSION_IDS)
                                                                        .executes(TardisCommand::setDestByIdWithDim)))))))

                        // ---------- fly [id] ----------
                        .then(Commands.literal("fly")
                                .executes(TardisCommand::flyNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::flyById)))
                )
                // ============================================================
                //                      debug
                // ============================================================
                .then(Commands.literal("debug")
                        .then(Commands.literal("dims")
                                .executes(TardisCommand::listDims))
                        .then(Commands.literal("save")
                                .executes(TardisCommand::saveDims))
                        .then(Commands.literal("reload")
                                .executes(TardisCommand::reloadDims))
                        .then(Commands.literal("purge")
                                .executes(TardisCommand::purgeAll))
                        .then(Commands.literal("createdim")
                                .executes(TardisCommand::createDimRandom)
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(TardisCommand::createDimById)))
                        .then(Commands.literal("tpd")
                                .then(Commands.argument("dim", StringArgumentType.string())
                                        .suggests(DIMENSION_IDS)
                                        .executes(TardisCommand::tpDimDefault)
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(TardisCommand::tpDimAt)))))))
        );
    }

    // ================================================================
    //                      create
    // ================================================================

    private static int createTardis(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
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
                .setValue(AbstractTardisDoorBlock.OPEN, true);
        level.setBlock(target, lower, 3);
        level.setBlock(target.above(),
                lower.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        UUID id = data.id();
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.create.success", id.toString()),
                true);
        return 1;
    }

    // ================================================================
    //                      tp
    // ================================================================

    private static int tpNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return teleport(source, player, data);
    }

    private static int tpById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return teleport(source, player, data);
    }

    private static int teleport(CommandSourceStack source, ServerPlayer player, TardisData data) {
        if (!TardisManager.teleportInto(player, data)) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.tp.fail"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.tp.success", data.id().toString()),
                false);
        return 1;
    }

    // ================================================================
    //                      release
    // ================================================================

    private static int releaseById(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return doDelete(source, data);
    }

    private static int doDelete(CommandSourceStack source, TardisData data) {
        MinecraftServer server = source.getServer();
        UUID id = data.id();

        TardisManager.delete(server, id);
        clearExteriorBlock(server, data);

        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.delete.success", id.toString()),
                true);
        return 1;
    }

    private static void clearExteriorBlock(MinecraftServer server, TardisData data) {
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

    // ================================================================
    //                      info / list
    // ================================================================

    private static int infoNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return showInfo(source, data);
    }

    private static int infoById(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return showInfo(source, data);
    }

    private static int showInfo(CommandSourceStack source, TardisData data) {
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.info.header", data.id().toString()),
                false);
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.info.owner",
                        data.owner() == null ? "-" : data.owner().toString()),
                false);
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.info.exterior",
                        data.exteriorDim().identifier().toString(),
                        data.exteriorPos().toShortString(),
                        data.exteriorFacing().getName()),
                false);
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.info.interior",
                        data.interiorPos().toShortString(),
                        data.interiorFacing().getName()),
                false);
        source.sendSuccess(
                () -> Text.tr("doctor_m.command.tardis.info.spares",
                        data.spareDoors().size()),
                false);
        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.info.state",
                        data.state().getSerializedName()),
                false);
        return 1;
    }

    private static int listAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var all = TardisManager.getRegistry(source.getServer()).all();

        if (all.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("doctor_m.command.tardis.list.empty"), false);
            return 0;
        }
        int count = all.size();
        source.sendSuccess(
                () -> Text.tr("doctor_m.command.tardis.list.header", count), false);
        for (TardisData d : all) {
            source.sendSuccess(
                    () -> Component.translatable("doctor_m.command.tardis.list.entry",
                            d.id().toString(),
                            d.exteriorDim().identifier().toString(),
                            d.exteriorPos().toShortString(),
                            d.spareDoors().size()),
                    false);
        }
        return count;
    }

    // ================================================================
    //                      door
    // ================================================================

    private static int setNearbyDoor(CommandContext<CommandSourceStack> ctx, boolean open)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return doSetDoor(source, data, open);
    }

    private static int setDoorById(CommandContext<CommandSourceStack> ctx, boolean open) {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return doSetDoor(source, data, open);
    }

    private static int doSetDoor(CommandSourceStack source, TardisData data, boolean open) {
        TardisManager.setDoorOpen(source.getServer(), data, open);
        source.sendSuccess(
                () -> Component.translatable(
                        open ? "doctor_m.command.tardis.door.opened"
                                : "doctor_m.command.tardis.door.closed",
                        data.id().toString()),
                false);
        return 1;
    }

    // ================================================================
    //                      appearance
    // ================================================================

    private static int openAppearanceNearby(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return sendOpenAppearance(source, player, data);
    }

    private static int openAppearanceById(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return sendOpenAppearance(source, player, data);
    }

    private static int sendOpenAppearance(CommandSourceStack source,
                                          ServerPlayer player,
                                          TardisData data) {
        ServerPlayNetworking.send(
                player,
                new OpenAppearanceScreenPayload(data.id(), data.appearanceId()));
        source.sendSuccess(
                () -> Component.translatable(
                        "doctor_m.command.tardis.appearance.opened",
                        data.id().toString()),
                false);
        return 1;
    }

    // ================================================================
    //                      dest
    // ================================================================

    private static int setDestNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return doSetDest(source, data, player.level().dimension(), readXyz(ctx));
    }

    private static int setDestNearbyWithDim(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return doSetDestWithDim(source, data, readXyz(ctx), StringArgumentType.getString(ctx, "dim"));
    }

    private static int setDestById(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return doSetDest(source, data,
                source.getLevel().dimension(),   // ← 见下方说明
                readXyz(ctx));
    }

    private static int setDestByIdWithDim(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return doSetDestWithDim(source, data, readXyz(ctx),
                StringArgumentType.getString(ctx, "dim"));
    }

    private static BlockPos readXyz(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(
                DoubleArgumentType.getDouble(ctx, "x"),
                DoubleArgumentType.getDouble(ctx, "y"),
                DoubleArgumentType.getDouble(ctx, "z"));
    }

    private static int doSetDest(CommandSourceStack source, TardisData data,
                                 ResourceKey<Level> dim, BlockPos pos) {
        TardisManager.setDestination(source.getServer(), data, dim, pos);
        source.sendSuccess(
                () -> Text.tr("doctor_m.command.tardis.dest.set",
                        pos.getX(), pos.getY(), pos.getZ(),
                        dim.identifier().toString()),
                true);
        return 1;
    }

    private static int doSetDestWithDim(CommandSourceStack source, TardisData data,
                                        BlockPos pos, String dimInput) {
        Identifier dimId = parseDestDimInput(dimInput);
        if (dimId == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.tardis.dest.bad_dim", dimInput));
            return 0;
        }

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);

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

    // ================================================================
    //                      fly
    // ================================================================

    private static int flyNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return doFly(source, data);
    }

    private static int flyById(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return doFly(source, data);
    }

    private static int doFly(CommandSourceStack source, TardisData data) {
        if (data.state() != TardisState.LANDED) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.fly.not_landed",
                    data.state().getSerializedName()));
            return 0;
        }

        if (!TardisManager.startTakeoff(source.getServer(), data)) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.fly.fail"));
            return 0;
        }

        if (data.hasDestination()) {
            source.sendSuccess(
                    () -> Component.translatable("doctor_m.command.tardis.fly.success"),
                    true);
        } else {
            source.sendSuccess(
                    () -> Component.translatable("doctor_m.command.tardis.fly.success_no_dest"),
                    true);
        }
        return 1;
    }

    // ================================================================
    //                      debug
    // ================================================================

    private static int listDims(CommandContext<CommandSourceStack> ctx) {
        var list = DimensionDebug.listDynamic(ctx.getSource().getServer());
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("doctor_m.command.debug.list.empty"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Text.tr("doctor_m.command.debug.list.header", list.size()), false);
        for (var snap : list) {
            ctx.getSource().sendSuccess(
                    () -> Text.tr("doctor_m.command.debug.list.entry",
                            snap.key().identifier(), snap.playerCount(), snap.entityCount(),
                            snap.loadedChunks(), snap.pathExists() ? "§a✓" : "§c✗",
                            DimensionDebug.formatSize(snap.pathSizeBytes())),
                    false);
        }
        return list.size();
    }

    private static int saveDims(CommandContext<CommandSourceStack> ctx) {
        DimensionDebug.saveAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(
                () -> Component.translatable("doctor_m.command.debug.save.success"), false);
        return 1;
    }

    private static int reloadDims(CommandContext<CommandSourceStack> ctx) {
        DimensionDebug.unloadAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(
                () -> Component.translatable("doctor_m.command.debug.reload.success"), false);
        return 1;
    }

    private static int purgeAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        List<TardisData> snapshot = List.copyOf(TardisManager.getRegistry(server).all());
        int tardisCount = 0;
        for (TardisData data : snapshot) {
            TardisManager.delete(server, data.id());
            clearExteriorBlock(server, data);
            tardisCount++;
        }

        int dimCount = 0;
        for (var snap : DimensionDebug.listDynamic(server)) {
            if (DynamicDimensionManager.delete(server, snap.key())) {
                dimCount++;
            }
        }

        final int ft = tardisCount;
        final int fd = dimCount;
        source.sendSuccess(
                () -> Text.tr("doctor_m.command.debug.purge.success", ft, fd),
                true);
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
        MinecraftServer server = source.getServer();

        Identifier dimId = parseDimInput(input);
        if (dimId == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.debug.createdim.invalid", input));
            return 0;
        }

        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);

        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);
        if (level == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.debug.createdim.fail", dimId.toString()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.translatable(
                        "doctor_m.command.debug.createdim.success", dimId.toString()),
                true);
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

    private static int tpDim(CommandContext<CommandSourceStack> ctx, int x, int y, int z)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();

        String input = StringArgumentType.getString(ctx, "dim");
        Identifier dimId = parseDimInput(input);
        if (dimId == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.debug.tpd.invalid", input));
            return 0;
        }

        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);

        ServerLevel level = server.getLevel(key);
        if (level == null) {
            level = DynamicDimensionManager.getOrCreate(server, key);
            if (level == null) {
                source.sendFailure(Component.translatable(
                        "doctor_m.command.debug.tpd.fail", dimId.toString()));
                return 0;
            }
        }

        player.teleportTo(level,
                x + 0.5, y, z + 0.5,
                Set.of(),
                player.getYRot(), player.getXRot(),
                false);

        source.sendSuccess(
                () -> Text.tr(
                        "doctor_m.command.debug.tpd.success",
                        dimId.toString(), x, y, z),
                false);
        return 1;
    }

    // ================================================================
    //                      helpers
    // ================================================================

    /** 解析 UUID 字符串 → TardisData；失败时已发失败消息，直接返回 null。 */
    private static TardisData resolveById(CommandSourceStack source, String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("doctor_m.command.error.invalid_id", raw));
            return null;
        }
        TardisData data = TardisManager.get(source.getServer(), id);
        if (data == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.tardis.not_found", id.toString()));
        }
        return data;
    }

    private static Identifier parseDimInput(String input) {
        try {
            if (input.contains(":")) {
                return Identifier.parse(input);
            }
            return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
        } catch (Exception e) {
            return null;
        }
    }

    private static Identifier parseDestDimInput(String input) {
        try {
            if ("overworld".equals(input)) return Identifier.withDefaultNamespace("overworld");
            if ("the_nether".equals(input) || "nether".equals(input))
                return Identifier.withDefaultNamespace("the_nether");
            if ("the_end".equals(input) || "end".equals(input))
                return Identifier.withDefaultNamespace("the_end");
            if (input.contains(":")) return Identifier.parse(input);
            return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
        } catch (Exception e) {
            return null;
        }
    }
}