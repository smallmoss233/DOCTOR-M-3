package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.block.TardisConsoleBlock;
import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.client.tardis.console.ConsoleAnimationController;
import doctor_m.tardis.console.TardisConsoleAppearance;
import doctor_m.tardis.console.TardisConsoleRegistry;
import mosslib.core.bedrock.BedrockAnimationModel;
import mosslib.core.bedrock.BedrockCache;
import mosslib.core.bedrock.BedrockModelRef;
import mosslib.core.render.BedrockRenderPipeline;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 控制台渲染器。
 *
 * <p>比门渲染器简单：没有开关状态，只有一段从"首次可见"开始无限循环的 idle 动画。
 * 所有几何 / 动画 / 变换逻辑都在 {@link BedrockRenderPipeline} 里。
 *
 * <h2>控件动画</h2>
 * 除了 idle，控制台上的每个控件实体（{@link doctor_m.tardis.console.control.ConsoleControlEntity}）
 * 还可以驱动它绑定的骨骼。overrides 表在 {@link #extractRenderState} 里组装
 * （那里能拿到 BE），在 {@link #submit} 里传给 {@link BedrockRenderPipeline}。
 */
public class TardisConsoleRenderer
        implements BlockEntityRenderer<TardisConsoleBlockEntity, TardisConsoleRenderState> {

    /**
     * 每个控制台的 idle 动画起点与最后可见时间。
     *
     * <p>键用 BE 弱引用：方块实体卸载回收时条目随之消失，不会像旧的
     * BlockPos 键那样无限增长；顺带修掉"不同维度同坐标控制台共用 idle 相位"。
     */
    private static final Map<TardisConsoleBlockEntity, IdleState> IDLE_STATES = new WeakHashMap<>();
    private static long lastSweepMs;
    private static final long STATE_TTL_MS = 10_000L;

    private static final class IdleState {
        long startNs;
        long lastSeenMs;
    }

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

        long nowNs = System.nanoTime();
        long nowMs = Util.getMillis();

        IdleState idle = IDLE_STATES.get(be);
        if (idle == null) {
            idle = new IdleState();
            idle.startNs = nowNs;
            IDLE_STATES.put(be, idle);
        }
        idle.lastSeenMs = nowMs;

        // 惰性清理：距上次清理满 10 秒才扫一遍
        if (nowMs - lastSweepMs >= STATE_TTL_MS) {
            lastSweepMs = nowMs;
            IDLE_STATES.entrySet().removeIf(e -> nowMs - e.getValue().lastSeenMs > STATE_TTL_MS);
        }

        state.idleElapsedSec = (nowNs - idle.startNs) / 1_000_000_000f;

        // ---- 控件动画 overrides ----
        TardisConsoleAppearance ca = TardisConsoleRegistry.get(be.getAppearanceId());
        if (ca != null) {
            Map<String, BedrockAnimationModel.Animation> clips =
                    BedrockCache.animations(ca.animation());
            state.controlOverrides = ConsoleAnimationController.buildOverrides(be, clips, ca);
        } else {
            state.controlOverrides = Map.of();
        }
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
                state.controlOverrides,                                                     // ← 新增
                state.facing, ca.modelFacing(),
                ca.offsetX(), ca.offsetY(), ca.offsetZ(), ca.scale(),
                state.lightCoords,
                net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock(),
                1.0f);
    }
}