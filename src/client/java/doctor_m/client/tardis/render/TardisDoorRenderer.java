package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.tardis.portal.PortalViewManager;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import mosslib.core.bedrock.BedrockModelRef;
import mosslib.core.render.BedrockRenderPipeline;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public class TardisDoorRenderer
        implements BlockEntityRenderer<TardisDoorBlockEntity, TardisDoorRenderState> {

    private static final Direction MODEL_FACING = Direction.NORTH;

    /**
     * 每扇门的开关动画起点。
     *
     * <p>键用 BE 本身（弱引用）：方块实体被区块卸载回收时条目自动消失，
     * 也不会出现"不同维度的门恰好同坐标 → 共用一份动画状态"的串台。
     * 旧版用 BlockPos 作键 + {@code (nowMs & 0x1FF) == 0} 这个几乎命中不了的
     * 清理条件，地图只增不减。
     */
    private static final Map<TardisDoorBlockEntity, AnimState> ANIM_STATES = new WeakHashMap<>();
    /** 上次清理时间（毫秒）。 */
    private static long lastSweepMs;
    private static final long STATE_TTL_MS = 10_000L;

    private static final class AnimState {
        boolean lastOpen;
        long startNs;
        long lastSeenMs;
    }

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
        state.fadeAlpha = be.getFadeAlpha();

        // BOTI：对端门锚点（门户渲染用）
        state.counterpartPos = be.getCounterpartPos();
        state.counterpartFacing = be.getCounterpartFacing();

        BlockState s = be.getBlockState();
        if (s.getBlock() instanceof AbstractTardisDoorBlock) {
            state.facing = s.getValue(AbstractTardisDoorBlock.FACING);
            state.renderThis =
                    s.getValue(AbstractTardisDoorBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
        }

        boolean isOpen = be.isOpen();

        // ★ 将此门注册到 PortalViewManager 中，方便离屏渲染获取
        if (isOpen && state.counterpartPos != null) {
            PortalViewManager.get().trackActiveDoor(be);
        }

        long nowNs = System.nanoTime();
        long nowMs = Util.getMillis();

        AnimState anim = ANIM_STATES.get(be);
        if (anim == null) {
            anim = new AnimState();
            anim.startNs = nowNs;
            ANIM_STATES.put(be, anim);
        } else if (anim.lastOpen != isOpen) {
            anim.startNs = nowNs;
        }
        anim.lastOpen = isOpen;
        anim.lastSeenMs = nowMs;

        // 按"距上次清理是否满 10 秒"触发，读起来就是它想表达的意思。
        if (nowMs - lastSweepMs >= STATE_TTL_MS) {
            lastSweepMs = nowMs;
            ANIM_STATES.entrySet().removeIf(e -> nowMs - e.getValue().lastSeenMs > STATE_TTL_MS);
        }

        state.animElapsedSec = (nowNs - anim.startNs) / 1_000_000_000f;
        state.animTarget = isOpen;
    }

    @Override
    public void submit(TardisDoorRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.renderThis) return;
        submitDoorBody(state, poseStack, collector);
    }

    private void submitDoorBody(TardisDoorRenderState state, PoseStack poseStack,
                                SubmitNodeCollector collector) {
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

        RenderType rt = state.fadeAlpha < 0.999f
                ? RenderTypes.translucentMovingBlock()
                : RenderTypes.cutoutMovingBlock();

        BedrockRenderPipeline.render(
                poseStack, collector, ref,
                sa.anim(), sa.time(),
                state.facing, MODEL_FACING,
                b.offset().x, b.offset().y, b.offset().z, b.scale(),
                state.lightCoords,
                rt,
                state.fadeAlpha);
    }
}