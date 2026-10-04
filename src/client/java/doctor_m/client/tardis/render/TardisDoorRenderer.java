package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.client.tardis.appearance.TardisAsset;
import doctor_m.client.tardis.bedrock.BedrockAnimationModel;
import doctor_m.client.tardis.bedrock.BedrockCache;
import doctor_m.client.tardis.bedrock.BedrockGeometryModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * 门方块渲染入口。本身只负责：
 *   1) 从 BlockEntity 状态取模型 / 动画 / 光照
 *   2) 把 PoseStack 摆到「方块中心 + 朝向旋转 + 模型 offset/scale」
 *   3) 交给 {@link BedrockModelRenderer} 渲染
 *
 * 所有几何 / UV / 骨骼逻辑都在 BedrockModelRenderer 里。
 */
public class TardisDoorRenderer
        implements BlockEntityRenderer<TardisDoorBlockEntity, TardisDoorRenderState> {

    /**
     * 模型在 Blockbench 里"正面"朝向哪个方向。
     * 一般 Blockbench 主视图正对 north，但也可以把它当作 south 面来建模。
     * 如果发现门永远面朝反方向，把这里改成 {@link Direction#NORTH} 即可。
     */
    private static final Direction MODEL_FACING = Direction.NORTH;

    private static final Map<BlockPos, AnimState> ANIM_STATES = new HashMap<>();
    private record AnimState(boolean lastOpen, long startMs, long lastSeenMs) {}

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
        long now = Util.getMillis();

        AnimState prev = ANIM_STATES.get(pos);
        long startMs = (prev == null || prev.lastOpen() != isOpen) ? now : prev.startMs();
        ANIM_STATES.put(pos, new AnimState(isOpen, startMs, now));

        // 惰性清理：避免传送/拆放导致 map 无限增长
        if ((now & 0x1FFL) == 0L) {
            ANIM_STATES.entrySet().removeIf(e -> now - e.getValue().lastSeenMs() > 10_000L);
        }

        state.animElapsedSec = (now - startMs + partialTicks * 50f) / 1000f;
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

        BedrockGeometryModel geo = BedrockCache.geometry(b.geometry());
        if (geo == null) return;

        Identifier tex = b.texture() != null ? b.texture() : geo.texture();
        if (tex == null) return;

        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance()
                .getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite sprite = atlas.getSprite(tex);

        // 动画采样
        String animName = state.animTarget ? b.openAnimation() : b.closeAnimation();
        BedrockAnimationModel.Animation anim = BedrockCache.animation(b.animation(), animName);

        float animTime = 0f;
        if (anim != null) {
            animTime = anim.loop()
                    ? state.animElapsedSec % anim.length()
                    : Math.min(state.animElapsedSec, anim.length());
        }

        // 朝向旋转：把模型的"正面"方向转到方块的 FACING
        float rotDeg = state.facing.toYRot() - MODEL_FACING.toYRot();

        poseStack.pushPose();
        try {
            // 1) 移到方块中心（底面 y = 0）
            poseStack.translate(0.5, 0.0, 0.5);

            // 2) 朝向旋转（绕方块中心 Y 轴）
            poseStack.mulPose(new Matrix4f().rotationY((float) Math.toRadians(rotDeg)));

            //poseStack.scale(-1f, 1f, 1f);

            // 3) 模型局部偏移（像素 → 方块，随朝向一起转）
            poseStack.translate(b.offsetX() / 16f, b.offsetY() / 16f, b.offsetZ() / 16f);

            // 4) 像素 → 方块 + 全局缩放合并为一次
            float sc = b.scale() / 16f;
            poseStack.scale(sc, sc, sc);

            RenderType rt = RenderTypes.cutoutMovingBlock();
            BedrockModelRenderer.render(poseStack, collector, geo, sprite, rt,
                    anim, animTime, state.lightCoords);
        } finally {
            poseStack.popPose();
        }
    }
}