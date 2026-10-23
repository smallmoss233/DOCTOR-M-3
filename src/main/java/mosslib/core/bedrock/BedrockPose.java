package mosslib.core.bedrock;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 骨骼姿态与 cube 变换的<b>唯一</b>实现。
 *
 * <h2>为什么需要它</h2>
 * 「骨骼 pivot 链 → 动画采样 → cube 自身 pivot/旋转 → 变换到模型空间」这条链，
 * 原先在三个地方各写了一遍：
 * <ul>
 *   <li>{@code BedrockModelRenderer} —— 实际渲染</li>
 *   <li>{@link BoundingBox} —— UI 预览居中与模型尺寸</li>
 *   <li>{@code ConsoleControlLayout} —— 控件碰撞箱</li>
 * </ul>
 * 三份实现只要有一处走样，就会出现「碰撞箱和模型对不上」「预览里模型偏掉」
 * 这类极难定位的问题 —— 因为渲染看起来是对的，错的是另外两份。
 *
 * <p>现在数学只写在这里，三个消费者共用。渲染器仍走 {@code PoseStack}
 * （它需要逐 cube 提交几何），但姿态解析同样取自 {@link #resolve}。
 *
 * <h2>变换次序（不可调换）</h2>
 * <pre>
 *   骨骼：translate(pivot - parentPivot) → translate(position) → rotate(rotation) → scale(scale)
 *   cube：bonePose → translate(cubePivot - bonePivot) → rotate(cubeRotation)
 *   顶点：origin - cubePivot（再经上面的矩阵）
 * </pre>
 * 这个次序与 Blockbench 的语义、以及渲染器的 {@code PoseStack} 右乘顺序一致。
 */
public final class BedrockPose {

    private BedrockPose() {}

    // =========================================================
    //                      骨骼姿态
    // =========================================================

    /**
     * 骨骼在某一时刻解析后的姿态分量。
     *
     * @param pivot    旋转中心（始终非 null）
     * @param rotation 欧拉角（度）；动画有该通道时已替换为采样值
     * @param position 动画位移；动画没有该通道时为 null
     * @param scale    动画缩放；动画没有该通道时为 null
     */
    public record BoneState(Vector3f pivot, Vector3f rotation,
                            Vector3f position, Vector3f scale) {}

    /**
     * 解析骨骼姿态：动画通道覆盖静态值，缺失的通道回退到骨骼自身的静态值。
     *
     * @param anim 当前动画；null 表示只用静态姿态
     * @param time 动画时间（秒）
     */
    public static BoneState resolve(BedrockGeometryModel.Bone bone,
                                    BedrockAnimationModel.Animation anim,
                                    float time) {
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
        return new BoneState(bone.pivot(), rotation, position, scale);
    }

    /**
     * 把骨骼的局部变换写入 {@code dest}（结果 = 父位姿 × 本骨骼局部变换）。
     *
     * @param dest        目标矩阵，会被覆盖
     * @param parentPose  父骨骼的模型空间位姿
     * @param state       本骨骼姿态
     * @param parentPivot 父骨骼 pivot；根骨骼传 null
     */
    public static Matrix4f applyBone(Matrix4f dest, Matrix4f parentPose,
                                     BoneState state, Vector3f parentPivot) {
        dest.set(parentPose);

        Vector3f pivot = state.pivot();
        if (parentPivot == null) {
            dest.translate(pivot.x(), pivot.y(), pivot.z());
        } else {
            dest.translate(pivot.x() - parentPivot.x(),
                    pivot.y() - parentPivot.y(),
                    pivot.z() - parentPivot.z());
        }

        Vector3f position = state.position();
        if (position != null
                && (position.x() != 0f || position.y() != 0f || position.z() != 0f)) {
            dest.translate(position.x(), position.y(), position.z());
        }

        Vector3f rotation = state.rotation();
        if (rotation.x() != 0f || rotation.y() != 0f || rotation.z() != 0f) {
            dest.rotate(BedrockRenderMath.boneRotationEuler(rotation));
        }

        Vector3f scale = state.scale();
        if (scale != null
                && (scale.x() != 1f || scale.y() != 1f || scale.z() != 1f)) {
            dest.scale(scale.x(), scale.y(), scale.z());
        }
        return dest;
    }

    // =========================================================
    //                       Cube 变换
    // =========================================================

    /** cube 的旋转中心：自带 pivot 优先，否则跟随骨骼 pivot。 */
    public static Vector3f cubePivotOr(BedrockGeometryModel.Cube cube, Vector3f bonePivot) {
        return cube.pivot() != null ? cube.pivot() : bonePivot;
    }

    /**
     * cube 在模型空间的变换矩阵：{@code 骨骼位姿 × 平移到 cube pivot × cube 旋转}。
     *
     * <p>顶点需先减去 {@code cubePivot}（见 {@link #forEachCorner}）。
     *
     * @param dest 目标矩阵，会被覆盖
     */
    public static Matrix4f applyCube(Matrix4f dest, Matrix4f bonePose,
                                     BedrockGeometryModel.Cube cube,
                                     Vector3f bonePivot) {
        dest.set(bonePose);

        Vector3f cubePivot = cubePivotOr(cube, bonePivot);
        float dx = cubePivot.x() - bonePivot.x();
        float dy = cubePivot.y() - bonePivot.y();
        float dz = cubePivot.z() - bonePivot.z();
        if (dx != 0f || dy != 0f || dz != 0f) {
            dest.translate(dx, dy, dz);
        }

        Vector3f rotation = cube.rotation();
        if (rotation.x() != 0f || rotation.y() != 0f || rotation.z() != 0f) {
            dest.rotate(BedrockRenderMath.cubeRotationEuler(rotation));
        }
        return dest;
    }

    /** 遍历 cube 的 8 个角（坐标已减去 cubePivot，即 cube 局部空间的顶点）。 */
    public static void forEachCorner(BedrockGeometryModel.Cube cube, Vector3f cubePivot,
                                     CornerConsumer consumer) {
        Vector3f size = cube.size();
        float x0 = cube.origin().x() - cubePivot.x();
        float y0 = cube.origin().y() - cubePivot.y();
        float z0 = cube.origin().z() - cubePivot.z();
        float x1 = x0 + size.x();
        float y1 = y0 + size.y();
        float z1 = z0 + size.z();

        for (int i = 0; i < 8; i++) {
            consumer.accept(
                    (i & 1) == 0 ? x0 : x1,
                    (i & 2) == 0 ? y0 : y1,
                    (i & 4) == 0 ? z0 : z1);
        }
    }

    /** 角点回调。 */
    @FunctionalInterface
    public interface CornerConsumer {
        void accept(float x, float y, float z);
    }

    /** cube 是否完全退化（三轴尺寸都是 0）。这类 cube 不产生可见几何。 */
    public static boolean isDegenerate(BedrockGeometryModel.Cube cube) {
        Vector3f s = cube.size();
        return s.x() == 0f && s.y() == 0f && s.z() == 0f;
    }

    // =========================================================
    //                      包围盒累加
    // =========================================================

    /**
     * 可变 AABB 累加器。BoundingBox 与 ConsoleControlLayout 共用。
     *
     * <p>没有用 {@code net.minecraft.world.phys.AABB} —— 这里是双端通用代码，
     * 而 AABB 属于游戏类；调用方需要时自行转换。
     */
    public static final class AabbAccumulator {
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        public void include(float x, float y, float z) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        /** 把一个 cube 的 8 个角变换到模型空间后并入。 */
        public void includeCube(Matrix4f bonePose, BedrockGeometryModel.Cube cube,
                                Vector3f bonePivot, Matrix4f scratch, Vector3f temp) {
            if (isDegenerate(cube)) return;

            Vector3f cubePivot = cubePivotOr(cube, bonePivot);
            applyCube(scratch, bonePose, cube, bonePivot);

            forEachCorner(cube, cubePivot, (x, y, z) -> {
                temp.set(x, y, z);
                scratch.transformPosition(temp);
                include(temp.x, temp.y, temp.z);
            });
        }

        public boolean isEmpty() { return maxX < minX; }

        public float minX() { return minX; }
        public float minY() { return minY; }
        public float minZ() { return minZ; }
        public float maxX() { return maxX; }
        public float maxY() { return maxY; }
        public float maxZ() { return maxZ; }
    }
}
