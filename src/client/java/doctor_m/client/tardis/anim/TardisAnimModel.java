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
     * 顶点已在解析阶段应用了 element 自身的静态旋转。
     * normal 也是旋转后的方向（未归一化前是单位向量）。
     */
    public record Face(float[] positions, float[] uvs, Vector3f normal) {}

    /**
     * 一个动画组。
     * pivot 单位为格；rotationClosed / rotationOpen 为组在 closed/open 时的静态旋转。
     */
    public record Group(
            Vector3f pivot,
            Quaternionf rotation,     // ★ 单字段
            List<Face> faces
    )  {}
}