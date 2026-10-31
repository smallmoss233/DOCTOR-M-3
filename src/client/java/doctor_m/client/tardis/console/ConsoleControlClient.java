package doctor_m.client.tardis.console;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.network.SyncConsoleControlsPayload;
import doctor_m.tardis.console.ConsoleControlLayout;
import doctor_m.tardis.console.TardisConsoleAppearance;
import doctor_m.tardis.console.TardisConsoleRegistry;
import mosslib.core.bedrock.BedrockCache;
import mosslib.core.bedrock.BedrockGeometryModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台控件的客户端发送逻辑。
 *
 * <p>BE 是双端类，不能直接引用客户端类。客户端在初始化时把本类的
 * {@link #send} 方法注册进 {@link TardisConsoleBlockEntity#setClientSender}，
 * 之后 BE 的 {@code clientTick} 就会调回来。
 */
public final class ConsoleControlClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/console_client");

    private ConsoleControlClient() {}

    /** 在客户端初始化时调一次。 */
    public static void register() {
        TardisConsoleBlockEntity.setClientSender(ConsoleControlClient::send);
    }

    /** BE 客户端 tick 调回来 —— 算 AABB 并发包。 */
    private static void send(TardisConsoleBlockEntity be) {
        TardisConsoleAppearance app = TardisConsoleRegistry.get(be.getAppearanceId());
        if (app == null) {
            LOGGER.debug("[console] no appearance for {}", be.getAppearanceId());
            return;
        }

        BedrockGeometryModel geo = BedrockCache.geometry(app.geometry());
        if (geo == null) {
            LOGGER.debug("[console] no geometry for {}", app.geometry());
            return;
        }

        List<ConsoleControlLayout.ControlBounds> bounds =
                ConsoleControlLayout.compute(geo, app, be.getBlockPos(), be.currentFacingPublic());

        LOGGER.debug("[console] sending {} controls for {}", bounds.size(), be.getBlockPos());

        List<SyncConsoleControlsPayload.Entry> entries = new ArrayList<>(bounds.size());
        for (ConsoleControlLayout.ControlBounds b : bounds) {
            entries.add(new SyncConsoleControlsPayload.Entry(
                    b.boneName(), b.worldBox(),
                    b.config().type().id(),
                    b.config().tiers(),
                    b.config().duration(),
                    b.config().tierMode().name(),
                    b.config().animationId()));
        }

        ClientPlayNetworking.send(new SyncConsoleControlsPayload(be.getBlockPos(), entries));
    }
}