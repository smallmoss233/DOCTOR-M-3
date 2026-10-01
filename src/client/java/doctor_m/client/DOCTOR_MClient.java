package doctor_m.client;

import doctor_m.client.stp.StpClientState;
import doctor_m.stp.StpPackets;
import mosslib.api.ClientHandlers;
import net.fabricmc.api.ClientModInitializer;

public class DOCTOR_MClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
        ClientHandlers.handle(StpPackets.PREPARE_S2C, StpClientState::onPrepare);
        ClientHandlers.handle(StpPackets.CHUNK_S2C,    StpClientState::onChunkData);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> StpClientState.clear());
	}
}