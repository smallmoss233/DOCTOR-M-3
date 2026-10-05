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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 门方块渲染入口。自己只负责：
 *   1) 从 BlockEntity 状态取模型 ID / 动画名 / 光照 / 朝向
 *   2) 算出当前动画时刻
 *   3) 交给 {@link BedrockRenderPipeline} 完成解析 + 采样 + 渲染
 */
public class TardisDoorRenderer
        implements BlockEntityRenderer<TardisDoorBlockEntity, TardisDoorRenderState> {

    /**
     * 模型在 Blockbench 里"正面"朝向哪个方向。
     * 如果发现门永远面朝反方向，把这里改成 {@link Direction#NORTH} 即可。
     */
    private static final Direction MODEL_FACING = Direction.NORTH;

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

        BlockState s = be.getBlockState();
        if (s.getBlock() instanceof AbstractTardisDoorBlock) {
            state.facing = s.getValue(AbstractTardisDoorBlock.FACING);
        }

        BlockPos pos = be.getBlockPos();
        boolean isOpen = be.isOpen();
        long nowNs = System.nanoTime();       // 纳秒时钟，单调，不会倒退
        long nowMs = Util.getMillis();        // 毫秒时钟，仅用于惰性清理的"最后可见时间"

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
        Identifier appId = state.appearanceId;
        if (appId == null) return;

        TardisAppearance app = TardisAppearanceRegistry.get(appId);
        if (app == null) return;

        TardisAsset asset = state.exterior ? app.exterior() : app.interior();
        if (!(asset instanceof TardisAsset.Bedrock b)) return;

        BedrockModelRef ref = BedrockRenderPipeline.resolve(b.geometry(), b.texture());
        if (ref == null) return;

        // 动画采样：open 状态 → openAnimation，close 状态 → closeAnimation
        String animName = state.animTarget ? b.openAnimation() : b.closeAnimation();
        BedrockRenderPipeline.SampledAnim sa =
                BedrockRenderPipeline.sample(b.animation(), animName, state.animElapsedSec);

        BedrockRenderPipeline.render(
                poseStack, collector, ref,
                sa.anim(), sa.time(),
                state.facing, MODEL_FACING,
                b.offsetX(), b.offsetY(), b.offsetZ(), b.scale(),
                state.lightCoords);
    }
}