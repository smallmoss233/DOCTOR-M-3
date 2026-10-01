package doctor_m.client.mixin;

import doctor_m.client.stp.StpClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    /** 复用预构建的 ClientLevelData。 */
    @Redirect(
            method = "handleRespawn",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/client/multiplayer/ClientLevel$ClientLevelData"
            )
    )
    private ClientLevel.ClientLevelData stp$newLevelData(
            Difficulty difficulty, boolean hardcore, boolean isFlat) {
        ClientLevel.ClientLevelData prepared = StpClientState.peekPreparedLevelData();
        if (prepared != null) return prepared;
        return new ClientLevel.ClientLevelData(difficulty, hardcore, isFlat);
    }

    /** 复用预构建的 ClientLevel。 */
    @Redirect(
            method = "handleRespawn",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/client/multiplayer/ClientLevel"
            )
    )
    private ClientLevel stp$newLevel(
            ClientPacketListener conn,
            ClientLevel.ClientLevelData levelData,
            ResourceKey<Level> dim,
            Holder<DimensionType> dimType,
            int chunkRadius,
            int simDist,
            LevelExtractor extractor,
            boolean isDebug,
            long seed,
            int seaLevel) {

        ClientLevel prepared = StpClientState.consumePreparedLevel();
        if (prepared != null) return prepared;
        return new ClientLevel(conn, levelData, dim, dimType, chunkRadius, simDist,
                extractor, isDebug, seed, seaLevel);
    }

    /** 跳过 loading screen。 */
    @Redirect(
            method = "startWaitingForNewLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;"
                            + "setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"
            )
    )
    private void stp$skipLoading(Minecraft mc, Screen screen) {
        if (StpClientState.consumeSuppress()) return;
        mc.setScreenAndShow(screen);
    }
}