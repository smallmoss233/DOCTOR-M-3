package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.block.TardisConsoleBlock;
import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.client.tardis.console.TardisConsoleAppearance;
import doctor_m.client.tardis.console.TardisConsoleRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 控制台渲染器。
 *
 * <p>比门渲染器简单：没有开关状态，只有一段从"首次可见"开始无限循环的 idle 动画。
 * 所有几何 / 动画 / 变换逻辑都在 {@link BedrockRenderPipeline} 里。
 */
public class TardisConsoleRenderer
        implements BlockEntityRenderer<TardisConsoleBlockEntity, TardisConsoleRenderState> {

    /** 每个控制台的"首次可见"时间戳（纳秒）。用于 idle 动画的起始点。 */
    private static final Map<BlockPos, Long> IDLE_START_NS = new HashMap<>();
    private static final Map<BlockPos, Long> LAST_SEEN_MS = new HashMap<>();

    public TardisConsoleRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public TardisConsoleRenderState createRenderState() {
        return new TardisConsoleRenderState();
    }

    @Override
    public void extractRenderState(TardisConsoleBlockEntity be, TardisConsoleRenderState state,
                                   float partialTicks, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(
                be, state, partialTicks, cameraPos, breakProgress);

        state.appearanceId = be.getAppearanceId();

        BlockState s = be.getBlockState();
        if (s.getBlock() instanceof TardisConsoleBlock) {
            state.facing = s.getValue(TardisConsoleBlock.FACING);
        }

        BlockPos pos = be.getBlockPos();
        long nowNs = System.nanoTime();
        long nowMs = Util.getMillis();

        IDLE_START_NS.putIfAbsent(pos, nowNs);
        LAST_SEEN_MS.put(pos, nowMs);

        // 惰性清理
        if ((nowMs & 0x1FFL) == 0L) {
            LAST_SEEN_MS.entrySet().removeIf(e -> {
                if (nowMs - e.getValue() > 10_000L) {
                    IDLE_START_NS.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }

        long startNs = IDLE_START_NS.getOrDefault(pos, nowNs);
        state.idleElapsedSec = (nowNs - startNs) / 1_000_000_000f;
    }

    @Override
    public void submit(TardisConsoleRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        Identifier appId = state.appearanceId;
        if (appId == null) return;

        TardisConsoleAppearance ca = TardisConsoleRegistry.get(appId);
        if (ca == null) return;

        BedrockModelRef ref = BedrockRenderPipeline.resolve(ca.geometry(), ca.texture());
        if (ref == null) return;

        BedrockRenderPipeline.SampledAnim sa = BedrockRenderPipeline.sample(
                ca.animation(), ca.idleAnimation(), state.idleElapsedSec);

        BedrockRenderPipeline.render(
                poseStack, collector, ref,
                sa.anim(), sa.time(),
                state.facing, ca.modelFacing(),
                ca.offsetX(), ca.offsetY(), ca.offsetZ(), ca.scale(),
                state.lightCoords,
                net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock(),   // ← RenderType
                1.0f);                                                                      // ← alpha
    }
}