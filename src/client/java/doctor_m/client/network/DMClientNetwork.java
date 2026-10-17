package doctor_m.client.network;

import doctor_m.client.gui.TardisAppearanceScreen;
import doctor_m.client.gui.TardisInteriorScreen; // 新增导入
import doctor_m.network.DMNetwork;
import mosslib.api.ClientHandlers;
import net.minecraft.client.Minecraft;

public final class DMClientNetwork {

    private DMClientNetwork() {}

    public static void register() {
        // 外观 UI
        ClientHandlers.handle(DMNetwork.OPEN_APPEARANCE_SCREEN, payload -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            mc.gui.setScreen(new TardisAppearanceScreen(
                    mc.player, payload.tardisId(), payload.appearanceId()));
        });

        // 内饰 UI
        ClientHandlers.handle(DMNetwork.OPEN_INTERIOR_SCREEN, payload -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            mc.gui.setScreen(new TardisInteriorScreen(
                    payload.tardisId(), payload.currentInterior(), payload.availableInteriors()));
        });
    }
}