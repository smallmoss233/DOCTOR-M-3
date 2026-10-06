package doctor_m.tardis.bedrock;

import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * 纯数据。所有坐标已经对齐到 Minecraft 右手系（+X 东 / +Y 上 / +Z 南），
 * 由 {@link BedrockParser} 在解析时一次性完成镜像，此处不再做任何翻转。
 */
public record BedrockGeometryModel(
        Identifier texture,
        int textureWidth,
        int textureHeight,
        List<Bone> rootBones,
        Map<String, Bone> byName
) {
    public record Bone(
            String name,
            String parentName,
            Vector3f pivot,
            Vector3f rotation,
            List<Cube> cubes,
            List<Bone> children
    ) {}

    public record Cube(
            Vector3f origin,
            Vector3f size,
            Vector3f pivot,     // null = 用骨骼 pivot
            Vector3f rotation,
            Map<String, FaceUv> faceUvs,
            boolean mirror      // 是否翻转 U
    ) {}

    /**
     * 每个面在贴图上的矩形区域，单位：像素。
     * 负 uv_size 已在解析时折算为 flipU / flipV。
     */
    public record FaceUv(float u, float v, float w, float h,
                         boolean flipU, boolean flipV) {}
}