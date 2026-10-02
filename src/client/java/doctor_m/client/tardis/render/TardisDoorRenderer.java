package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.client.tardis.appearance.TardisModelKeys;
import doctor_m.client.tardis.appearance.TardisModelTransforms;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class TardisDoorRenderer
        implements BlockEntityRenderer<TardisDoorBlockEntity, TardisDoorRenderState> {

    public TardisDoorRenderer(BlockEntityRendererProvider.Context ctx) {}

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
        // 日志删掉
    }

    @Override
    public void submit(TardisDoorRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        Identifier appId = state.appearanceId;
        if (appId == null) return;

        TardisAppearance app = TardisAppearanceRegistry.get(appId);
        if (app == null) return;

        Identifier modelId = state.exterior
                ? (state.open ? app.exteriorOpen() : app.exteriorClosed())
                : (state.open ? app.interiorOpen() : app.interiorClosed());
        if (modelId == null) return;

        var mm = net.minecraft.client.Minecraft.getInstance().getModelManager();
        BlockStateModel model = ((net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager) mm)
                .getModel(TardisModelKeys.of(modelId));
        if (model == null) return;

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0), parts);
        if (parts.isEmpty()) return;

        float rot = 180.0F - state.facing.toYRot();

        poseStack.pushPose();

        // 1) 原点移到方块中心
        poseStack.translate(0.5, 0.0, 0.5);

        // 2) ★ 应用模型 display.fixed 里的偏移 / 旋转 / 缩放
        ItemTransform tf = TardisModelTransforms.get(modelId);
        if (tf != null) {
            // 偏移（单位已经是"格"）
            poseStack.translate(
                    (double) tf.translation().x(),
                    (double) tf.translation().y(),
                    (double) tf.translation().z());

            // 旋转（度 → 弧度，顺序 XYZ 跟原版 apply() 一致）
            poseStack.mulPose(new org.joml.Matrix4f().rotationXYZ(
                    (float) Math.toRadians(tf.rotation().x()),
                    (float) Math.toRadians(tf.rotation().y()),
                    (float) Math.toRadians(tf.rotation().z())));

            // 缩放
            poseStack.scale(
                    tf.scale().x(),
                    tf.scale().y(),
                    tf.scale().z());
        }

        // 3) 绕方块中心转向 FACING
        poseStack.mulPose(new org.joml.Matrix4f().rotationY((float) Math.toRadians(rot)));

        // 4) 原点还原回方块角落
        poseStack.translate(-0.5, 0.0, -0.5);

        collector.submitBlockModel(
                poseStack,
                net.minecraft.client.renderer.rendertype.RenderTypes.solidMovingBlock(),
                parts,
                new int[0],
                state.lightCoords,
                0,
                0
        );

        poseStack.popPose();
    }
}