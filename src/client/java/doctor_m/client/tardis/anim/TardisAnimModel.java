package doctor_m.client.tardis.anim;

import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * 一个 TARDIS 外观模型（closed 或 open）解析后的动画数据。
 * 坐标单位统一为"格"（已除以 16）。
 */
public record TardisAnimModel(
        List<Face> staticFaces,
        Map<String, Group> groups,
        Identifier texture
) {

    /**
     * 一个面。positions 长度 12（4 顶点 × xyz），uvs 长度 8（4 顶点 × uv）。
     */
    public record Face(float[] positions, float[] uvs, Vector3f normal) {}

    /**
     * 一个动画组，包含若干 element。
     * 组内每个 element 独立持有 pivot / rotation / translation / scale。
     */
    public record Group(List<Element> elements) {}

    /**
     * 一个可动画 element。
     * pivot       单位"格"（已 /16）
     * rotation    四元数
     * translation 单位"格"（已 /16）
     * scale       倍数（默认 1,1,1）
     */
    public record Element(
            Vector3f pivot,
            Quaternionf rotation,
            Vector3f translation,
            Vector3f scale,
            List<Face> faces
    ) {}
}