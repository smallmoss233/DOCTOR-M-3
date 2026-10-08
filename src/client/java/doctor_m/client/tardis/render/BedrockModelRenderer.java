package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.tardis.bedrock.BedrockAnimationModel;
import doctor_m.tardis.bedrock.BedrockGeometryModel;
import doctor_m.tardis.bedrock.BedrockRenderMath;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * 通用基岩几何体渲染器。
 *
 * <p>坐标系：内部全部使用 Bedrock 原生坐标（+X 东、+Y 上、+Z 南）。
 * 轴向与镜像已在 {@link doctor_m.tardis.bedrock.BedrockParser} 解析时一次性完成
 * （定式见 {@link doctor_m.tardis.bedrock.BedrockAxes}），本类不做任何翻转。
 *
 * <p>本类不知道 TARDIS 是什么，可用于任何基岩模型。
 *
 * <h2>相对旧版的性能与正确性改动</h2>
 * <ul>
 *   <li>方向性明暗系数在类初始化时预乘，不再每个面现场拼颜色。</li>
 *   <li>每次 {@code submitCustomGeometry} 的临时向量被提到闭包之外，
 *       不再逐 cube 分配。</li>
 *   <li>骨骼与 cube 使用各自的欧拉角定序（旧版混用一套，见
 *       {@link BedrockRenderMath}）。</li>
 *   <li>{@code uv_rotation} 现已生效（旧版忽略）。</li>
 * </ul>
 */
public final class BedrockModelRenderer {

    private BedrockModelRenderer() {}

    private static final String[] FACE_NAMES =
            {"north", "south", "east", "west", "up", "down"};

    // 每个面 4 个顶点。索引 0 = min，1 = max。
    // 顺序：从面外侧看，逆时针，起点为屏幕左上。
    private static final int[][][] FACE_CORNERS = {
            { {1,1,0}, {1,0,0}, {0,0,0}, {0,1,0} }, // north
            { {0,1,1}, {0,0,1}, {1,0,1}, {1,1,1} }, // south
            { {1,1,1}, {1,0,1}, {1,0,0}, {1,1,0} }, // east
            { {0,1,0}, {0,0,0}, {0,0,1}, {0,1,1} }, // west
            { {0,1,0}, {0,1,1}, {1,1,1}, {1,1,0} }, // up
            { {0,0,1}, {0,0,0}, {1,0,0}, {1,0,1} }, // down
    };

    private static final float[][] FACE_NORMALS = {
            { 0,  0, -1}, { 0,  0,  1}, { 1,  0,  0},
            {-1,  0,  0}, { 0,  1,  0}, { 0, -1,  0},
    };

    /**
     * 方向性阴影，顺序与 {@link #FACE_NAMES} 对应。
     * 数值就是原版 MC 的 {@code Direction.getShade()}：UP 最亮、DOWN 最暗、
     * E/W 比 N/S 暗一档。
     */
    private static final float[] FACE_SHADES = {
            0.8f,  // north
            0.8f,  // south
            0.6f,  // east
            0.6f,  // west
            1.0f,  // up
            0.5f,  // down
    };

    /** 预乘到 0..255 的灰度值，避免每个面现场算。 */
    private static final int[] FACE_SHADE_BYTES = new int[6];

    static {
        for (int i = 0; i < 6; i++) {
            FACE_SHADE_BYTES[i] = (int) (255f * FACE_SHADES[i]);
        }
    }

    // ============================================================
    //                        公开入口
    // ============================================================

    public static void render(PoseStack pose, SubmitNodeCollector collector,
                              BedrockGeometryModel geo, TextureAtlasSprite sprite,
                              RenderType rt,
                              BedrockAnimationModel.Animation anim, float time,
                              int light, float alpha) {
        if (geo == null || sprite == null) return;

        // 逐 cube 复用的临时对象：提到循环外，避免每帧每 cube 分配。
        Vector3f tmp = new Vector3f();
        Vector3f nrm = new Vector3f();
        float[] u = new float[4];
        float[] v = new float[4];

        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            renderBone(pose, collector, geo, sprite, rt, anim, time,
                    root, null, light, alpha, tmp, nrm, u, v);
        }
    }

    // ============================================================
    //                       骨骼递归
    // ============================================================

    private static void renderBone(PoseStack pose, SubmitNodeCollector collector,
                                   BedrockGeometryModel geo, TextureAtlasSprite sprite,
                                   RenderType rt,
                                   BedrockAnimationModel.Animation anim, float time,
                                   BedrockGeometryModel.Bone bone,
                                   Vector3f parentPivot, int light, float alpha,
                                   Vector3f tmp, Vector3f nrm, float[] u, float[] v) {

        Vector3f pivot = bone.pivot();
        Vector3f rotation = bone.rotation();
        Vector3f position = null;
        Vector3f scale = null;

        if (anim != null) {
            BedrockAnimationModel.BoneTracks tracks = anim.bones().get(bone.name());
            if (tracks != null) {
                Vector3f kf = BedrockRenderMath.sample(tracks.rotation(), time);
                if (kf != null) rotation = kf;
                kf = BedrockRenderMath.sample(tracks.position(), time);
                if (kf != null) position = kf;
                kf = BedrockRenderMath.sample(tracks.scale(), time);
                if (kf != null) scale = kf;
            }
        }

        pose.pushPose();
        try {
            if (parentPivot == null) {
                pose.translate(pivot.x(), pivot.y(), pivot.z());
            } else {
                pose.translate(pivot.x() - parentPivot.x(),
                        pivot.y() - parentPivot.y(),
                        pivot.z() - parentPivot.z());
            }

            if (position != null
                    && (position.x() != 0f || position.y() != 0f || position.z() != 0f)) {
                pose.translate(position.x(), position.y(), position.z());
            }

            if (rotation.x() != 0f || rotation.y() != 0f || rotation.z() != 0f) {
                pose.mulPose(new Matrix4f().rotate(
                        BedrockRenderMath.boneRotationEuler(rotation)));
            }

            if (scale != null
                    && (scale.x() != 1f || scale.y() != 1f || scale.z() != 1f)) {
                pose.scale(scale.x(), scale.y(), scale.z());
            }

            for (BedrockGeometryModel.Cube cube : bone.cubes()) {
                renderCube(pose, collector, geo, sprite, rt, pivot, cube,
                        light, alpha, tmp, nrm, u, v);
            }

            for (BedrockGeometryModel.Bone child : bone.children()) {
                renderBone(pose, collector, geo, sprite, rt, anim, time,
                        child, pivot, light, alpha, tmp, nrm, u, v);
            }
        } finally {
            pose.popPose();
        }
    }

    // ============================================================
    //                       Cube 渲染
    // ============================================================

    private static void renderCube(PoseStack pose, SubmitNodeCollector collector,
                                   BedrockGeometryModel geo, TextureAtlasSprite sprite,
                                   RenderType rt,
                                   Vector3f bonePivot,
                                   BedrockGeometryModel.Cube cube, int light, float alpha,
                                   Vector3f tmp, Vector3f nrm, float[] u, float[] v) {

        Vector3f size = cube.size();
        if (size.x() == 0f && size.y() == 0f && size.z() == 0f) return;

        Vector3f cubePivot = cube.pivot() != null ? cube.pivot() : bonePivot;

        final float x0 = cube.origin().x() - cubePivot.x();
        final float y0 = cube.origin().y() - cubePivot.y();
        final float z0 = cube.origin().z() - cubePivot.z();
        final float x1 = x0 + size.x();
        final float y1 = y0 + size.y();
        final float z1 = z0 + size.z();

        final float[] cx = { x0, x1 };
        final float[] cy = { y0, y1 };
        final float[] cz = { z0, z1 };

        final Matrix4f model = new Matrix4f(pose.last().pose());
        final float dpx = cubePivot.x() - bonePivot.x();
        final float dpy = cubePivot.y() - bonePivot.y();
        final float dpz = cubePivot.z() - bonePivot.z();
        if (dpx != 0f || dpy != 0f || dpz != 0f) {
            model.translate(dpx, dpy, dpz);
        }
        Vector3f cr = cube.rotation();
        if (cr.x() != 0f || cr.y() != 0f || cr.z() != 0f) {
            model.rotate(BedrockRenderMath.cubeRotationEuler(cr));
        }

        final Matrix3f normalMat = new Matrix3f(model).invert().transpose();

        final float su0 = sprite.getU0();
        final float sv0 = sprite.getV0();
        final float sdu = sprite.getU1() - su0;
        final float sdv = sprite.getV1() - sv0;

        final float texW = geo.textureWidth();
        final float texH = geo.textureHeight();
        final boolean mirror = cube.mirror();
        final Map<String, BedrockGeometryModel.FaceUv> faceUvs = cube.faceUvs();

        final int alphaByte = (int) (Math.max(0f, Math.min(1f, alpha)) * 255f) & 0xFF;

        // 退化面只取决于尺寸，逐 cube 算一次即可，不必在顶点循环里反复判断。
        // 注意用局部数组：静态共享数组会在多线程渲染（光影类模组）下互相踩踏。
        final boolean[] degenerate = new boolean[6];
        final int[] shadeBytes = new int[6];
        for (int fi = 0; fi < 6; fi++) {
            degenerate[fi] = isDegenerate(fi, size);
            shadeBytes[fi] = (alphaByte << 24)
                    | (FACE_SHADE_BYTES[fi] << 16)
                    | (FACE_SHADE_BYTES[fi] << 8)
                    | FACE_SHADE_BYTES[fi];
        }

        collector.submitCustomGeometry(pose, rt, (p, buffer) -> {
            for (int fi = 0; fi < 6; fi++) {
                BedrockGeometryModel.FaceUv face = faceUvs.get(FACE_NAMES[fi]);
                if (face == null) continue;

                // 退化面（面积 0）直接跳过：例如 Y=0 薄片的 N/S 面。
                if (degenerate[fi]) continue;

                final int faceColor = shadeBytes[fi];

                float uMin = face.u() / texW;
                float uMax = (face.u() + face.w()) / texW;
                float vMin = face.v() / texH;
                float vMax = (face.v() + face.h()) / texH;

                final boolean flipU = face.flipU() ^ mirror;
                final boolean flipV = face.flipV();
                if (flipU) { float t = uMin; uMin = uMax; uMax = t; }
                if (flipV) { float t = vMin; vMin = vMax; vMax = t; }

                // 面内 UV 旋转：在矩形内按 90° 的倍数重排四个角。
                BedrockRenderMath.faceUvs(face, uMin, uMax, vMin, vMax, u, v);

                float[] n = FACE_NORMALS[fi];
                nrm.set(n[0], n[1], n[2]);
                normalMat.transform(nrm);
                nrm.normalize();

                int[][] corners = FACE_CORNERS[fi];
                for (int i = 0; i < 4; i++) {
                    int[] c = corners[i];
                    tmp.set(cx[c[0]], cy[c[1]], cz[c[2]]);
                    model.transformPosition(tmp);

                    buffer.addVertex(tmp.x(), tmp.y(), tmp.z());
                    buffer.setColor(faceColor);
                    buffer.setUv(su0 + u[i] * sdu, sv0 + v[i] * sdv);
                    buffer.setLight(light);
                    buffer.setNormal(nrm.x(), nrm.y(), nrm.z());
                }
            }
        });
    }

    /** 判断面是否退化（该面只需要三个维度里的两个，任一为 0 即无面积）。 */
    private static boolean isDegenerate(int faceIndex, Vector3f size) {
        return switch (faceIndex) {
            case 0, 1 -> size.x() == 0f || size.y() == 0f;   // north / south
            case 2, 3 -> size.z() == 0f || size.y() == 0f;   // east / west
            case 4, 5 -> size.x() == 0f || size.z() == 0f;   // up / down
            default -> false;
        };
    }
}
