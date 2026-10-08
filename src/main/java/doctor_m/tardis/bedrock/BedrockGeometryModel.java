package doctor_m.tardis.bedrock;

import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * 基岩版几何模型（已转换到 Minecraft 坐标系的纯数据）。
 *
 * <p>所有轴向与镜像处理已在 {@link BedrockParser} 中通过 {@link BedrockAxes}
 * 一次性完成，此处与渲染器都不再做任何翻转。
 *
 * <p>全部组件都是不可变 record；集合在解析结束时已复制为不可变视图。
 */
public record BedrockGeometryModel(
        Identifier texture,
        int textureWidth,
        int textureHeight,
        List<Bone> rootBones,
        Map<String, Bone> byName
) {
    /**
     * 骨骼。
     *
     * <p>{@code children} 内部使用可变列表以便解析阶段连接父子关系，解析完成后
     * 不再修改；渲染时只读遍历。{@code parentName} 保留是为了诊断和环检测。
     */
    public record Bone(
            String name,
            String parentName,
            Vector3f pivot,
            Vector3f rotation,
            List<Cube> cubes,
            List<Bone> children
    ) {}

    /**
     * 立方体。
     *
     * @param origin     最小角（X 镜像后可能大于 max 角，渲染按 min/max 归一处理）
     * @param size       尺寸，必须为正
     * @param pivot      旋转中心；null 表示跟随骨骼 pivot
     * @param rotation   欧拉角（度）
     * @param faceUvs    每个面的贴图矩形，键为 north/south/east/west/up/down
     * @param mirror     Blockbench 的 mirror 标记，仅翻转 U
     * @param uvRotation 贴图在该面内旋转的角度（0/90/180/270），来自 cube 的 uv_rotation
     */
    public record Cube(
            Vector3f origin,
            Vector3f size,
            Vector3f pivot,
            Vector3f rotation,
            Map<String, FaceUv> faceUvs,
            boolean mirror,
            int uvRotation
    ) {
        /** 兼容构造器：无 uv_rotation。 */
        public Cube(Vector3f origin, Vector3f size, Vector3f pivot,
                    Vector3f rotation, Map<String, FaceUv> faceUvs, boolean mirror) {
            this(origin, size, pivot, rotation, faceUvs, mirror, 0);
        }

        /** 三轴尺寸都非零即视为有体积的立方体。 */
        public boolean hasVolume() {
            return size.x() != 0f && size.y() != 0f && size.z() != 0f;
        }
    }

    /**
     * 每个面在贴图上的矩形区域，单位：像素。
     *
     * <p>负 {@code uv_size} 已在解析时折算为 {@link #flipU} / {@link #flipV}，
     * 所以 {@code w} / {@code h} 恒为正。
     *
     * @param uvRotation 贴图在该面内旋转的角度（0/90/180/270）
     */
    public record FaceUv(float u, float v, float w, float h,
                         boolean flipU, boolean flipV, int uvRotation) {
        /** 兼容构造器：无旋转。 */
        public FaceUv(float u, float v, float w, float h, boolean flipU, boolean flipV) {
            this(u, v, w, h, flipU, flipV, 0);
        }
    }
}
