package doctor_m.client.tardis.anim;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** 一个外观模型（closed 或 open）解析后的动画数据。 */
public final class TardisAnimationModelData {

    /** 静态几何：不参与动画的所有面。 */
    public final List<Face> staticFaces;

    /** 动画组：组名 → 组数据。 */
    public final Map<String, AnimatedGroup> groups;

    /** 纹理 ID（v1 假设单纹理）。 */
    public final Identifier texture;

    public TardisAnimationModelData(List<Face> staticFaces,
                                    Map<String, AnimatedGroup> groups,
                                    Identifier texture) {
        this.staticFaces = staticFaces;
        this.groups = groups;
        this.texture = texture;
    }

    /**
     * 单个面。
     * positions：4 顶点 × 3 坐标 = 12 个 float，坐标单位是"格"
     * uvs：4 顶点 × 2 UV = 8 个 float，范围 0..1
     */
    public record Face(float[] positions, float[] uvs, Direction direction, int tintIndex) {}

    /**
     * 一个动画组。
     * pivot 单位是"格"（已除以 16）。
     */
    public record AnimatedGroup(
            Vector3f pivot,
            Quaternionf closedRotation,
            Quaternionf openRotation,
            List<Face> faces
    ) {
        public Quaternionf rotationAt(float t) {
            return new Quaternionf(closedRotation).slerp(openRotation, t);
        }
    }
}