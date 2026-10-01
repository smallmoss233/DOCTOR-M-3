package doctor_m.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import mosslib.dimension.DimensionDebug;
import mosslib.dimension.DimensionTemplate;
import mosslib.dimension.DynamicDimensionManager;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

public final class TardisCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/TardisCommand");

    private TardisCommand() {}

    /** `tardis/` 前缀——单点定义，便于将来改名。 */
    private static final String DIMENSION_PATH_PREFIX = "tardis/";

    /** 补全：列出所有动态维度的短 ID。 */
    private static final SuggestionProvider<CommandSourceStack> DYNAMIC_IDS = (ctx, builder) -> {
        for (var snap : DimensionDebug.listDynamic(ctx.getSource().getServer())) {
            String path = snap.key().identifier().getPath();
            if (path.startsWith(DIMENSION_PATH_PREFIX)) {
                builder.suggest(path.substring(DIMENSION_PATH_PREFIX.length()));
            }
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("doctor_m")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                // ============================================================
                //                      tardis（玩家向）
                // ============================================================
                .then(Commands.literal("tardis")

                        // ---------- create ---------- 用玩家 UUID
                        .then(Commands.literal("create")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    return createDimension(ctx.getSource(),
                                            keyForPlayer(player.getUUID()));
                                }))

                        // ---------- tp [id] ----------
                        .then(Commands.literal("tp")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    return teleportToTardis(player, ctx.getSource(),
                                            keyForPlayer(player.getUUID()));
                                })
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(DYNAMIC_IDS)
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            String id = StringArgumentType.getString(ctx, "id");
                                            return teleportToTardis(player, ctx.getSource(), keyForId(id));
                                        })))

                        // ---------- release ---------- 删除玩家自己的
                        .then(Commands.literal("release")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    return deleteDimension(ctx.getSource(),
                                            keyForPlayer(player.getUUID()));
                                })))

                // ============================================================
                //                      debug（GM 向）
                // ============================================================
                .then(Commands.literal("debug")
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    var list = DimensionDebug.listDynamic(ctx.getSource().getServer());
                                    if (list.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.translatable("doctor_m.command.debug.list.empty"),
                                                false);
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    "doctor_m.command.debug.list.header", list.size()),
                                            false);
                                    for (var snap : list) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.translatable(
                                                        "doctor_m.command.debug.list.entry",
                                                        snap.key().identifier(),
                                                        snap.playerCount(),
                                                        snap.entityCount(),
                                                        snap.loadedChunks(),
                                                        snap.pathExists() ? "§a✓" : "§c✗",
                                                        DimensionDebug.formatSize(snap.pathSizeBytes())),
                                                false);
                                    }
                                    return list.size();
                                }))

                        // ---------- debug create <id> ---------- 手动建
                        .then(Commands.literal("create")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            return createDimension(ctx.getSource(), keyForId(id));
                                        })))

                        // ---------- debug delete <id> ---------- 手动删
                        .then(Commands.literal("delete")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(DYNAMIC_IDS)
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            return deleteDimension(ctx.getSource(), keyForId(id));
                                        })))

                        .then(Commands.literal("save")
                                .executes(ctx -> {
                                    DimensionDebug.saveAll(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable("doctor_m.command.debug.save.success"),
                                            false);
                                    return 1;
                                }))

                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    DimensionDebug.unloadAll(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable("doctor_m.command.debug.reload.success"),
                                            false);
                                    return 1;
                                }))));
    }

    // ================================================================
    //                      命令实现
    // ================================================================

    private static int teleportToTardis(ServerPlayer player, CommandSourceStack source,
                                        ResourceKey<Level> key) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.no_server"));
            return 0;
        }

        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);
        if (level == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.create_failed"));
            return 0;
        }

        player.teleportTo(level, 0.5, 65.0, 0.5,
                Set.of(), player.getYRot(), player.getXRot(), false);

        LOGGER.info("[DM] Player {} teleported to {}", player.getName().getString(), key.identifier());

        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.tp.success", key.identifier().toString()),
                false);
        return 1;
    }

    private static int createDimension(CommandSourceStack source, ResourceKey<Level> key) {
        MinecraftServer server = source.getServer();
        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);
        if (level == null) {
            source.sendFailure(Component.translatable("doctor_m.command.error.create_failed"));
            return 0;
        }

        LOGGER.info("[DM] Dimension created via command: {}", key.identifier());

        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.create.success", key.identifier().toString()),
                false);
        return 1;
    }

    private static int deleteDimension(CommandSourceStack source, ResourceKey<Level> key) {
        MinecraftServer server = source.getServer();
        boolean ok = DynamicDimensionManager.delete(server, key);
        if (!ok) {
            source.sendFailure(Component.translatable(
                    "doctor_m.command.tardis.delete.fail", key.identifier().toString()));
            return 0;
        }

        LOGGER.info("[DM] Dimension deleted via command: {}", key.identifier());

        source.sendSuccess(
                () -> Component.translatable("doctor_m.command.tardis.delete.success", key.identifier().toString()),
                false);
        return 1;
    }

    // ================================================================
    //                      Key 派生
    // ================================================================

    private static ResourceKey<Level> keyForPlayer(UUID playerId) {
        return ResourceKey.create(
                Registries.DIMENSION,
                DimensionTemplate.dimensionIdFor(playerId));
    }

    private static ResourceKey<Level> keyForId(String shortId) {
        return ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("doctor_m", DIMENSION_PATH_PREFIX + shortId));
    }
}