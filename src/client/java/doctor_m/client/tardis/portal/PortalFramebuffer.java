package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;

public class PortalFramebuffer {
    private final TextureTarget target;

    public PortalFramebuffer() {
        // 保证 FBO 具有 RGBA8 颜色和 D32 深度
        this.target = new TextureTarget("Tardis_Portal_Target", 1920, 1080, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
    }

    public void resize(int width, int height) {
        if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
    }

    public TextureTarget getTarget() {
        return target;
    }

    public GpuTextureView getColorTextureView() {
        return target.getColorTextureView();
    }
}