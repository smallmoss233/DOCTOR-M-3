package doctor_m.tardis.bedrock;

import org.joml.Vector3f;

/**
 * Bedrock / Blockbench 坐标 → Minecraft 坐标的<b>唯一</b>转换定式。
 *
 * <p>所有轴向语义只在这里定义一次。旧版把它们散落在解析器、缓存、渲染器
 * 四五个位置，彼此对同一件事给出不同答案，结果就是"每遇到一个方向不对的
 * 模型就随手再取反一个轴"，补丁互相抵消、无人能解释最终状态。
 *
 * <h2>两个坐标系</h2>
 * <ul>
 *   <li><b>Bedrock</b>：左手系。+X 右、+Y 上、+Z 前（模型面对 +Z）。</li>
 *   <li><b>Minecraft</b>：右手系。+X 东、+Y 上、+Z 南。</li>
 * </ul>
 *
 * @param mirrorGeometry  几何是否应用 X 镜像
 * @param mirrorAnimation 动画是否应用 Y 翻转
 * @param cubeOrder       该模型 cube 旋转的欧拉角定序
 */
public record BedrockAxes(boolean mirrorGeometry, boolean mirrorAnimation,
                          BedrockRenderMath.EulerOrder cubeOrder) {

    /**
     * 生产定式：几何 X 镜像、动画 Y 翻转，cube 定序 {@link
     * BedrockRenderMath.EulerOrder#XYZ}。
     *
     * <p>cube 的 {@code XYZ} 与 {@link #applyCube} 里的 {@code (-x, -y, z)}
     * 反变换是配套的，两者共同构成完整还原路径 —— 详见
     * {@link BedrockRenderMath} 类文档。
     */
    public static final BedrockAxes DEFAULT =
            new BedrockAxes(true, true, BedrockRenderMath.EulerOrder.XYZ);

    /** 完全不转换。用于单元测试里对照原始 JSON 数据。 */
    public static final BedrockAxes IDENTITY =
            new BedrockAxes(false, false, BedrockRenderMath.EulerOrder.XYZ);

    /**
     * 换一个 cube 欧拉角定序，其余不变。
     *
     * <p>用于"某个模型导出时用了不同定序"的情况。切换时必须同时确认
     * 该模型 {@link #applyCube} 的旋转反变换仍是 {@code (-x, -y, z)}，
     * 否则两边不配套。
     */
    public BedrockAxes withCubeOrder(BedrockRenderMath.EulerOrder order) {
        return order == null || order == cubeOrder
                ? this
                : new BedrockAxes(mirrorGeometry, mirrorAnimation, order);
    }

    public boolean mirrorsAnimation() {
        return mirrorAnimation;
    }

    // =========================================================
    //                        骨骼
    // =========================================================

    /**
     * 就地转换骨骼的 pivot 与 rotation。
     *
     * <p>X 镜像对旋转的共轭是 {@code (x, -y, -z)}：单轴时
     * {@code M·Rx·M = Rx}、{@code M·Ry·M = Ry(-θ)}、{@code M·Rz·M = Rz(-θ)}，
     * 而共轭对乘积分配，所以对任意定序都成立。
     */
    public void applyBone(Vector3f pivot, Vector3f rotation) {
        if (!mirrorGeometry) return;
        pivot.set(-pivot.x(), pivot.y(), pivot.z());
        rotation.set(rotation.x(), -rotation.y(), -rotation.z());
    }

    // =========================================================
    //                        立方体
    // =========================================================

    /**
     * 就地转换 cube 的几何。
     *
     * <p><b>调用顺序</b>：必须已经完成 inflate。X 镜像把
     * {@code [x0, x1]} 映射为 {@code [-x1, -x0]}，若在膨胀前镜像，
     * 膨胀量会把模型整体推偏。
     *
     * <h2>旋转那行做的是什么</h2>
     * 对 cube 的 {@code rotation} 应用 {@code (-x, -y, z)}。这个变换
     * <b>不是</b>几何镜像 —— 它是 Blockbench 从 {@code .bbmodel} 导出
     * JSON 时应用的变换本身（自逆），所以在这里再做一次就恢复了
     * Blockbench 内部值。
     *
     * <p>导出变换是逐 cube 对照 {@code .bbmodel} 工程文件与导出 JSON
     * 得出的：单轴与三轴 cube 全部一致，{@code (x, y, z) → (-x, -y, z)}。
     *
     * <p>反变换之后，渲染端按 {@link BedrockRenderMath#cubeRotationEuler}
     * 的定序组合即得原姿态。两者缺一不可。
     */
    public void applyCube(Vector3f origin, Vector3f size,
                          Vector3f pivot, Vector3f rotation,
                          boolean mirror) {
        if (mirrorGeometry) {
            // [x0, x1] → [-x1, -x0]
            origin.set(-(origin.x() + size.x()), origin.y(), origin.z());
            if (pivot != null) {
                pivot.set(-pivot.x(), pivot.y(), pivot.z());
            }
            // JSON → .bbmodel 的反变换（自逆）。详见方法 Javadoc。
            rotation.set(-rotation.x(), -rotation.y(), rotation.z());
        }
    }

    // =========================================================
    //                        动画
    // =========================================================

    /**
     * 转换动画旋转关键帧的分量。
     *
     * <p>当前是 Y 轴翻转语义 {@code (rx, -ry, -rz)}，与 {@link #applyBone}
     * 的 X 镜像语义不同 —— 这是已知的不一致，两者服务的是不同的数据源
     * （动画关键帧来自 {@code .animation.json}，骨骼 rotation 来自
     * {@code .geo.json}），暂未统一。
     */
    public Vector3f applyAnimationRotation(float rx, float ry, float rz) {
        if (!mirrorAnimation) return new Vector3f(rx, ry, rz);
        return new Vector3f(rx, -ry, -rz);
    }

    /**
     * 转换动画位移关键帧的分量。
     *
     * <p>位移与旋转必须用同一套轴向语义，否则"骨骼绕不过去的轴"和
     * "骨骼移动的方向"会互相矛盾。这里跟随几何的 X 镜像处理。
     */
    public Vector3f applyAnimationPosition(float px, float py, float pz) {
        if (!mirrorAnimation) return new Vector3f(px, py, pz);
        return new Vector3f(-px, py, pz);
    }
}