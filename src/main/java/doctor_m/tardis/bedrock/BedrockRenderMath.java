package doctor_m.tardis.bedrock;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * 基岩模型的渲染数学：欧拉角定序、动画采样、UV 旋转。
 *
 * <p>与渲染器分开，是为了让包围盒计算（{@link BoundingBox}）和渲染器共用
 * <b>同一份</b>变换定义。旧版把这两套数学各自写在客户端渲染器里，包围盒
 * 干脆忽略了骨骼变换 —— 两处不一致正是"UI 里模型偏掉"的根源。
 *
 * <h2>cube 旋转的完整路径</h2>
 * JSON 里的 cube 旋转值不是 Blockbench 内部值 —— 导出时做过
 * {@code (x, y, z) → (-x, -y, z)} 的变换（逐 cube 对照 {@code .bbmodel}
 * 工程文件与导出 JSON 验证一致）。因此正确的还原路径是：
 * <ol>
 *   <li>{@link BedrockAxes#applyCube} 做一次 {@code (-x, -y, z)}，
 *       把 JSON 值反变换回 Blockbench 内部值；</li>
 *   <li>渲染时用 {@link #cubeRotationEuler} 按 {@link #cubeOrder}
 *       （当前 {@code XYZ}）组合成四元数。</li>
 * </ol>
 * 两者缺一不可，改动其一必崩。单轴 cube 的 x/y 为 0，对变换不敏感，
 * 所以曾经看起来"只有三轴薄片错位"。
 */
public final class BedrockRenderMath {

    private BedrockRenderMath() {}

    /**
     * 欧拉角定序（6 种排列）。
     *
     * <p>三轴旋转块的姿态对定序敏感 —— 不同定序组合出不同旋转。当前
     * {@link #cubeOrder} 默认 {@link #XYZ}，与 {@link BedrockAxes#applyCube}
     * 里的 {@code (-x, -y, z)} 反变换配套，两者共同构成完整还原路径。
     */
    public enum EulerOrder {
        /** 矩阵 Rz·Ry·Rx（作用顺序：先 X → 再 Y → 最后 Z）。当前默认。 */
        XYZ,
        /** 矩阵 Rx·Ry·Rz（作用顺序：先 Z → 再 Y → 最后 X）。 */
        ZYX,
        YXZ, YZX, ZXY, XZY;

        public EulerOrder next() {
            EulerOrder[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /**
     * cube 与骨骼旋转当前使用的定序。
     *
     * <p>默认 {@link EulerOrder#XYZ}。改它之前先读类 Javadoc：
     * 它必须与 {@link BedrockAxes#applyCube} 的 {@code (-x, -y, z)}
     * 反变换配对。单独改一边会把三轴块炸开。
     */
    private static volatile EulerOrder cubeOrder = EulerOrder.XYZ;

    /**
     * 全局 Y↔Z 轴交换。**已废弃，永远保持 false。**
     *
     * <p>历史教训：这个开关曾是全局的，一开就把所有模型一起改掉；
     * 而且当年的共轭公式写错了，会直接把几何体炸开。现在不再提供
     * 运行时开关 —— 不同模型需要不同定序时，应改为按模型配置，
     * 而不是全局切。
     */
    private static volatile boolean swapYZ = false;

    public static EulerOrder cubeOrder() {
        return cubeOrder;
    }

    public static void setCubeOrder(EulerOrder order) {
        if (order != null) cubeOrder = order;
    }

    public static boolean cubeSwapYZ() {
        return swapYZ;
    }

    public static void setCubeSwapYZ(boolean value) {
        swapYZ = value;
    }

    /**
     * 骨骼旋转的四元数。
     *
     * <p>与 cube 使用同一套定序（{@link #cubeOrder}）。骨骼的 rotation
     * 在本项目的资产里都是单轴或整 180°，对定序不敏感；与 cube 统一
     * 只是避免长期维护时出现两套不同定义。
     */
    public static Quaternionf boneRotationEuler(Vector3f degrees) {
        return euler(degrees, cubeOrder, false);
    }

    /**
     * 用全局定序构造 cube 旋转四元数。
     *
     * <p>参数应当是 {@link BedrockAxes#applyCube} 处理后的值（即已经从
     * JSON 值反变换回 Blockbench 内部值）。直接传原始 JSON 值会错。
     */
    public static Quaternionf cubeRotationEuler(Vector3f degrees) {
        return euler(degrees, cubeOrder, swapYZ);
    }

    /**
     * 用指定定序构造 cube 旋转四元数。
     *
     * @param order 该模型导出时使用的定序；null 表示用全局默认
     */
    public static Quaternionf cubeRotationEuler(Vector3f degrees, EulerOrder order) {
        return euler(degrees, order == null ? cubeOrder : order, false);
    }

    /** 解析 JSON 里的定序写法，无法识别时返回 fallback。 */
    public static EulerOrder parseOrder(String text, EulerOrder fallback) {
        if (text == null) return fallback;
        try {
            return EulerOrder.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 三个分量按给定定序组合成四元数。
     *
     * <p>JOML 里 {@code a.mul(b)} 是 {@code a ← a·b}，所以
     * {@code qz.mul(qy).mul(qx)} 得到 {@code qz·qy·qx}，作用到向量上
     * 是"先 X → 再 Y → 最后 Z"。每个 case 的注释标注了实际矩阵。
     */
    private static Quaternionf euler(Vector3f deg, EulerOrder order, boolean swapAxes) {
        float rx = (float) Math.toRadians(deg.x());
        float ry = (float) Math.toRadians(deg.y());
        float rz = (float) Math.toRadians(deg.z());

        Quaternionf qx = new Quaternionf().rotationX(rx);
        Quaternionf qy = new Quaternionf().rotationY(ry);
        Quaternionf qz = new Quaternionf().rotationZ(rz);

        Quaternionf q = switch (order) {
            case XYZ -> qz.mul(qy).mul(qx);   // 矩阵 Rz·Ry·Rx
            case ZYX -> qx.mul(qy).mul(qz);   // 矩阵 Rx·Ry·Rz
            case YXZ -> qz.mul(qx).mul(qy);
            case YZX -> qx.mul(qz).mul(qy);
            case ZXY -> qy.mul(qx).mul(qz);
            case XZY -> qy.mul(qz).mul(qx);
        };

        if (swapAxes) {
            // 绕 Y 的旋转映射为绕 -Z 的基变换共轭
            q.set(q.w, q.x, -q.z, q.y);
        }
        return q;
    }

    // =========================================================
    //                      动画采样
    // =========================================================

    /**
     * 关键帧线性采样。
     *
     * @return 采样值；该通道没有关键帧时返回 {@code null}（调用方沿用静态姿态）
     */
    public static Vector3f sample(List<BedrockAnimationModel.Keyframe> keys, float time) {
        if (keys == null || keys.isEmpty()) return null;

        int last = keys.size() - 1;
        if (time <= keys.get(0).time()) return new Vector3f(keys.get(0).value());
        if (time >= keys.get(last).time()) return new Vector3f(keys.get(last).value());

        // 关键帧已按时间升序，二分查找。
        int lo = 0, hi = last;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).time() <= time) lo = mid;
            else hi = mid;
        }

        BedrockAnimationModel.Keyframe a = keys.get(lo);
        BedrockAnimationModel.Keyframe b = keys.get(hi);
        float span = b.time() - a.time();
        float f = span <= 0f ? 0f : (time - a.time()) / span;
        return new Vector3f(a.value()).lerp(b.value(), f);
    }

    // =========================================================
    //                      UV 旋转
    // =========================================================

    /**
     * 把某个面的四个角映射到贴图 UV 矩形上，并应用面内旋转。
     *
     * <p>角序固定为：0 = 左上、1 = 左下、2 = 右下、3 = 右上（从面外侧看）。
     * 旋转在归一化的 UV 矩形内按 90° 的倍数进行。
     *
     * @param face         面的 UV 矩形
     * @param uMin/uMax    贴图集内该矩形的归一化横向范围
     * @param vMin/vMax    贴图集内该矩形的归一化纵向范围
     * @param outU/outV    长度为 4 的输出数组
     */
    public static void faceUvs(BedrockGeometryModel.FaceUv face,
                               float uMin, float uMax, float vMin, float vMax,
                               float[] outU, float[] outV) {
        outU[0] = uMin; outV[0] = vMin;
        outU[1] = uMin; outV[1] = vMax;
        outU[2] = uMax; outV[2] = vMax;
        outU[3] = uMax; outV[3] = vMin;

        int rotation = ((face.uvRotation() % 360) + 360) % 360;
        if (rotation == 0) return;

        float cx = (uMin + uMax) * 0.5f;
        float cy = (vMin + vMax) * 0.5f;
        float hw = (uMax - uMin) * 0.5f;
        float hh = (vMax - vMin) * 0.5f;

        for (int i = 0; i < 4; i++) {
            float nx = hw == 0f ? 0f : (outU[i] - cx) / hw;
            float ny = hh == 0f ? 0f : (outV[i] - cy) / hh;

            float rx;
            float ry;
            switch (rotation) {
                case 90  -> { rx = -ny; ry =  nx; }
                case 180 -> { rx = -nx; ry = -ny; }
                case 270 -> { rx =  ny; ry = -nx; }
                default  -> { rx =  nx; ry =  ny; }
            }
            outU[i] = cx + rx * hw;
            outV[i] = cy + ry * hh;
        }
    }
}