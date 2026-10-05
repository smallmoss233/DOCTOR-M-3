package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.client.tardis.bedrock.BedrockAnimationModel;
import doctor_m.client.tardis.bedrock.BedrockGeometryModel;
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
 * 通用的基岩版几何体渲染器。
 *
 * <p>内部全部使用 Bedrock 原生坐标：+X 东、+Y 上、+Z 南。
 * 与 Minecraft 世界坐标完全一致，<b>不做任何轴翻转或镜像</b>。
 *
 * <p>调用方负责把 PoseStack 调整到"模型所在位置 + 模型朝向 + 全局缩放"。
 * 本渲染器只负责把几何体按 Bedrock 语义搭出来。
 */
public final class BedrockModelRenderer {

    private BedrockModelRenderer() {}

    // ============================================================
    //                       面的几何表
    // ============================================================
    //
    // 每个面 4 个顶点，索引 0 表示 min，1 表示 max。
    // 顶点顺序：从面外侧看，逆时针，起点为「屏幕左上」。
    //
    // 推导（右手系）：站在面外侧看向面，视线方向 d = -外法线；
    // 屏幕右方向 r = d × (0,1,0)；屏幕上方向 u = r × d。
    //
    // 不要修改此表；如遇"朝向反了"，问题一定在别处。

    private static final String[] FACE_NAMES = {
            "north", "south", "east", "west", "up", "down"
    };

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

    /** 与上面顶点顺序严格对应：左上、左下、右下、右上 */
    private static final float[] UV_FRAC_U = { 0f, 0f, 1f, 1f };
    private static final float[] UV_FRAC_V = { 0f, 1f, 1f, 0f };

    // ============================================================
    //                        公开入口
    // ============================================================

    /**
     * 渲染一整个 Bedrock 几何体。
     *
     * @param pose     已经变换到"模型局部空间"的 PoseStack
     * @param collector 26.3 的几何体提交器
     * @param geo      几何体数据
     * @param sprite   已烘焙到方块图集的精灵
     * @param rt       渲染类型
     * @param anim     当前动画（可为 null）
     * @param time     动画时间（秒）
     * @param light    光照值
     * @param alpha    整体不透明度（0~1），1 = 不透明
     */
    public static void render(PoseStack pose, SubmitNodeCollector collector,
                              BedrockGeometryModel geo, TextureAtlasSprite sprite,
                              RenderType rt,
                              BedrockAnimationModel.Animation anim, float time,
                              int light, float alpha) {
        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            renderBone(pose, collector, geo, sprite, rt, anim, time, root, null, light, alpha);
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

        Vector3f pivot = bone.pivot();
        Vector3f rotation = bone.rotation();

        // 动画：覆盖骨骼静态旋转（Bedrock 语义 = 该骨骼的绝对旋转）
        if (anim != null) {
            var tracks = anim.bones().get(bone.name());
            if (tracks != null) {
                Vector3f kf = sampleVec(tracks.rotation(), time);
                if (kf != null) rotation = kf;
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

            // 骨骼旋转（绕自身 pivot）
            if (rotation.x() != 0f || rotation.y() != 0f || rotation.z() != 0f) {
                pose.mulPose(new Matrix4f().rotate(eulerDegToQuat(rotation)));
            }

            // 当前骨骼的 cube
            for (BedrockGeometryModel.Cube cube : bone.cubes()) {
                renderCube(pose, collector, geo, sprite, rt, pivot, cube, light, alpha);
            }

            // 子骨骼
            for (BedrockGeometryModel.Bone child : bone.children()) {
                renderBone(pose, collector, geo, sprite, rt, anim, time, child, pivot, light, alpha);
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
        if (size.x() <= 0f || size.y() <= 0f || size.z() <= 0f) return;

        // Bedrock 语义：
        //   origin = cube 最小角（绝对坐标）
        //   pivot  = cube 旋转中心（绝对坐标）；缺省时用 bone pivot
        Vector3f cubePivot = cube.pivot() != null ? cube.pivot() : bonePivot;

        // cube 在「cubePivot 局部空间」中的 min/max
        final float x0 = cube.origin().x() - cubePivot.x();
        final float y0 = cube.origin().y() - cubePivot.y();
        final float z0 = cube.origin().z() - cubePivot.z();
        final float x1 = x0 + size.x();
        final float y1 = y0 + size.y();
        final float z1 = z0 + size.z();

        final float[] cx = { x0, x1 };
        final float[] cy = { y0, y1 };
        final float[] cz = { z0, z1 };

        // cubePivot 相对 bonePivot 的偏移（在 bone 局部空间）
        final float dpx = cubePivot.x() - bonePivot.x();
        final float dpy = cubePivot.y() - bonePivot.y();
        final float dpz = cubePivot.z() - bonePivot.z();

        // 快照 bone 的 pose
        final Matrix4f model = new Matrix4f(pose.last().pose());
        if (dpx != 0f || dpy != 0f || dpz != 0f) {
            model.translate(dpx, dpy, dpz);
        }
        Vector3f cr = cube.rotation();
        if (cr.x() != 0f || cr.y() != 0f || cr.z() != 0f) {
            model.rotate(eulerDegToQuat(cr));
        }

        // 法线矩阵
        final Matrix3f normalMat = new Matrix3f(model).invert().transpose();

        // 纹理采样边界
        final float su0 = sprite.getU0();
        final float sv0 = sprite.getV0();
        final float sdu = sprite.getU1() - su0;
        final float sdv = sprite.getV1() - sv0;

        final float texW = geo.textureWidth();
        final float texH = geo.textureHeight();
        final boolean mirror = cube.mirror();
        final Map<String, BedrockGeometryModel.FaceUv> faceUvs = cube.faceUvs();

        // ★ 把 alpha 预计算为 ARGB 颜色（一次计算，所有顶点共用）
        final int alphaByte = (int) (Math.max(0f, Math.min(1f, alpha)) * 255f) & 0xFF;
        final int color = (alphaByte << 24) | 0x00FFFFFF;

        collector.submitCustomGeometry(pose, rt, (p, buffer) -> {
            final Vector3f tmp = new Vector3f();
            final Vector3f nrm = new Vector3f();

            for (int fi = 0; fi < 6; fi++) {
                // 直接用同名面的 UV。不做任何 east/west 交换，
                // 不做任何 "有没有 up/down" 的条件翻转。
                BedrockGeometryModel.FaceUv face = faceUvs.get(FACE_NAMES[fi]);
                if (face == null) continue;

                float uMin = face.u() / texW;
                float uMax = (face.u() + face.w()) / texW;
                float vMin = face.v() / texH;
                float vMax = (face.v() + face.h()) / texH;

                // flipU/flipV 已在 parser 中从负 uv_size 折算；mirror 只翻 U
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
                    buffer.setColor(color);   // ★ ARGB
                    buffer.setUv(su0 + u * sdu, sv0 + v * sdv);
                    buffer.setLight(light);
                    buffer.setNormal(nrm.x(), nrm.y(), nrm.z());
                }
            }
        });
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

    /** 关键帧线性插值。null = 无关键帧。 */
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