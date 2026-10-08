package doctor_m.client.mixin;

import doctor_m.client.stp.StpClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 接管原版的维度切换，让穿门不出现加载界面与地形空洞。
 *
 * <p>原版 {@code ClientPacketListener#handleRespawn} 会新建一个
 * {@code ClientLevel}，然后进入"等待新区块"状态并弹出加载界面。
 * 这里把那个新建调用换成 STP 预构建好的实例，并抑制加载界面 ——
 * 玩家看到的就是从一个世界"直接"走进了另一个世界。
 *
 * <h2>为什么只拦截 ClientLevel，不拦截 ClientLevelData</h2>
 * 预构建的 {@code ClientLevel} 内部已经持有它自己的 {@code ClientLevelData}，
 * 换入它之后，原版构造参数里的那份 levelData 根本不会被使用。
 * 因此不需要第二处拦截 —— 少一处 Redirect 就少一份"两个拦截的消费顺序"耦合。
 *
 * <h2>安全前提</h2>
 * 只有当预构建实例的维度与本次换场目标维度一致时才会换入，
 * 由 {@link StpClientState#consumePreparedLevel(ResourceKey)} 把关。
 * 不一致就放弃预构建数据、退回原版行为：加载界面会重新出现，
 * 但绝不会把错误的世界显示给玩家。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    /**
     * 换入预构建的 ClientLevel。
     *
     * @param dim 原版本次想要切换到的维度 —— 用它校验预构建实例是否对得上
     */
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

        ClientLevel prepared = StpClientState.consumePreparedLevel(dim);
        if (prepared != null) return prepared;

        return new ClientLevel(conn, levelData, dim, dimType, chunkRadius, simDist,
                extractor, isDebug, seed, seaLevel);
    }

    /** 跳过 loading screen（仅在预构建数据有效时）。 */
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
