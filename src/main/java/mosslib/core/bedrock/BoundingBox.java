package mosslib.core.bedrock;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 几何体包围盒计算。
 *
 * <p><b>为什么需要它</b>：立方体的 {@code origin} 是<b>骨骼本地坐标</b>，不是模型坐标。
 * 只把所有 cube 的 origin/size 取极值（忽略骨骼 pivot 链与骨骼旋转）会算错中心 ——
 * 例如警亭的 {@code 警亭} 骨骼 pivot 是 {@code [0, 22.2, 0]}，忽略它会让 UI 预览
 * 里的模型明显偏移、缩放不合适。
 *
 * <p>实际数学在 {@link BedrockPose} 里，本类只负责"遍历骨骼并累加"。
 * 与 {@code ConsoleControlLayout}（控件碰撞箱）共用同一份变换实现，
 * 因此预览尺寸与碰撞箱不会互相漂移。
 */
public final class BoundingBox {

    private BoundingBox() {}

    /** 轴对齐包围盒。单位与模型一致（像素，16 = 1 格）。 */
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

    /** 计算几何体的静态包围盒（使用骨骼自带的静态旋转，不含动画）。 */
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

        BedrockPose.AabbAccumulator acc = new BedrockPose.AabbAccumulator();
        Matrix4f parentPose = new Matrix4f();
        Matrix4f scratch = new Matrix4f();
        Vector3f temp = new Vector3f();

        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            walk(root, null, parentPose, anim, time, acc, scratch, temp);
        }
        if (acc.isEmpty()) return Box.unit();
        return new Box(acc.minX(), acc.minY(), acc.minZ(),
                acc.maxX(), acc.maxY(), acc.maxZ());
    }

    /** 递归：算出每个骨骼的模型空间位姿，把它的 cube 并入累加器。 */
    private static void walk(BedrockGeometryModel.Bone bone,
                             Vector3f parentPivot,
                             Matrix4f parentPose,
                             BedrockAnimationModel.Animation anim,
                             float time,
                             BedrockPose.AabbAccumulator acc,
                             Matrix4f scratch,
                             Vector3f temp) {

        BedrockPose.BoneState state = BedrockPose.resolve(bone, anim, time);

        // 每层独立矩阵：子骨骼传入的就是它，若与自身复用会自乘
        Matrix4f bonePose = new Matrix4f();
        BedrockPose.applyBone(bonePose, parentPose, state, parentPivot);

        for (BedrockGeometryModel.Cube cube : bone.cubes()) {
            acc.includeCube(bonePose, cube, state.pivot(), scratch, temp);
        }
        for (BedrockGeometryModel.Bone child : bone.children()) {
            walk(child, state.pivot(), bonePose, anim, time, acc, scratch, temp);
        }
    }
}
