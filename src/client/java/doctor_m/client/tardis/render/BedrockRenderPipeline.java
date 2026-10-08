package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.tardis.bedrock.BedrockAnimationModel;
import doctor_m.tardis.bedrock.BedrockGeometryModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * 基岩模型的"从资源 ID 到屏幕像素"的中间层。
 *
 * <p>三个静态方法全是无状态的，不知道 TARDIS / 门 / 控制台是什么，
 * 只负责把通用的"解析 → 采样 → 变换 + 渲染"三段流程固化下来。
 *
 * <p>调用方（门渲染器、控制台渲染器、未来任何用基岩模型的方块实体）
 * 自己决定从哪个 Registry 拿 ID、用哪个动画名、动画时间怎么算。
 */
public final class BedrockRenderPipeline {

    private BedrockRenderPipeline() {}

    // ============================================================
    //                        解析
    // ============================================================

    /**
     * 从 geometry / texture 两个 ID 解析出模型句柄。
     *
     * <p>texture 为 null 时回退到 geometry 自带的纹理（geo.texture()）。
     * 任一环节失败都返回 null。
     */
    public static BedrockModelRef resolve(Identifier geometryId, Identifier textureId) {
        if (geometryId == null) return null;

        BedrockGeometryModel geo = BedrockCache.geometry(geometryId);
        if (geo == null) return null;

        Identifier tex = textureId != null ? textureId : geo.texture();
        if (tex == null) return null;

        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance()
                .getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite sprite = atlas.getSprite(tex);

        return new BedrockModelRef(geo, sprite);
    }

    // ============================================================
    //                        动画采样
    // ============================================================

    /** 已采样的动画 + 其归一化后的时间（秒）。 */
    public record SampledAnim(BedrockAnimationModel.Animation anim, float time) {
        public static final SampledAnim EMPTY = new SampledAnim(null, 0f);
    }

    /**
     * 查询动画，并把"原始经过时间"映射为该动画自身的播放时间：
     *   - loop=true  → 取模
     *   - loop=false → clamp 到 length（播完停在最后一帧）
     *
     * <p>animId / animName 任一为 null，或动画不存在，均返回 {@link SampledAnim#EMPTY}。
     */
    public static SampledAnim sample(Identifier animId, String animName, float elapsedSec) {
        BedrockAnimationModel.Animation anim = BedrockCache.animation(animId, animName);
        if (anim == null) return SampledAnim.EMPTY;

        float t = anim.loop()
                ? elapsedSec % anim.length()
                : Math.min(elapsedSec, anim.length());
        return new SampledAnim(anim, t);
    }

    // ============================================================
    //                        渲染
    // ============================================================

    /**
     * 把模型按"方块中心 + 朝向 + offset + scale"摆好，渲染出来。
     *
     * <p>调用方通过 {@code facing} 与 {@code modelFacing} 指定朝向：
     * 模型的"正面"从 modelFacing 转到 facing。
     *
     * @param facing       方块当前朝向
     * @param modelFacing  模型在 Blockbench 里的正面朝向
     * @param offsetX/Y/Z  模型局部偏移（格）
     * @param scale        全局缩放（像素倍率）
     * @param light        光照值
     */
    public static void render(PoseStack pose, SubmitNodeCollector collector,
                              BedrockModelRef ref,
                              BedrockAnimationModel.Animation anim, float animTime,
                              Direction facing, Direction modelFacing,
                              float offsetX, float offsetY, float offsetZ, float scale,
                              int light,
                              RenderType rt, float alpha) {
        if (ref == null) return;

        float rotDeg = modelFacing.toYRot() - facing.toYRot();

        pose.pushPose();
        try {
            pose.translate(0.5, 0.0, 0.5);
            pose.mulPose(new Matrix4f().rotationY((float) Math.toRadians(rotDeg)));
            pose.translate(offsetX / 16f, offsetY / 16f, offsetZ / 16f);
            float sc = scale / 16f;
            pose.scale(sc, sc, sc);

            BedrockModelRenderer.render(pose, collector, ref.geometry(), ref.sprite(),
                    rt, anim, animTime, light, alpha);
        } finally {
            pose.popPose();
        }
    }
}