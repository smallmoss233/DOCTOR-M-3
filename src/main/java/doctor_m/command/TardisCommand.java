package doctor_m.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.network.OpenAppearanceScreenPayload;
import doctor_m.network.OpenInteriorScreenPayload;
import doctor_m.register.DMBlocks;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisState;
import doctor_m.tardis.interior.TardisInteriorRegistry;
import mosslib.core.dimension.DynamicDimensionManager;
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
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class TardisCommand {

    private TardisCommand() {}

    public static final SuggestionProvider<CommandSourceStack> TARDIS_IDS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            builder.suggest(d.id().toString());
        }
        return builder.buildFuture();
    };

    public static final SuggestionProvider<CommandSourceStack> DIMENSION_IDS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            // 直接建议纯 UUID，别加 tardis/ 了
            builder.suggest(d.id().toString());
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("doctor_m")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("tardis")
                        .then(Commands.literal("create").executes(TardisCommand::createTardis))
                        .then(Commands.literal("tp")
                                .executes(TardisCommand::tpNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::tpById)))
                        .then(Commands.literal("release")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::releaseById)))
                        .then(Commands.literal("info")
                                .executes(TardisCommand::infoNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::infoById)))
                        .then(Commands.literal("list").executes(TardisCommand::listAll))
                        .then(Commands.literal("door")
                                .then(Commands.literal("open")
                                        .executes(ctx -> executeNearby(ctx, (s, p, d) -> doSetDoor(s, d, true)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> executeById(ctx, (s, p, d) -> doSetDoor(s, d, true)))))
                                .then(Commands.literal("close")
                                        .executes(ctx -> executeNearby(ctx, (s, p, d) -> doSetDoor(s, d, false)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> executeById(ctx, (s, p, d) -> doSetDoor(s, d, false))))))
                        .then(Commands.literal("appearance")
                                .executes(TardisCommand::openAppearanceNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::openAppearanceById)))
                        .then(Commands.literal("interior")
                                .executes(TardisCommand::openInteriorNearby)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .executes(TardisCommand::openInteriorById)))
                        .then(Commands.literal("dest")
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(TardisCommand::setDestNearby)
                                                        .then(Commands.argument("dim", StringArgumentType.word())
                                                                .suggests(DIMENSION_IDS)
                                                                .executes(TardisCommand::setDestNearbyWithDim)))))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(TARDIS_IDS)
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                .executes(TardisCommand::setDestById)
                                                                .then(Commands.argument("dim", StringArgumentType.word())
                                                                        .suggests(DIMENSION_IDS)
                                                                        .executes(TardisCommand::setDestByIdWithDim)))))))
                        .then(Commands.literal("throttle")
                                .then(Commands.argument("tier", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> executeNearby(ctx, (s, p, d) -> setThrottle(s, d, IntegerArgumentType.getInteger(ctx, "tier"))))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> executeById(ctx, (s, p, d) -> setThrottle(s, d, IntegerArgumentType.getInteger(ctx, "tier")))))))
                        .then(Commands.literal("brake")
                                .then(Commands.literal("on")
                                        .executes(ctx -> executeNearby(ctx, (s, p, d) -> setParkingBrake(s, d, true)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> executeById(ctx, (s, p, d) -> setParkingBrake(s, d, true)))))
                                .then(Commands.literal("off")
                                        .executes(ctx -> executeNearby(ctx, (s, p, d) -> setParkingBrake(s, d, false)))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(TARDIS_IDS)
                                                .executes(ctx -> executeById(ctx, (s, p, d) -> setParkingBrake(s, d, false))))))
                ));

        TardisDebugCommand.register(dispatcher, root);
    }

    // ================================================================
    //                      高阶执行器封装
    // ================================================================

    @FunctionalInterface
    public interface TardisExecutor {
        int execute(CommandSourceStack source, @Nullable ServerPlayer player, TardisData data) throws CommandSyntaxException;
    }

    private static int executeNearby(CommandContext<CommandSourceStack> ctx, TardisExecutor action) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return action.execute(source, player, data);
    }

    private static int executeById(CommandContext<CommandSourceStack> ctx, TardisExecutor action) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return action.execute(source, source.getPlayer(), data);
    }

    // ================================================================
    //                      具体业务实现
    // ================================================================

    private static int setThrottle(CommandSourceStack source, TardisData data, int tier) {
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
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.throttle.set", tier), true);
        return 1;
    }

    private static int setParkingBrake(CommandSourceStack source, TardisData data, boolean state) {
        data.setParkingBrake(state);
        TardisManager.getRegistry(source.getServer()).setDirty();
        source.sendSuccess(() -> Component.translatable(state ? "doctor_m.command.tardis.brake.on" : "doctor_m.command.tardis.brake.off"), true);
        return 1;
    }

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
        if (!level.getBlockState(target).canBeReplaced() || !level.getBlockState(target.above()).canBeReplaced()) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.create.no_space"));
            return 0;
        }
        if (TardisManager.findByExterior(server, level.dimension(), target) != null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.create.occupied"));
            return 0;
        }

        TardisData data = TardisManager.createNewTardis(server, player.getUUID(), level.dimension(), target, facing);

        BlockState lower = DMBlocks.TARDIS_EXTERIOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, facing)
                .setValue(AbstractTardisDoorBlock.OPEN, false);
        level.setBlock(target, lower, 3);
        level.setBlock(target.above(), lower.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        if (data.interiorBuilt()) {
            TardisManager.setDoorOpen(server, data, true);
        } else {
            TardisManager.syncDoorAppearance(server, data);
        }

        UUID id = data.id();
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.create.success", id.toString()), true);
        return 1;
    }

    private static int tpNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeNearby(ctx, (source, player, data) -> {
            if (!TardisManager.teleportInto(player, data)) {
                source.sendFailure(Component.translatable("doctor_m.command.tardis.tp.fail"));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.tp.success", data.id().toString()), false);
            return 1;
        });
    }

    private static int tpById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (source, player, data) -> {
            if (player == null || !TardisManager.teleportInto(player, data)) {
                source.sendFailure(Component.translatable("doctor_m.command.tardis.tp.fail"));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.tp.success", data.id().toString()), false);
            return 1;
        });
    }

    private static int releaseById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (source, player, data) -> {
            UUID id = data.id();
            TardisManager.delete(source.getServer(), id);
            clearExteriorBlock(source.getServer(), data);
            source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.delete.success", id.toString()), true);
            return 1;
        });
    }

    public static void clearExteriorBlock(MinecraftServer server, TardisData data) {
        if (data.exteriorDim() == null || data.exteriorPos() == null) return;
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;
        BlockState s = level.getBlockState(data.exteriorPos());
        if (!(s.getBlock() instanceof AbstractTardisDoorBlock)) return;
        BlockPos lower = s.getValue(AbstractTardisDoorBlock.HALF) == DoubleBlockHalf.LOWER ? data.exteriorPos() : data.exteriorPos().below();
        level.setBlock(lower, Blocks.AIR.defaultBlockState(), 35);
        level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), 35);
    }

    private static int infoNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeNearby(ctx, (s, p, d) -> showInfo(s, d));
    }

    private static int infoById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (s, p, d) -> showInfo(s, d));
    }

    private static int showInfo(CommandSourceStack source, TardisData data) {
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.info.header", data.id().toString()), false);
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.info.owner", data.owner() == null ? "-" : data.owner().toString()), false);
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.info.exterior", data.exteriorDim().identifier().toString(), data.exteriorPos().toShortString(), data.exteriorFacing().getName()), false);
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.info.interior", data.interiorPos().toShortString(), data.interiorFacing().getName()), false);
        source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.info.spares", data.spareDoors().size()), false);
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.info.state", data.state().getSerializedName()), false);
        return 1;
    }

    private static int listAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var all = TardisManager.getRegistry(source.getServer()).all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.list.empty"), false);
            return 0;
        }
        int count = all.size();
        source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.list.header", count), false);
        for (TardisData d : all) {
            source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.list.entry",
                    d.id().toString(), d.exteriorDim().identifier().toString(),
                    d.exteriorPos().toShortString(), d.spareDoors().size()), false);
        }
        return count;
    }

    private static int doSetDoor(CommandSourceStack source, TardisData data, boolean open) {
        TardisManager.setDoorOpen(source.getServer(), data, open);
        source.sendSuccess(() -> Component.translatable(open ? "doctor_m.command.tardis.door.opened" : "doctor_m.command.tardis.door.closed", data.id().toString()), false);
        return 1;
    }

    private static int openAppearanceNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeNearby(ctx, (s, p, d) -> sendOpenAppearance(s, p, d));
    }

    private static int openAppearanceById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (s, p, d) -> sendOpenAppearance(s, p, d));
    }

    private static int sendOpenAppearance(CommandSourceStack source, @Nullable ServerPlayer player, TardisData data) {
        if (player == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.player_only"));
            return 0;
        }
        ServerPlayNetworking.send(player, new OpenAppearanceScreenPayload(data.id(), data.appearanceId()));
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.appearance.opened", data.id().toString()), false);
        return 1;
    }

    private static int openInteriorNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeNearby(ctx, (s, p, d) -> sendOpenInterior(s, p, d));
    }

    private static int openInteriorById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (s, p, d) -> sendOpenInterior(s, p, d));
    }

    private static int sendOpenInterior(CommandSourceStack source, @Nullable ServerPlayer player, TardisData data) {
        if (player == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.player_only"));
            return 0;
        }
        var list = TardisInteriorRegistry.getAvailableInteriors(source.getServer());
        ServerPlayNetworking.send(player, new OpenInteriorScreenPayload(data.id(), data.interiorId(), list));
        source.sendSuccess(() -> Component.translatable("doctor_m.command.tardis.interior.opened", data.id().toString()), false);
        return 1;
    }

    private static int setDestNearby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeNearby(ctx, (s, p, d) -> doSetDest(s, d, p.level().dimension(), readXyz(ctx)));
    }

    private static int setDestNearbyWithDim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeNearby(ctx, (s, p, d) -> doSetDestWithDim(s, d, readXyz(ctx), StringArgumentType.getString(ctx, "dim")));
    }

    private static int setDestById(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (s, p, d) -> doSetDest(s, d, s.getLevel().dimension(), readXyz(ctx)));
    }

    private static int setDestByIdWithDim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeById(ctx, (s, p, d) -> doSetDestWithDim(s, d, readXyz(ctx), StringArgumentType.getString(ctx, "dim")));
    }

    private static BlockPos readXyz(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"));
    }

    private static int doSetDest(CommandSourceStack source, TardisData data, ResourceKey<Level> dim, BlockPos pos) {
        TardisManager.setDestination(source.getServer(), data, dim, pos);
        source.sendSuccess(() -> Text.tr("doctor_m.command.tardis.dest.set", pos.getX(), pos.getY(), pos.getZ(), dim.identifier().toString()), true);
        return 1;
    }

    private static int doSetDestWithDim(CommandSourceStack source, TardisData data, BlockPos pos, String dimInput) {
        Identifier dimId = parseDestDimInput(dimInput);
        if (dimId == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.dest.bad_dim", dimInput));
            return 0;
        }
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel targetLevel = source.getServer().getLevel(dimKey);
        if (targetLevel == null && TardisManager.isTardisDimension(dimKey)) {
            targetLevel = DynamicDimensionManager.getOrCreate(source.getServer(), dimKey);
        }
        if (targetLevel == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.dest.dim_not_loaded", dimId.toString()));
            return 0;
        }
        return doSetDest(source, data, dimKey, pos);
    }

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
            source.sendFailure(Component.translatable("doctor_m.command.tardis.not_found", id.toString()));
        }
        return data;
    }

    public static Identifier parseDimInput(String input) {
        try {
            if (input.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
            }

            if (input.contains(":")) return Identifier.parse(input);
            if (input.startsWith("tardis/")) return Identifier.fromNamespaceAndPath("doctor_m", input);

            return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
        } catch (Exception e) {
            return null;
        }
    }

    private static Identifier parseDestDimInput(String input) {
        try {
            if ("overworld".equals(input)) return Identifier.withDefaultNamespace("overworld");
            if ("the_nether".equals(input) || "nether".equals(input)) return Identifier.withDefaultNamespace("the_nether");
            if ("the_end".equals(input) || "end".equals(input)) return Identifier.withDefaultNamespace("the_end");
            if (input.contains(":")) return Identifier.parse(input);
            return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
        } catch (Exception e) {
            return null;
        }
    }
}