package doctor_m.client.gui.pip;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import doctor_m.client.tardis.render.BedrockModelRenderer;
import doctor_m.tardis.bedrock.BedrockGeometryModel;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

public class GuiBedrockModelRenderer extends PictureInPictureRenderer<GuiBedrockModelRenderState> {

    @Override
    public Class<GuiBedrockModelRenderState> getRenderStateClass() {
        return GuiBedrockModelRenderState.class;
    }

    @Override
    protected void renderToTexture(GuiBedrockModelRenderState state,
                                   PoseStack poseStack,
                                   SubmitNodeCollector collector) {

        BedrockGeometryModel geo = state.geometry();

        // 1) bbox
        float[] b = computeBoundingBox(geo);   // [minX,minY,minZ,maxX,maxY,maxZ]
        float cx = (b[0] + b[3]) * 0.5f;
        float cy = (b[1] + b[4]) * 0.5f;
        float cz = (b[2] + b[5]) * 0.5f;
        float maxDim = Math.max(b[3] - b[0],
                Math.max(b[4] - b[1], b[5] - b[2]));

        // 2) 唯一的调参点：模型最大边长在 PiP 里占多少 Bedrock 像素
        float targetPixels = 90f;
        float s = targetPixels / maxDim;

        poseStack.pushPose();
        try {
            poseStack.scale(s, s, s);
            poseStack.translate(-cx, -cy, -cz);

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

    private static float[] computeBoundingBox(BedrockGeometryModel geo) {
        float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (var root : geo.rootBones()) walkBone(root, box);
        return box;
    }

    private static void walkBone(BedrockGeometryModel.Bone bone, float[] box) {
        for (var c : bone.cubes()) {
            var o = c.origin(); var sz = c.size();
            box[0] = Math.min(box[0], o.x());         box[3] = Math.max(box[3], o.x() + sz.x());
            box[1] = Math.min(box[1], o.y());         box[4] = Math.max(box[4], o.y() + sz.y());
            box[2] = Math.min(box[2], o.z());         box[5] = Math.max(box[5], o.z() + sz.z());
        }
        for (var ch : bone.children()) walkBone(ch, box);
    }

    @Override
    protected String getTextureLabel() {
        return "tardis bedrock model";
    }
}