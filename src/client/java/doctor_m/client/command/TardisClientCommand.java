package doctor_m.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class TardisClientCommand {

    private TardisClientCommand() {}

    /** 简化字面量：显式指定泛型为 FabricClientCommandSource。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                dispatcher.register(
                        lit("doctor_m_client")
                                .then(lit("appearance")
                                        .then(lit("list")
                                                .executes(ctx -> listAppearances(ctx.getSource()))))
                )
        );
    }

    private static int listAppearances(FabricClientCommandSource source) {
        var all = TardisAppearanceRegistry.all();

        if (all.isEmpty()) {
            source.sendFeedback(Component.translatable(
                    "doctor_m.command.client.appearance.list.empty"));
            return 0;
        }

        source.sendFeedback(Component.translatable(
                "doctor_m.command.client.appearance.list.header", all.size()));

        for (TardisAppearance app : all.values()) {
            source.sendFeedback(Component.translatable(
                    "doctor_m.command.client.appearance.list.entry",
                    app.id(), app.displayName()));
        }
        return all.size();
    }
}