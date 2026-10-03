package doctor_m.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import doctor_m.DMBlocks;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import mosslib.dimension.DimensionDebug;
import mosslib.dimension.DynamicDimensionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
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

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("doctor_m")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("tardis")

                        // ---------- create ----------
                        .then(Commands.literal("create")
                                .executes(TardisCommand::createTardis))

                        // ---------- tp [id] ----------
                        .then(Commands.literal("tp")
                                .executes(TardisCommand::tpOwn)
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
                                .executes(TardisCommand::infoOwn)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::infoById)))

                        // ---------- list ----------
                        .then(Commands.literal("list")
                                .executes(TardisCommand::listAll))

                        // ---------- door open|close [id] ----------
                        .then(Commands.literal("door")
                                .then(Commands.literal("open")
                                        .executes(ctx -> setOwnDoor(ctx, true))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> setDoorById(ctx, true))))
                                .then(Commands.literal("close")
                                        .executes(ctx -> setOwnDoor(ctx, false))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> setDoorById(ctx, false)))))

                        // ---------- appearance set <id> <appearance> ----------
                        .then(Commands.literal("appearance")
                                .then(Commands.literal("set")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .then(Commands.argument("appearance", IdentifierArgument.id())
                                                        .executes(TardisCommand::setAppearance)))))
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
                        // ---------- createdim [id] ----------
                        .then(Commands.literal("createdim")
                                .executes(TardisCommand::createDimRandom)
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(TardisCommand::createDimById)))
                        // ---------- tpd <dim> [x y z] ----------
                        .then(Commands.literal("tpd")
                                .then(Commands.argument("dim", StringArgumentType.string())
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

    private static int tpOwn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = findOwn(source.getServer(), player.getUUID());
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.no_own"));
            return 0;
        }
        return teleport(source, player, data);
    }

    private static int tpById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        UUID id = parseId(source, StringArgumentType.getString(ctx, "id"));
        if (id == null) return 0;

        TardisData data = TardisManager.get(source.getServer(), id);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.not_found", id.toString()));
            return 0;
        }
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
    //                      release（OP 删除单台）
    // ================================================================

    private static int releaseById(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID id = parseId(source, StringArgumentType.getString(ctx, "id"));
        if (id == null) return 0;

        TardisData data = TardisManager.get(source.getServer(), id);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.not_found", id.toString()));
            return 0;
        }
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

    /** 静默清掉外门方块。 */
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

    private static int infoOwn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = findOwn(source.getServer(), player.getUUID());
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.no_own"));
            return 0;
        }
        return showInfo(source, data);
    }

    private static int infoById(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID id = parseId(source, StringArgumentType.getString(ctx, "id"));
        if (id == null) return 0;
        TardisData data = TardisManager.get(source.getServer(), id);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.not_found", id.toString()));
            return 0;
        }
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
                () -> Component.translatable("doctor_m.command.tardis.info.spares",
                        data.spareDoors().size()),
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
                () -> Component.translatable("doctor_m.command.tardis.list.header", count), false);
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

    private static int setOwnDoor(CommandContext<CommandSourceStack> ctx, boolean open) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = findOwn(source.getServer(), player.getUUID());
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.no_own"));
            return 0;
        }
        return doSetDoor(source, data, open);
    }

    private static int setDoorById(CommandContext<CommandSourceStack> ctx, boolean open) {
        CommandSourceStack source = ctx.getSource();
        UUID id = parseId(source, StringArgumentType.getString(ctx, "id"));
        if (id == null) return 0;
        TardisData data = TardisManager.get(source.getServer(), id);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.not_found", id.toString()));
            return 0;
        }
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

    private static int setAppearance(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        UUID id = parseId(source, StringArgumentType.getString(ctx, "id"));
        if (id == null) return 0;

        Identifier appearance = IdentifierArgument.getId(ctx, "appearance");

        TardisData data = TardisManager.get(server, id);
        if (data == null) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.tardis.not_found", id.toString()));
            return 0;
        }

        data.setAppearanceId(appearance);
        TardisManager.getRegistry(server).setDirty();
        TardisManager.syncDoorAppearance(server, data);

        source.sendSuccess(() -> Component.literal(
                "§aAppearance set: §b" + appearance), false);
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
                () -> Component.translatable("doctor_m.command.debug.list.header", list.size()), false);
        for (var snap : list) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("doctor_m.command.debug.list.entry",
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
                () -> Component.translatable("doctor_m.command.debug.purge.success", ft, fd),
                true);
        return tardisCount + dimCount;
    }

    // ================================================================
    //                      debug: createdim
    // ================================================================

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

    // ================================================================
    //                      debug: tpd
    // ================================================================

    private static int tpDimDefault(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return tpDim(ctx, 0, 100, 0);
    }

    private static int tpDimAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        return tpDim(ctx, x, y, z);
    }

    private static int tpDim(CommandContext<CommandSourceStack> ctx, int x, int y, int z) throws CommandSyntaxException {
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

        // 已加载就直接用；否则尝试创建
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

        final ServerLevel fl = level;
        source.sendSuccess(
                () -> Component.translatable(
                        "doctor_m.command.debug.tpd.success",
                        dimId.toString(), x, y, z),
                false);
        return 1;
    }

    // ================================================================
    //                      helpers
    // ================================================================

    private static TardisData findOwn(MinecraftServer server, UUID playerId) {
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            if (playerId.equals(d.owner())) return d;
        }
        return null;
    }

    private static UUID parseId(CommandSourceStack source, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("doctor_m.command.error.invalid_id", raw));
            return null;
        }
    }

    /**
     * 解析维度输入。
     * <ul>
     *   <li>含 ":"  → 完整 ID，例：{@code my_ns:custom/path}</li>
     *   <li>不含 ":" → 补默认前缀，例：{@code test} → {@code doctor_m:tardis/test}</li>
     * </ul>
     * @return 解析失败返回 null
     */
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
}