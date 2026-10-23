package doctor_m.client.command;

import com.mojang.brigadier.CommandDispatcher;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.client.tardis.appearance.TardisAppearanceAssets;
import mosslib.core.bedrock.BedrockCache;
import mosslib.util.Text;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客户端基岩模型调试命令：{@code /dmbedrock}
 * <p>纯客户端命令，不需要服务端权限。
 */
public final class BedrockDebugCommand {

    private BedrockDebugCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                register(dispatcher));
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("dmbedrock")
                .then(ClientCommands.literal("report")
                        .executes(ctx -> report(ctx.getSource())))
                .then(ClientCommands.literal("check")
                        .executes(ctx -> checkAll(ctx.getSource())))
                .then(ClientCommands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource())))
        );
    }

    // =========================================================
    //                      report
    // =========================================================

    private static int report(FabricClientCommandSource source) {
        BedrockCache.Report report = BedrockCache.report();

        header(source, Component.translatable("doctor_m.command.debug.bedrock.header").getString());
        info(source, Component.translatable("doctor_m.command.debug.bedrock.geometry_count").getString(), String.valueOf(report.geometryCount()));
        info(source, Component.translatable("doctor_m.command.debug.bedrock.animation_count").getString(), String.valueOf(report.animationCount()));

        if (!report.hasProblems()) {
            source.sendFeedback(Component.translatable("doctor_m.command.debug.bedrock.no_problems")
                    .withStyle(ChatFormatting.GREEN));
            return 1;
        }

        if (!report.failedGeometry().isEmpty()) {
            source.sendFeedback(Component.translatable("doctor_m.command.debug.bedrock.failed_geometry")
                    .withStyle(ChatFormatting.RED));
            for (Identifier id : report.failedGeometry()) {
                source.sendFeedback(Component.literal("    " + id)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (!report.failedAnimation().isEmpty()) {
            source.sendFeedback(Component.translatable("doctor_m.command.debug.bedrock.failed_animation")
                    .withStyle(ChatFormatting.RED));
            for (Identifier id : report.failedAnimation()) {
                source.sendFeedback(Component.literal("    " + id)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (!report.issues().isEmpty()) {
            source.sendFeedback(Component.translatable("doctor_m.command.debug.bedrock.issues")
                    .withStyle(ChatFormatting.YELLOW));
            for (Map.Entry<Identifier, String> e : report.issues().entrySet()) {
                source.sendFeedback(Component.literal("    " + e.getKey())
                        .withStyle(ChatFormatting.GRAY));
                source.sendFeedback(Component.literal("      " + e.getValue())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return 1;
    }

    // =========================================================
    //                      check
    // =========================================================

    /** 逐个外观尝试加载它引用的全部资源，报告任何问题。 */
    private static int checkAll(FabricClientCommandSource source) {
        Map<Identifier, TardisAppearance> all = TardisAppearanceRegistry.all();
        if (all.isEmpty()) {
            source.sendError(Component.translatable("doctor_m.command.debug.bedrock.no_appearances"));
            return 0;
        }

        header(source, Text.tr("doctor_m.command.debug.bedrock.check_header", all.size()).getString());

        int bad = 0;
        List<Identifier> failed = new ArrayList<>();

        for (TardisAppearance app : all.values()) {
            List<String> problems = TardisAppearanceAssets.warmUp(app);
            if (problems.isEmpty()) continue;

            bad++;
            failed.add(app.id());
            source.sendFeedback(Component.literal("  ✗ " + app.displayName()
                    + " (" + app.id() + ")").withStyle(ChatFormatting.RED));
            for (String p : problems) {
                source.sendFeedback(Component.literal("      " + p)
                        .withStyle(ChatFormatting.GRAY));
            }
        }

        if (bad == 0) {
            source.sendFeedback(Text.tr("doctor_m.command.debug.bedrock.all_good", all.size())
                    .withStyle(ChatFormatting.GREEN));
        } else {
            source.sendFeedback(Text.tr("doctor_m.command.debug.bedrock.some_bad", bad, all.size())
                    .withStyle(ChatFormatting.YELLOW));
        }
        return bad == 0 ? 1 : 0;
    }

    // =========================================================
    //                      reload
    // =========================================================

    /** 清空缓存，下次请求时重新从资源包读取。 */
    private static int reload(FabricClientCommandSource source) {
        BedrockCache.invalidateAll();
        source.sendFeedback(Component.translatable("doctor_m.command.debug.bedrock.reload")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // =========================================================
    //                      输出
    // =========================================================

    private static void header(FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal("[DM] " + text)
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
    }

    private static void info(FabricClientCommandSource source, String label, String value) {
        source.sendFeedback(Component.literal("  " + label + "：")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
    }
}