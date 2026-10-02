package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.tardis.anim.TardisAnimCache;
import doctor_m.client.tardis.anim.TardisAnimModel;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.client.tardis.appearance.TardisModelTransforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

public class TardisDoorRenderer
        implements BlockEntityRenderer<TardisDoorBlockEntity, TardisDoorRenderState> {

    /** 客户端动画状态：方块位置 → {上次开状态, 动画起点时间}。 */
    private static final Map<BlockPos, AnimState> ANIM_STATES = new HashMap<>();
    private static final long ANIM_DURATION_MS = 400L;

    private record AnimState(boolean lastOpen, long startMs) {}

    public TardisDoorRenderer(BlockEntityRendererProvider.Context ctx) {}

    private static RenderType tardisSolid(Identifier texture) {
        return RenderTypes.solidMovingBlock();
    }

    @Override
    public TardisDoorRenderState createRenderState() {
        return new TardisDoorRenderState();
    }

    @Override
    public void extractRenderState(TardisDoorBlockEntity be, TardisDoorRenderState state,
                                   float partialTicks, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPos, breakProgress);

        state.appearanceId = be.getAppearance();
        state.open = be.isOpen();
        state.exterior = be.isExterior();

        BlockState s = be.getBlockState();
        if (s.getBlock() instanceof AbstractTardisDoorBlock) {
            state.facing = s.getValue(AbstractTardisDoorBlock.FACING);
        }

        // ---- 开门/关门动画进度 ----
        BlockPos pos = be.getBlockPos();
        AnimState prev = ANIM_STATES.get(pos);
        boolean isOpen = be.isOpen();
        long now = Util.getMillis();

        if (prev == null || prev.lastOpen() != isOpen) {
            ANIM_STATES.put(pos, new AnimState(isOpen, now));
            state.openProgress = isOpen ? 1f : 0f;
        } else {
            float t = Mth.clamp((now - prev.startMs()) / (float) ANIM_DURATION_MS, 0f, 1f);
            state.openProgress = isOpen ? t : 1f - t;
        }
    }

    @Override
    public void submit(TardisDoorRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        Identifier appId = state.appearanceId;
        if (appId == null) return;

        TardisAppearance app = TardisAppearanceRegistry.get(appId);
        if (app == null) return;

        Identifier closedId = state.exterior ? app.exteriorClosed() : app.interiorClosed();
        Identifier openId   = state.exterior ? app.exteriorOpen()   : app.interiorOpen();
        if (closedId == null || openId == null) return;

        TardisAnimModel closed = TardisAnimCache.get(closedId);
        TardisAnimModel open   = TardisAnimCache.get(openId);
        if (closed == null || open == null) return;

        float progress = Mth.clamp(state.openProgress, 0f, 1f);

        float rot = 180.0F - state.facing.toYRot();

        poseStack.pushPose();
        poseStack.translate(0.5, 0.0, 0.5);

        ItemTransform tf = TardisModelTransforms.get(closedId);
        if (tf != null) {
            poseStack.translate(tf.translation().x(), tf.translation().y(), tf.translation().z());
            poseStack.mulPose(new Matrix4f().rotationXYZ(
                    (float) Math.toRadians(tf.rotation().x()),
                    (float) Math.toRadians(tf.rotation().y()),
                    (float) Math.toRadians(tf.rotation().z())));
            poseStack.scale(tf.scale().x(), tf.scale().y(), tf.scale().z());
        }

        poseStack.mulPose(new Matrix4f().rotationY((float) Math.toRadians(rot)));
        poseStack.translate(-0.5, 0.0, -0.5);

        Identifier tex = closed.texture();
        if (tex == null) return;
        RenderType rt = tardisSolid(tex);

        // 1) 静态部分
        submitGeometry(poseStack, collector, rt, closed.staticFaces(), tex, state.lightCoords);

        // 2) 每个动画组
        for (var entry : closed.groups().entrySet()) {
            String name = entry.getKey();
            TardisAnimModel.Group cg = entry.getValue();
            TardisAnimModel.Group og = open.groups().get(name);
            if (og == null) continue;

            Vector3f pivot = cg.pivot();
            Quaternionf q = new Quaternionf(cg.rotation()).slerp(og.rotation(), progress);

            poseStack.pushPose();
            poseStack.translate(pivot.x(), pivot.y(), pivot.z());
            poseStack.mulPose(new Matrix4f().rotation(q));
            poseStack.translate(-pivot.x(), -pivot.y(), -pivot.z());

            submitGeometry(poseStack, collector, rt, cg.faces(), tex, state.lightCoords);

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    // ================================================================
    //                      顶点提交
    // ================================================================

    private static void submitGeometry(PoseStack poseStack,
                                       SubmitNodeCollector collector,
                                       RenderType renderType,
                                       java.util.List<TardisAnimModel.Face> faces,
                                       Identifier textureId,
                                       int lightCoords) {
        if (faces.isEmpty()) return;

        var tm = net.minecraft.client.Minecraft.getInstance().getTextureManager();
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas)
                tm.getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var sprite = atlas.getSprite(textureId);

        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float du = sprite.getU1() - u0;
        float dv = sprite.getV1() - v0;

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            for (TardisAnimModel.Face f : faces) {
                float[] p = f.positions();
                float[] uv = f.uvs();

                for (int i = 0; i < 4; i++) {
                    buffer.addVertex(pose.pose(), p[i*3], p[i*3+1], p[i*3+2]);
                    buffer.setColor(0xFFFFFFFF);
                    buffer.setUv(u0 + uv[i*2] * du, v0 + uv[i*2+1] * dv);
                    buffer.setLight(lightCoords);
                }
            }
        });
    }
}