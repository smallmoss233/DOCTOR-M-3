package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.client.tardis.appearance.TardisAsset;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public class TardisDoorRenderer
        implements BlockEntityRenderer<TardisDoorBlockEntity, TardisDoorRenderState> {

    private static final Direction MODEL_FACING = Direction.SOUTH;

    private static final Map<BlockPos, AnimState> ANIM_STATES = new HashMap<>();
    private record AnimState(boolean lastOpen, long startNs, long lastSeenMs) {}

    public TardisDoorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public TardisDoorRenderState createRenderState() {
        return new TardisDoorRenderState();
    }

    @Override
    public void extractRenderState(TardisDoorBlockEntity be, TardisDoorRenderState state,
                                   float partialTicks, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(
                be, state, partialTicks, cameraPos, breakProgress);

        state.appearanceId = be.getAppearance();
        state.open = be.isOpen();
        state.exterior = be.isExterior();
        state.fadeAlpha = be.getFadeAlpha();          // ← 新增

        BlockState s = be.getBlockState();
        if (s.getBlock() instanceof AbstractTardisDoorBlock) {
            state.facing = s.getValue(AbstractTardisDoorBlock.FACING);
            state.renderThis =
                    s.getValue(AbstractTardisDoorBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
        }

        BlockPos pos = be.getBlockPos();
        boolean isOpen = be.isOpen();
        long nowNs = System.nanoTime();
        long nowMs = Util.getMillis();

        AnimState prev = ANIM_STATES.get(pos);
        long startNs = (prev == null || prev.lastOpen() != isOpen) ? nowNs : prev.startNs();
        ANIM_STATES.put(pos, new AnimState(isOpen, startNs, nowMs));

        if ((nowMs & 0x1FFL) == 0L) {
            ANIM_STATES.entrySet().removeIf(e -> nowMs - e.getValue().lastSeenMs() > 10_000L);
        }

        state.animElapsedSec = (nowNs - startNs) / 1_000_000_000f;
        state.animTarget = isOpen;
    }

    @Override
    public void submit(TardisDoorRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.renderThis) return;
        Identifier appId = state.appearanceId;
        if (appId == null) return;

        TardisAppearance app = TardisAppearanceRegistry.get(appId);
        if (app == null) return;

        TardisAsset asset = state.exterior ? app.exterior() : app.interior();
        if (!(asset instanceof TardisAsset.Bedrock b)) return;

        BedrockModelRef ref = BedrockRenderPipeline.resolve(b.geometry(), b.texture());
        if (ref == null) return;

        String animName = state.animTarget ? b.openAnimation() : b.closeAnimation();
        BedrockRenderPipeline.SampledAnim sa =
                BedrockRenderPipeline.sample(b.animation(), animName, state.animElapsedSec);

        // ★ 根据 alpha 选择渲染类型
        RenderType rt = state.fadeAlpha < 0.999f
                ? RenderTypes.translucentMovingBlock()
                : RenderTypes.cutoutMovingBlock();

        BedrockRenderPipeline.render(
                poseStack, collector, ref,
                sa.anim(), sa.time(),
                state.facing, MODEL_FACING,
                b.offsetX(), b.offsetY(), b.offsetZ(), b.scale(),
                state.lightCoords,
                rt,
                state.fadeAlpha);   // ← 新增两个参数
    }
}