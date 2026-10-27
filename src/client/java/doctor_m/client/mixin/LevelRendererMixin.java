package doctor_m.client.mixin;

import doctor_m.client.tardis.portal.PortalMaskRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import com.mojang.renderpearl.api.commands.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void onExecuteSolid(ChunkSectionsToRender chunkSectionsToRender,
                                FeatureRenderDispatcher.PreparedFrame featureFrame,
                                RenderPass renderPass, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        PortalMaskRenderer.renderMask(renderPass, camera);
    }
}