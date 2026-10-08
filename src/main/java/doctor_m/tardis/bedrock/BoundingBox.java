package doctor_m.tardis.bedrock;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 几何体包围盒计算。
 *
 * <p><b>为什么需要它</b>：立方体的 {@code origin} 是<b>骨骼本地坐标</b>，不是模型坐标。
 * 旧版 {@code GuiBedrockModelRenderer} 直接把所有 cube 的 origin/size 取极值，完全忽略
 * 骨骼 pivot 链和骨骼旋转，于是任何 pivot 远离原点的模型（例如警亭的
 * {@code 警亭} 骨骼 pivot 是 {@code [0, 22.2, 0]}）算出来的中心都是错的 ——
 * 表现就是 UI 预览里模型偏移、缩放不合适。
 *
 * <p>静态骨骼旋转（本项目的资产里确实存在，如墙壁的 {@code [0, -90, 0]}）也会参与计算。
 */
public final class BoundingBox {

    private BoundingBox() {}

    /** 轴对齐包围盒。 */
    public record Box(float minX, float minY, float minZ,
                      float maxX, float maxY, float maxZ) {

        public float centerX() { return (minX + maxX) * 0.5f; }
        public float centerY() { return (minY + maxY) * 0.5f; }
        public float centerZ() { return (minZ + maxZ) * 0.5f; }

        public float sizeX() { return maxX - minX; }
        public float sizeY() { return maxY - minY; }
        public float sizeZ() { return maxZ - minZ; }

        /** 最长边。空盒时返回 0。 */
        public float maxSize() {
            return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
        }

        public boolean isEmpty() {
            return maxX < minX || maxY < minY || maxZ < minZ;
        }

        /** 兜底：用于空几何体，避免调用方除零。 */
        public static Box unit() {
            return new Box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);
        }
    }

    /**
     * 计算几何体的静态包围盒（使用骨骼自带的静态旋转，不含动画）。
     */
    public static Box of(BedrockGeometryModel geo) {
        return of(geo, null, 0f);
    }

    /**
     * 计算几何体在某个动画时刻的包围盒。
     *
     * @param anim 当前动画；为 null 表示只用静态姿态
     * @param time 动画时间（秒）
     */
    public static Box of(BedrockGeometryModel geo,
                         BedrockAnimationModel.Animation anim,
                         float time) {
        if (geo == null) return Box.unit();

        Accumulator acc = new Accumulator();
        Matrix4f parent = new Matrix4f();
        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            walk(root, null, parent, anim, time, acc);
        }
        return acc.isEmpty() ? Box.unit() : acc.toBox();
    }

    /** 逐个骨骼累加：把 cube 的八个角变换到模型空间后取极值。 */
    private static void walk(BedrockGeometryModel.Bone bone,
                             Vector3f parentPivot,
                             Matrix4f parentPose,
                             BedrockAnimationModel.Animation anim,
                             float time,
                             Accumulator acc) {

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

        // 与渲染器保持完全一致的骨骼变换顺序，否则包围盒和实际画面会对不上。
        Matrix4f pose = new Matrix4f(parentPose);
        if (parentPivot == null) {
            pose.translate(pivot.x(), pivot.y(), pivot.z());
        } else {
            pose.translate(pivot.x() - parentPivot.x(),
                    pivot.y() - parentPivot.y(),
                    pivot.z() - parentPivot.z());
        }
        if (position != null) {
            pose.translate(position.x(), position.y(), position.z());
        }
        if (rotation.x() != 0f || rotation.y() != 0f || rotation.z() != 0f) {
            pose.rotate(BedrockRenderMath.boneRotationEuler(rotation));
        }
        if (scale != null
                && (scale.x() != 1f || scale.y() != 1f || scale.z() != 1f)) {
            pose.scale(scale.x(), scale.y(), scale.z());
        }

        for (BedrockGeometryModel.Cube cube : bone.cubes()) {
            acc.include(pose, cube, pivot);
        }
        for (BedrockGeometryModel.Bone child : bone.children()) {
            walk(child, pivot, pose, anim, time, acc);
        }
    }

    private static final class Accumulator {
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        void include(Matrix4f bonePose, BedrockGeometryModel.Cube cube, Vector3f bonePivot) {
            Vector3f size = cube.size();
            if (size.x() == 0f && size.y() == 0f && size.z() == 0f) return;

            Vector3f cubePivot = cube.pivot() != null ? cube.pivot() : bonePivot;

            final float x0 = cube.origin().x() - cubePivot.x();
            final float y0 = cube.origin().y() - cubePivot.y();
            final float z0 = cube.origin().z() - cubePivot.z();
            final float x1 = x0 + size.x();
            final float y1 = y0 + size.y();
            final float z1 = z0 + size.z();

            // cube 自己的 pivot 偏移与旋转
            Matrix4f m = new Matrix4f(bonePose);
            float dpx = cubePivot.x() - bonePivot.x();
            float dpy = cubePivot.y() - bonePivot.y();
            float dpz = cubePivot.z() - bonePivot.z();
            if (dpx != 0f || dpy != 0f || dpz != 0f) m.translate(dpx, dpy, dpz);
            Vector3f cr = cube.rotation();
            if (cr.x() != 0f || cr.y() != 0f || cr.z() != 0f) {
                m.rotate(BedrockRenderMath.cubeRotationEuler(cr));
            }

            Vector3f tmp = new Vector3f();
            for (int i = 0; i < 8; i++) {
                tmp.set(
                        (i & 1) == 0 ? x0 : x1,
                        (i & 2) == 0 ? y0 : y1,
                        (i & 4) == 0 ? z0 : z1);
                m.transformPosition(tmp);
                if (tmp.x < minX) minX = tmp.x;
                if (tmp.y < minY) minY = tmp.y;
                if (tmp.z < minZ) minZ = tmp.z;
                if (tmp.x > maxX) maxX = tmp.x;
                if (tmp.y > maxY) maxY = tmp.y;
                if (tmp.z > maxZ) maxZ = tmp.z;
            }
        }

        boolean isEmpty() { return maxX < minX; }

        Box toBox() { return new Box(minX, minY, minZ, maxX, maxY, maxZ); }
    }
}
