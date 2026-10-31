package doctor_m.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import doctor_m.client.tardis.portal.PortalMaskRenderer;
import doctor_m.client.tardis.portal.PortalViewManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // ★ 核心修复：在整帧渲染开始前（此时没有任何 RenderPass 在激活状态），先执行离屏渲染
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 把对面世界画进 FBO
        PortalViewManager.get().renderOffscreenPass();
    }

    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void onExecuteSolidHead(ChunkSectionsToRender chunkSectionsToRender,
                                    FeatureRenderDispatcher.PreparedFrame featureFrame,
                                    RenderPass renderPass, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 1. 在主渲染 Pass 中，写入深度遮罩（这一步是安全的，因为我们复用已有的 renderPass）
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        PortalMaskRenderer.renderMask(renderPass, camera);
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void onExecuteSolidTail(ChunkSectionsToRender chunkSectionsToRender,
                                    FeatureRenderDispatcher.PreparedFrame featureFrame,
                                    RenderPass renderPass, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 2. 在主渲染 Pass 的末尾，把离屏渲染好的画面贴在门框上
        PortalViewManager.get().renderPortalQuad(renderPass);
    }
}