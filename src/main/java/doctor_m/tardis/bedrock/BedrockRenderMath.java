package doctor_m.tardis.bedrock;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * 基岩模型的渲染数学：欧拉角定序、动画采样、UV 旋转。
 *
 * <p>与渲染器分开，是为了让包围盒计算（{@link BoundingBox}）和渲染器共用<b>同一份</b>
 * 变换定义。旧版把这两套数学各自写在客户端渲染器里，包围盒根本没法复用，于是它
 * 干脆忽略了骨骼变换 —— 两处不一致正是"UI 里模型偏掉"的根源。
 */
public final class BedrockRenderMath {

    private BedrockRenderMath() {}

    /**
     * 欧拉角定序（6 种排列）。
     *
     * <p><b>为什么做成可切换</b>：当模型里出现 Blockbench 手工拖出来的"
     * 自由旋转"时，它会被导出成一组欧拉角；只有用<b>导出时相同的定序</b>
     * 还原，几何体才会回到原位。定序错了，凡是多轴旋转的块都会散开，
     * 而单轴旋转的块看起来完全正常 —— 这正是"只有复杂旋转的薄片错位"的成因。
     *
     * <p>Cube 的定序无法从数据里可靠反推（实测轴向评分 0.931~0.980，差距在噪声级），
     * 所以做成运行时可切换：用 {@code /dmbedrock rotation <order>} 现场试，
     * 哪个对就用哪个，不必改代码重编。
     */
    public enum EulerOrder {
        /** Rx·Ry·Rz —— Z→Y→X。Blockbench 对 cube 的默认导出定序。 */
        ZYX,
        /** Rz·Ry·Rx —— X→Y→Z。Blockbench 对 bone 的默认导出定序。 */
        XYZ,
        YXZ, YZX, ZXY, XZY;

        public EulerOrder next() {
            EulerOrder[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /**
     * cube 旋转当前使用的定序。
     *
     * <p>默认 {@link EulerOrder#ZYX}。若模型里多轴旋转的块错位，用
     * {@code /dmbedrock rotation <order>} 逐个试 —— 这是唯一可靠的判定方式，
     * 因为从数据本身反推不出定序。
     */
    private static volatile EulerOrder cubeOrder = EulerOrder.ZYX;

    /**
     * 全局 Y↔Z 轴交换。**已废弃，保留仅为兼容，永远保持 false。**
     *
     * <p>历史教训：这个开关曾是全局的，一开就把<b>所有</b>模型一起改掉 ——
     * 控制台没修好，塔迪斯反而崩了。而且当时的共轭公式写错
     * （{@code (w,x,z,-y)} 不是单位四元数），会直接把几何体炸开。
     *
     * <p>正确的共轭是 {@code (w,x,-z,y)}。但现在不再提供运行时开关：
     * 不同模型是在不同时期导出的，需要各自不同的定序，
     * <b>只能按模型配置</b>，见 {@link #cubeRotationEuler(Vector3f, EulerOrder)}。
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
     * 骨骼旋转的欧拉角定序。
     *
     * <p>Blockbench 对<b>骨骼</b>使用 X → Y → Z 的定序。
     */
    public static Quaternionf boneRotationEuler(Vector3f degrees) {
        return euler(degrees, EulerOrder.XYZ, false);
    }

    /** 用全局定序构造 cube 旋转（旧调用点兼容用）。 */
    public static Quaternionf cubeRotationEuler(Vector3f degrees) {
        return euler(degrees, cubeOrder, swapYZ);
    }

    /**
     * 用<b>指定</b>定序构造 cube 旋转。
     *
     * <p>这是推荐的调用方式：定序应当来自模型自身（外观 JSON 的
     * {@code rotation_order} 字段），而不是全局状态 ——
     * 否则切一个模型会把其他所有模型一起改掉。
     *
     * @param order 该模型导出时使用的欧拉角定序；null 表示用全局默认
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

    private static Quaternionf euler(Vector3f deg, EulerOrder order, boolean swapAxes) {
        float rx = (float) Math.toRadians(deg.x());
        float ry = (float) Math.toRadians(deg.y());
        float rz = (float) Math.toRadians(deg.z());

        Quaternionf qx = new Quaternionf().rotationX(rx);
        Quaternionf qy = new Quaternionf().rotationY(ry);
        Quaternionf qz = new Quaternionf().rotationZ(rz);

        // 四元数按"先作用的写在右边"组合，与矩阵乘法次序一致。
        Quaternionf q = switch (order) {
            case XYZ -> qz.mul(qy).mul(qx);   // Rx·Ry·Rz
            case ZYX -> qx.mul(qy).mul(qz);   // Rz·Ry·Rx
            case YXZ -> qz.mul(qx).mul(qy);   // Ry·Rx·Rz
            case YZX -> qx.mul(qz).mul(qy);   // Ry·Rz·Rx
            case ZXY -> qy.mul(qx).mul(qz);   // Rz·Rx·Ry
            case XZY -> qy.mul(qz).mul(qx);   // Rx·Rz·Ry
        };

        if (swapAxes) {
            // 正确的基变换共轭：绕 Y 的旋转映射为绕 -Z
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

        // 关键帧已按时间升序，二分查找比旧版的逐段线性扫描更适合关键帧较多的动画。
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
     * 旋转是在归一化的 UV 矩形内按 90° 的倍数进行的 ——
     * 旧版完全忽略 {@code uv_rotation}，凡是用了该字段的模型贴图都是转错的。
     *
     * @param face         面的 UV 矩形
     * @param uMin/uMax    贴图集内该矩形的归一化横向范围
     * @param vMin/vMax    贴图集内该矩形的归一化纵向范围
     * @param outU/outV    长度为 4 的输出数组
     */
    public static void faceUvs(BedrockGeometryModel.FaceUv face,
                               float uMin, float uMax, float vMin, float vMax,
                               float[] outU, float[] outV) {
        // 基准角：左上、左下、右下、右上
        outU[0] = uMin; outV[0] = vMin;
        outU[1] = uMin; outV[1] = vMax;
        outU[2] = uMax; outV[2] = vMax;
        outU[3] = uMax; outV[3] = vMin;

        int rotation = ((face.uvRotation() % 360) + 360) % 360;
        if (rotation == 0) return;

        // 以矩形中心为轴旋转。矩形不一定等宽高，所以按归一化坐标旋转后映射回去。
        float cx = (uMin + uMax) * 0.5f;
        float cy = (vMin + vMax) * 0.5f;
        float hw = (uMax - uMin) * 0.5f;
        float hh = (vMax - vMin) * 0.5f;

        for (int i = 0; i < 4; i++) {
            // 归一化到 [-1, 1]
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
