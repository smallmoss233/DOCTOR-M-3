package doctor_m.client;

import doctor_m.client.gui.pip.GuiBedrockModelRenderer;
import doctor_m.client.network.DMClientNetwork;
import doctor_m.client.stp.StpClientState;
import doctor_m.client.tardis.appearance.TardisAppearancePlugin;
import doctor_m.tardis.bedrock.BedrockCache;
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
        DMClientNetwork.register();

        PictureInPictureRendererRegistry.register(ctx -> new GuiBedrockModelRenderer());


        BlockEntityRenderers.register(DMBlockEntities.TARDIS_DOOR, TardisDoorRenderer::new);
        BlockEntityRenderers.register(DMBlockEntities.TARDIS_CONSOLE, TardisConsoleRenderer::new);

        ClientHandlers.handle(StpPackets.PREPARE_S2C, StpClientState::onPrepare);
        ClientHandlers.handle(StpPackets.CHUNK_S2C,    StpClientState::onChunkData);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> StpClientState.clear());
	}
}