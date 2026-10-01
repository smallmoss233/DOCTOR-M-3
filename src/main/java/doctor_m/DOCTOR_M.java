package doctor_m;

import doctor_m.command.TardisCommand;
import mosslib.dimension.DynamicDimensionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
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

        // 生命周期
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                DynamicDimensionManager.loadAll(server));

        // 命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TardisCommand.register(dispatcher));

        LOGGER.info("[DM] DOCTOR M3 initialized.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}