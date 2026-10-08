package doctor_m;

import doctor_m.command.TardisCommand;
import doctor_m.network.DMNetwork;
import doctor_m.register.*;
import doctor_m.stp.StpChunkPump;
import doctor_m.stp.StpManager;
import doctor_m.stp.StpPackets;
import doctor_m.stp.StpServerState;
import doctor_m.stp.StpTrigger;
import doctor_m.tardis.TardisManager;
import mosslib.dimension.DynamicDimensionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DOCTOR_M implements ModInitializer {
    public static final String MOD_ID = "doctor_m";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[DM] DOCTOR M3 initializing...");

        DMBlocks.register();
        DMItems.register();
        DMCreativeTabs.register();
        DMBlockEntities.register();
        DMSounds.register();
        DMNetwork.register();

        // 生命周期
        ServerLifecycleEvents.SERVER_STARTED.register(server -> DynamicDimensionManager.loadAll(server));
        ServerTickEvents.END_SERVER_TICK.register(TardisManager::tickAll);

        // 命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TardisCommand.register(dispatcher));

        StpPackets.register();
        ServerTickEvents.END_SERVER_TICK.register(StpTrigger::tick);

        // 玩家断线：丢弃尚未发完的区块队列，否则会一直对空连接发包
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var id = handler.getPlayer().getUUID();
            StpChunkPump.onPlayerDisconnect(id);
            StpServerState.clearPlayer(id);
        });

        // C2S 区块请求
        ServerPlayNetworking.registerGlobalReceiver(
                StpPackets.CHUNK_REQUEST_C2S,
                (payload, context) -> context.server().execute(() -> {
                    MinecraftServer server = context.server();
                    ServerPlayer player = context.player();
                    ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, payload.dimensionId());
                    ServerLevel level = server.getLevel(dimKey);
                    if (level == null) return;
                    StpManager.sendChunk(server, player, level, new ChunkPos(payload.chunkX(), payload.chunkZ()));
                })
        );

        LOGGER.info("[DM] DOCTOR M3 initialized.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}