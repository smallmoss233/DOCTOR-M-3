package doctor_m.client;

import doctor_m.client.command.BedrockDebugCommand;
import doctor_m.client.gui.pip.GuiBedrockModelRenderer;
import doctor_m.client.network.DMClientNetwork;
import doctor_m.client.stp.StpClientState;
import doctor_m.client.tardis.appearance.TardisAppearancePlugin;
import doctor_m.client.tardis.render.BedrockCache;
import doctor_m.client.tardis.console.TardisConsoleLoader;
import doctor_m.client.tardis.render.TardisConsoleRenderer;
import doctor_m.client.tardis.render.TardisDoorRenderer;
import doctor_m.register.DMBlockEntities;
import doctor_m.stp.StpPackets;
import mosslib.api.ClientHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class DOCTOR_MClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
        TardisAppearancePlugin.register();
        TardisConsoleLoader.register();
        BedrockCache.register();
        BedrockDebugCommand.register();
        DMClientNetwork.register();

        PictureInPictureRendererRegistry.register(ctx -> new GuiBedrockModelRenderer());


        BlockEntityRenderers.register(DMBlockEntities.TARDIS_DOOR, TardisDoorRenderer::new);
        BlockEntityRenderers.register(DMBlockEntities.TARDIS_CONSOLE, TardisConsoleRenderer::new);

        ClientHandlers.handle(StpPackets.PREPARE_S2C, StpClientState::onPrepare);
        ClientHandlers.handle(StpPackets.CHUNK_S2C,    StpClientState::onChunkData);

        // 区块写入节流：把一次维度切换涌来的几百个区块摊到多帧上写。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> StpClientState.tick());

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> StpClientState.clear());
	}
}