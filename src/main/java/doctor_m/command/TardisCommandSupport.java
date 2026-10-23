package doctor_m.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 命令层的共用件：参数建议、执行器封装、ID / 维度解析。
 *
 * <h2>两种寻址方式</h2>
 * 大多数子命令同时支持两种目标解析：
 * <ul>
 *   <li><b>附近</b>（{@link #executeNearby}）—— 站在 TARDIS 里、或站在外壳附近时，
 *       由 {@code TardisManager#findContextTardis} 推断上下文</li>
 *   <li><b>按 ID</b>（{@link #executeById}）—— 显式传 UUID，供远程/后台操作</li>
 * </ul>
 * 业务实现只关心 {@link TardisExecutor} 这一层，不必重复写解析逻辑。
 */
public final class TardisCommandSupport {

    private TardisCommandSupport() {}

    // =========================================================
    //                      参数建议
    // =========================================================

    /** 建议所有已注册 TARDIS 的 UUID。 */
    public static final SuggestionProvider<CommandSourceStack> TARDIS_IDS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            builder.suggest(d.id().toString());
        }
        return builder.buildFuture();
    };

    /**
     * 目的地命令的维度建议。
     *
     * <p>建议纯 UUID（不带 {@code tardis/} 前缀）—— {@link #parseDimInput} 会补全。
     */
    public static final SuggestionProvider<CommandSourceStack> DIMENSION_IDS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        for (TardisData d : TardisManager.getRegistry(server).all()) {
            builder.suggest(d.id().toString());
        }
        return builder.buildFuture();
    };

    // =========================================================
    //                      执行器封装
    // =========================================================

    /** 一段"拿到 TARDIS 之后要做的事"。 */
    @FunctionalInterface
    public interface TardisExecutor {
        int execute(CommandSourceStack source, @Nullable ServerPlayer player, TardisData data)
                throws CommandSyntaxException;
    }

    /** 按"玩家附近"解析目标并执行；找不到时发失败消息。 */
    public static int executeNearby(CommandContext<CommandSourceStack> ctx, TardisExecutor action)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        TardisData data = TardisManager.findContextTardis(source.getServer(), player);
        if (data == null) {
            source.sendFailure(Component.translatable("doctor_m.command.tardis.nearby.none"));
            return 0;
        }
        return action.execute(source, player, data);
    }

    /** 按 {@code id} 参数解析目标并执行。 */
    public static int executeById(CommandContext<CommandSourceStack> ctx, TardisExecutor action)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TardisData data = resolveById(source, StringArgumentType.getString(ctx, "id"));
        if (data == null) return 0;
        return action.execute(source, source.getPlayer(), data);
    }

    /** UUID 字符串 → TardisData；失败时已发失败消息，返回 null。 */
    public static @Nullable TardisData resolveById(CommandSourceStack source, String raw) {
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

    /** 读 {@code x y z} 三个 double 参数并取整为方块坐标。 */
    public static BlockPos readXyz(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(
                DoubleArgumentType.getDouble(ctx, "x"),
                DoubleArgumentType.getDouble(ctx, "y"),
                DoubleArgumentType.getDouble(ctx, "z"));
    }

    // =========================================================
    //                      维度解析
    // =========================================================

    /**
     * 解析维度输入的宽松形式。
     *
     * <p>接受：纯 UUID（补 {@code doctor_m:tardis/<uuid>}）、
     * {@code tardis/<uuid>}、{@code namespace:path}。
     *
     * @return 解析结果；格式非法返回 null
     */
    public static @Nullable Identifier parseDimInput(String input) {
        try {
            if (input.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
            }
            if (input.contains(":")) return Identifier.parse(input);
            if (input.startsWith("tardis/")) return Identifier.fromNamespaceAndPath("doctor_m", input);
            return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 {@code dest} 子命令的维度输入。
     *
     * <p>比 {@link #parseDimInput} 多支持三个原版维度别名（{@code overworld} /
     * {@code nether} / {@code end}），因为设目的地时最常去的就是这三处。
     */
    public static @Nullable Identifier parseDestDimInput(String input) {
        try {
            if ("overworld".equals(input)) return Identifier.withDefaultNamespace("overworld");
            if ("the_nether".equals(input) || "nether".equals(input)) {
                return Identifier.withDefaultNamespace("the_nether");
            }
            if ("the_end".equals(input) || "end".equals(input)) {
                return Identifier.withDefaultNamespace("the_end");
            }
            if (input.contains(":")) return Identifier.parse(input);
            return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + input);
        } catch (Exception e) {
            return null;
        }
    }
}
