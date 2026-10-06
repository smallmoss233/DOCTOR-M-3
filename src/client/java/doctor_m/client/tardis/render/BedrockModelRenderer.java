package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.tardis.bedrock.BedrockAnimationModel;
import doctor_m.tardis.bedrock.BedrockGeometryModel;
import doctor_m.tardis.bedrock.BedrockParser;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * 通用 Bedrock 几何体渲染器。
 *
 * <p>坐标系：内部全部使用 Bedrock 原生坐标（+X 东、+Y 上、+Z 南），
 * 镜像已在 {@link BedrockParser} 解析时完成，
 * 本类不做任何翻转 / 镜像。
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

    // ★ 方向性阴影系数，顺序与 FACE_NAMES 对应。
    //   数值就是原版 MC 的 Direction.getShade()：
    //   UP 最亮、DOWN 最暗、E/W 比 N/S 暗一档。
    private static final float[] FACE_SHADES = {
            0.8f,  // north
            0.8f,  // south
            0.6f,  // east
            0.6f,  // west
            1.0f,  // up
            0.5f,  // down
    };

    private static final float[] UV_FRAC_U = { 0f, 0f, 1f, 1f };
    private static final float[] UV_FRAC_V = { 0f, 1f, 1f, 0f };

    // ============================================================
    //                        公开入口
    // ============================================================

    public static void render(PoseStack pose, SubmitNodeCollector collector,
                              BedrockGeometryModel geo, TextureAtlasSprite sprite,
                              RenderType rt,
                              BedrockAnimationModel.Animation anim, float time,
                              int light, float alpha) {
        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            renderBone(pose, collector, geo, sprite, rt, anim, time,
                    root, null, light, alpha);
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
                                   Vector3f parentPivot, int light, float alpha) {

        Vector3f pivot    = bone.pivot();
        Vector3f rotation = bone.rotation();
        Vector3f position = null;
        Vector3f scale    = null;

        // 动画：覆盖骨骼的静态 rotation / position / scale
        if (anim != null) {
            var tracks = anim.bones().get(bone.name());
            if (tracks != null) {
                Vector3f kf = sampleVec(tracks.rotation(), time);
                if (kf != null) rotation = kf;
                kf = sampleVec(tracks.position(), time);
                if (kf != null) position = kf;
                kf = sampleVec(tracks.scale(), time);
                if (kf != null) scale = kf;
            }
        }

        pose.pushPose();
        try {
            // 平移到骨骼 pivot（相对父 pivot）
            if (parentPivot == null) {
                pose.translate(pivot.x(), pivot.y(), pivot.z());
            } else {
                pose.translate(pivot.x() - parentPivot.x(),
                        pivot.y() - parentPivot.y(),
                        pivot.z() - parentPivot.z());
            }

            // 动画位移（骨骼局部空间，单位：模型像素）
            if (position != null &&
                    (position.x() != 0f || position.y() != 0f || position.z() != 0f)) {
                pose.translate(position.x(), position.y(), position.z());
            }

            // 骨骼旋转
            if (rotation.x() != 0f || rotation.y() != 0f || rotation.z() != 0f) {
                pose.mulPose(new Matrix4f().rotate(eulerDegToQuat(rotation)));
            }

            // 动画缩放
            if (scale != null &&
                    (scale.x() != 1f || scale.y() != 1f || scale.z() != 1f)) {
                pose.scale(scale.x(), scale.y(), scale.z());
            }

            // 当前骨骼的 cube
            for (BedrockGeometryModel.Cube cube : bone.cubes()) {
                renderCube(pose, collector, geo, sprite, rt, pivot, cube, light, alpha);
            }

            // 子骨骼
            for (BedrockGeometryModel.Bone child : bone.children()) {
                renderBone(pose, collector, geo, sprite, rt, anim, time,
                        child, pivot, light, alpha);
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
                                   BedrockGeometryModel.Cube cube, int light, float alpha) {

        Vector3f size = cube.size();
        if (size.x() == 0f && size.y() == 0f && size.z() == 0f) return;

        Vector3f cubePivot = cube.pivot() != null ? cube.pivot() : bonePivot;

        // cube 在 cubePivot 局部空间中的 min/max
        final float x0 = cube.origin().x() - cubePivot.x();
        final float y0 = cube.origin().y() - cubePivot.y();
        final float z0 = cube.origin().z() - cubePivot.z();
        final float x1 = x0 + size.x();
        final float y1 = y0 + size.y();
        final float z1 = z0 + size.z();

        final float[] cx = { x0, x1 };
        final float[] cy = { y0, y1 };
        final float[] cz = { z0, z1 };

        final float dpx = cubePivot.x() - bonePivot.x();
        final float dpy = cubePivot.y() - bonePivot.y();
        final float dpz = cubePivot.z() - bonePivot.z();

        final Matrix4f model = new Matrix4f(pose.last().pose());
        if (dpx != 0f || dpy != 0f || dpz != 0f) {
            model.translate(dpx, dpy, dpz);
        }
        Vector3f cr = cube.rotation();
        if (cr.x() != 0f || cr.y() != 0f || cr.z() != 0f) {
            model.rotate(eulerDegToQuat(cr));
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

        collector.submitCustomGeometry(pose, rt, (p, buffer) -> {
            Vector3f tmp = new Vector3f();
            Vector3f nrm = new Vector3f();

            for (int fi = 0; fi < 6; fi++) {
                BedrockGeometryModel.FaceUv face = faceUvs.get(FACE_NAMES[fi]);
                if (face == null) continue;

                // 退化面（面积 0）直接跳过：例如 Y=0 薄片的 N/S 面
                if (isDegenerate(fi, size)) continue;

                // ★ 每个面独立算颜色：方向 shade × alpha
                float shade = FACE_SHADES[fi];
                int rgb = (int) (255f * shade);
                int faceColor = (alphaByte << 24)
                        | (rgb << 16)
                        | (rgb << 8)
                        | rgb;

                float uMin = face.u() / texW;
                float uMax = (face.u() + face.w()) / texW;
                float vMin = face.v() / texH;
                float vMax = (face.v() + face.h()) / texH;

                boolean flipU = face.flipU() ^ mirror;
                boolean flipV = face.flipV();
                if (flipU) { float t = uMin; uMin = uMax; uMax = t; }
                if (flipV) { float t = vMin; vMin = vMax; vMax = t; }

                float[] n = FACE_NORMALS[fi];
                nrm.set(n[0], n[1], n[2]);
                normalMat.transform(nrm);
                nrm.normalize();

                int[][] corners = FACE_CORNERS[fi];
                for (int i = 0; i < 4; i++) {
                    int[] c = corners[i];
                    tmp.set(cx[c[0]], cy[c[1]], cz[c[2]]);
                    model.transformPosition(tmp);

                    float u = uMin + UV_FRAC_U[i] * (uMax - uMin);
                    float v = vMin + UV_FRAC_V[i] * (vMax - vMin);

                    buffer.addVertex(tmp.x(), tmp.y(), tmp.z());
                    buffer.setColor(faceColor);
                    buffer.setUv(su0 + u * sdu, sv0 + v * sdv);
                    buffer.setLight(light);
                    buffer.setNormal(nrm.x(), nrm.y(), nrm.z());
                }
            }
        });
    }

    /** 判断面是否退化（三个维度里该面只需要两个维度）。 */
    private static boolean isDegenerate(int faceIndex, Vector3f size) {
        return switch (faceIndex) {
            case 0, 1 -> size.x() == 0f || size.y() == 0f;   // north / south
            case 2, 3 -> size.z() == 0f || size.y() == 0f;   // east / west
            case 4, 5 -> size.x() == 0f || size.z() == 0f;   // up / down
            default -> false;
        };
    }

    // ============================================================
    //                        工具
    // ============================================================

    /** 欧拉角 → 四元数。Blockbench 顺序：Z → Y → X。 */
    public static Quaternionf eulerDegToQuat(Vector3f deg) {
        float rx = (float) Math.toRadians(deg.x());
        float ry = (float) Math.toRadians(deg.y());
        float rz = (float) Math.toRadians(deg.z());
        Quaternionf q = new Quaternionf().rotationZ(rz);
        q.mul(new Quaternionf().rotationY(ry));
        q.mul(new Quaternionf().rotationX(rx));
        return q;
    }

    /** 关键帧线性插值。返回 null = 该通道无关键帧。 */
    public static Vector3f sampleVec(List<BedrockAnimationModel.Keyframe> keys, float time) {
        if (keys == null || keys.isEmpty()) return null;

        if (time <= keys.get(0).time()) return new Vector3f(keys.get(0).value());
        int last = keys.size() - 1;
        if (time >= keys.get(last).time()) return new Vector3f(keys.get(last).value());

        for (int i = 0; i < last; i++) {
            BedrockAnimationModel.Keyframe a = keys.get(i);
            BedrockAnimationModel.Keyframe b = keys.get(i + 1);
            if (time >= a.time() && time <= b.time()) {
                float span = b.time() - a.time();
                float f = span <= 0f ? 0f : (time - a.time()) / span;
                return new Vector3f(a.value()).lerp(b.value(), f);
            }
        }
        return new Vector3f(keys.get(last).value());
    }
}