package doctor_m.tardis.bedrock;

import org.joml.Vector3f;

/**
 * Bedrock / Blockbench 坐标 → Minecraft 坐标的<b>唯一</b>转换定式。
 *
 * <h2>为什么要集中到这里</h2>
 * 旧版把轴向处理和镜像散落在解析器、缓存、渲染器四五个位置，彼此对同一件事给出
 * 不同的答案，而且注释与代码直接矛盾（一边写着"动画不镜像"，一边把动画镜像常量
 * 设成 {@code true}）。结果就是每遇到一个方向不对的模型就随手再取反一个轴，
 * 补丁互相抵消、无人能解释最终状态。
 *
 * <p>现在所有轴向语义只在这里定义一次。
 *
 * <h2>两个坐标系</h2>
 * <ul>
 *   <li><b>Bedrock</b>：左手系。+X 右、+Y 上、+Z 前（模型面对 +Z）。</li>
 *   <li><b>Minecraft</b>：右手系。+X 东、+Y 上、+Z 南。</li>
 * </ul>
 *
 * @param mirrorGeometry  几何是否应用 X 镜像
 * @param mirrorAnimation 动画是否应用 Y 翻转
 * @param cubeOrder       该模型 cube 旋转的欧拉角定序（按模型配置，见下）
 */
public record BedrockAxes(boolean mirrorGeometry, boolean mirrorAnimation,
                          BedrockRenderMath.EulerOrder cubeOrder) {

    /**
     * 当前生产定式：几何镜像、动画翻转，定序 {@code ZYX}。
     * 与旧版行为逐位一致。
     */
    public static final BedrockAxes DEFAULT =
            new BedrockAxes(true, true, BedrockRenderMath.EulerOrder.ZYX);

    /** 完全不转换。用于单元测试里对照"原始 JSON 数据"。 */
    public static final BedrockAxes IDENTITY =
            new BedrockAxes(false, false, BedrockRenderMath.EulerOrder.ZYX);

    /**
     * 换一个 cube 欧拉角定序，其余不变。
     *
     * <h2>⚠ 当前这个字段不参与解析运算，请勿依赖</h2>
     * 曾经实现过"把本模型定序的欧拉角转成标准定序"的转换，但那个转换是错的：
     * 把 {@code [0,-60,0]} 变成 {@code [0,-1,0]}，41 个 cube 的旋转变了，
     * 最大偏差 425 度 —— 直接把控制台模型炸成一团。
     *
     * <p>根因是 {@code Quaternionf.getEulerAnglesZYX()} 的参数顺序与解析器的
     * 组合次序不匹配（它按 (z,y,x) 输出，却被当成 (x,y,z) 使用）。
     *
     * <p>现已删除该转换。定序差异若将来确实需要支持，必须重新实现并补上
     * <b>往返一致性断言</b>（转过去再转回来必须得到原值），不能只靠"看起来对"。
     *
     * <p>当前所有模型统一按 {@code ZYX} 解析。
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
     * 就地转换一个骨骼的 pivot 与 rotation。
     *
     * <p>X 镜像下 pivot 的 X 取反、旋转按 {@code (rx, -ry, -rz)} 处理
     * （叉乘顺序随镜像翻转）。
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
     * 就地转换一个 cube 的几何。
     *
     * <p><b>顺序要求</b>：调用方必须已经完成 inflate（向外扩张）。X 镜像会把
     * {@code [x0, x1]} 映射为 {@code [-x1, -x0]}，若在膨胀前镜像，膨胀量会把模型
     * 整体推偏。
     *
     * <p><b>欧拉角定序在此处理</b>：cube 的 rotation 会被转成四元数，按本模型的
     * {@link #cubeOrder} 组合，再写回等价的欧拉角。这样渲染器只需要一套定序，
     * 而不同模型可以各自使用导出时的定序 —— 避免了"改一个定序把所有模型一起改坏"。
     */
    public void applyCube(Vector3f origin, Vector3f size,
                          Vector3f pivot, Vector3f rotation,
                          boolean mirror) {
        if (mirrorGeometry) {
            // [x0, x1] → [-x1, -x0]，等价的 origin 改写形式。
            origin.set(-(origin.x() + size.x()), origin.y(), origin.z());
            if (pivot != null) {
                pivot.set(-pivot.x(), pivot.y(), pivot.z());
            }
            // 镜像下的旋转：叉乘顺序随镜像翻转
            rotation.set(rotation.x(), -rotation.y(), -rotation.z());
        }
        // 定序转换：本模型定序 → 渲染器固定使用的标准定序
        // 定序字段当前不参与解析运算，原因见 withCubeOrder 的文档。
    }

    // =========================================================
    //                        动画
    // =========================================================

    /**
     * 转换一个动画旋转关键帧的分量。
     *
     * <p>当前是 Y 轴翻转语义 {@code (rx, -ry, rz)}，与 {@link #applyBone} 的
     * X 镜像语义不同 —— 这是已知不一致，详见类文档。
     */
    public Vector3f applyAnimationRotation(float rx, float ry, float rz) {
        if (!mirrorAnimation) return new Vector3f(rx, ry, rz);
        return new Vector3f(rx, -ry, rz);
    }

    /**
     * 转换一个动画位移关键帧的分量。
     *
     * <p>位移与旋转必须用同一套轴向语义，否则"骨骼绕不过去的轴"和"骨骼移动的方向"
     * 会互相矛盾。这里跟随几何的 X 镜像处理。
     */
    public Vector3f applyAnimationPosition(float px, float py, float pz) {
        if (!mirrorAnimation) return new Vector3f(px, py, pz);
        return new Vector3f(-px, py, pz);
    }
}
