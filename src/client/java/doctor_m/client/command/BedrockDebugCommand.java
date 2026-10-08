package doctor_m.client.command;

import com.mojang.brigadier.CommandDispatcher;
import doctor_m.client.tardis.render.BedrockCache;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
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
 *
 * <p>存在的意义：基岩模型的故障模式是"模型或动画没出来"，而这类问题旧版只会往
 * 日志里写一行 warn，游戏里看不出任何线索。这个命令把
 * {@link BedrockCache} 的加载报告直接显示在聊天栏。
 *
 * <p>纯客户端命令，不需要服务端权限，专用服务器上也能用。
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

    // 注意：这里曾经有一个 /dmbedrock rotation <order> 与 /dmbedrock rotation swap，
    // 用于在运行时全局切换 cube 的欧拉角定序与轴映射。
    //
    // 已移除。原因是全局切换无法成立：不同模型在不同时期导出，需要的定序不同，
    // 切一个必然把其他模型改坏（实测把塔迪斯门模型切崩了）。
    // 定序现在是 per-model 的，配置在外观 JSON 的 "rotation_order" 字段里，
    // 见 TardisConsoleLoader / TardisAppearancePlugin。
    //
    // 需要为某个新模型换定序时：在它的外观 JSON 里加一行
    //   "rotation_order": "XYZ"
    // 可用值：ZYX（默认）/ XYZ / YXZ / YZX / ZXY / XZY



    // =========================================================
    //                      report
    // =========================================================

    private static int report(FabricClientCommandSource source) {
        BedrockCache.Report report = BedrockCache.report();

        header(source, "Bedrock 缓存");
        info(source, "已加载几何体", String.valueOf(report.geometryCount()));
        info(source, "已加载动画文件", String.valueOf(report.animationCount()));

        if (!report.hasProblems()) {
            source.sendFeedback(Component.literal("  没有发现问题")
                    .withStyle(ChatFormatting.GREEN));
            return 1;
        }

        if (!report.failedGeometry().isEmpty()) {
            source.sendFeedback(Component.literal("  几何体加载失败：")
                    .withStyle(ChatFormatting.RED));
            for (Identifier id : report.failedGeometry()) {
                source.sendFeedback(Component.literal("    " + id)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (!report.failedAnimation().isEmpty()) {
            source.sendFeedback(Component.literal("  动画加载失败：")
                    .withStyle(ChatFormatting.RED));
            for (Identifier id : report.failedAnimation()) {
                source.sendFeedback(Component.literal("    " + id)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (!report.issues().isEmpty()) {
            source.sendFeedback(Component.literal("  解析有问题（仍可渲染）：")
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
            source.sendError(Component.literal("没有已注册的外观"));
            return 0;
        }

        header(source, "外观资源自检（" + all.size() + " 套）");

        int bad = 0;
        List<Identifier> failed = new ArrayList<>();

        for (TardisAppearance app : all.values()) {
            List<String> problems = BedrockCache.warmUp(app.id());
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
            source.sendFeedback(Component.literal("  全部 " + all.size() + " 套外观资源正常")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            source.sendFeedback(Component.literal("  " + bad + " / " + all.size()
                    + " 套外观存在问题").withStyle(ChatFormatting.YELLOW));
        }
        return bad == 0 ? 1 : 0;
    }

    // =========================================================
    //                      reload
    // =========================================================

    /** 清空缓存，下次请求时重新从资源包读取（改了模型 JSON 但不想重启游戏时用）。 */
    private static int reload(FabricClientCommandSource source) {
        BedrockCache.invalidateAll();
        source.sendFeedback(Component.literal("[DM] 已清空基岩模型缓存，将在下次渲染时重新读取")
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
