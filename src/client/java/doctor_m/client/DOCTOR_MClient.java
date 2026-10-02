package doctor_m.client;

import doctor_m.DMBlockEntities;
import doctor_m.client.command.TardisClientCommand;
import doctor_m.client.stp.StpClientState;
import doctor_m.client.tardis.anim.TardisAnimCache;
import doctor_m.client.tardis.appearance.TardisAppearancePlugin;
import doctor_m.client.tardis.render.TardisDoorRenderer;
import doctor_m.stp.StpPackets;
import mosslib.api.ClientHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class DOCTOR_MClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
        TardisAppearancePlugin.register();
        TardisClientCommand.register();
        TardisAnimCache.register();

        BlockEntityRenderers.register(DMBlockEntities.TARDIS_DOOR, TardisDoorRenderer::new);

        BlockEntityRenderers.register(DMBlockEntities.TARDIS_DOOR, TardisDoorRenderer::new);

        ClientHandlers.handle(StpPackets.PREPARE_S2C, StpClientState::onPrepare);
        ClientHandlers.handle(StpPackets.CHUNK_S2C,    StpClientState::onChunkData);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> StpClientState.clear());
	}
}