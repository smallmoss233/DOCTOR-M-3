package doctor_m.client.gui.pip;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mosslib.core.bedrock.BedrockGeometryModel;
import mosslib.core.bedrock.BoundingBox;
import mosslib.core.render.BedrockModelRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

public class GuiBedrockModelRenderer extends PictureInPictureRenderer<GuiBedrockModelRenderState> {

    /** 模型最大边长在 PiP 里占多少 Bedrock 像素。唯一的构图调参点。 */
    private static final float TARGET_PIXELS = 90f;

    /**
     * 包围盒只取决于几何体本身，而预览每帧都会调用一次。
     *
     * <p>单条缓存足够：界面一次只预览一个模型，切换时重算一次即可。
     * 缓存实例字段而不是静态 map —— 后者会长期持有已被资源重载替换掉的旧几何体。
     */
    private BedrockGeometryModel cachedGeo;
    private BoundingBox.Box cachedBox;

    @Override
    public Class<GuiBedrockModelRenderState> getRenderStateClass() {
        return GuiBedrockModelRenderState.class;
    }

    @Override
    protected void renderToTexture(GuiBedrockModelRenderState state,
                                   PoseStack poseStack,
                                   SubmitNodeCollector collector) {

        BedrockGeometryModel geo = state.geometry();

        // 统一的包围盒计算：正确沿骨骼 pivot 链与骨骼旋转累积变换。
        // 旧版直接把所有 cube 的 origin 取极值、忽略骨骼，模型中心必然偏掉
        // （警亭根骨骼 pivot 是 [0, 22.2, 0]，偏差非常明显）。
        if (geo != cachedGeo || cachedBox == null) {
            cachedGeo = geo;
            cachedBox = BoundingBox.of(geo);
        }
        BoundingBox.Box box = cachedBox;
        float maxDim = box.maxSize();
        float s = maxDim <= 0f ? 1f : TARGET_PIXELS / maxDim;

        poseStack.pushPose();
        try {
            poseStack.scale(s, s, s);
            poseStack.translate(-box.centerX(), -box.centerY(), -box.centerZ());

            poseStack.rotateDegrees(Axis.XP, 180f);
            poseStack.rotateDegrees(Axis.YP, state.yawDegrees());

            BedrockModelRenderer.render(
                    poseStack, collector,
                    geo, state.sprite(),
                    RenderTypes.cutoutMovingBlock(),
                    null, 0f, 0xF000F0, 1.0f);
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    protected String getTextureLabel() {
        return "tardis bedrock model";
    }
}
