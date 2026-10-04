package doctor_m.client.tardis.bedrock;

import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BedrockGeometryModel(
        Identifier texture,
        int textureWidth,
        int textureHeight,
        List<Bone> rootBones,
        Map<String, Bone> byName
)

{
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
            Vector3f pivot,
            Vector3f rotation,
            Map<String, FaceUv> faceUvs,
            boolean mirror
    ) {}

    /** 返回一个沿 X 轴镜像后的新几何体。原对象不变。 */
    public BedrockGeometryModel mirrorX() {
        Map<String, Bone> newByName = new LinkedHashMap<>();
        for (Bone b : byName.values()) {
            newByName.put(b.name(), mirrorBone(b));
        }
        // 重建父子链接
        for (Bone b : byName.values()) {
            Bone nb = newByName.get(b.name());
            for (Bone child : b.children()) {
                nb.children().add(newByName.get(child.name()));
            }
        }
        List<Bone> newRoots = new ArrayList<>();
        for (Bone r : rootBones) {
            newRoots.add(newByName.get(r.name()));
        }
        return new BedrockGeometryModel(
                texture, textureWidth, textureHeight, newRoots, newByName);
    }

    private static Bone mirrorBone(Bone b) {
        Vector3f p = b.pivot();
        Vector3f r = b.rotation();
        List<Cube> newCubes = new ArrayList<>();
        for (Cube c : b.cubes()) newCubes.add(mirrorCube(c));
        return new Bone(
                b.name(), b.parentName(),
                new Vector3f(-p.x(), p.y(), p.z()),
                new Vector3f(r.x(), -r.y(), -r.z()),
                newCubes,
                new ArrayList<>()   // children 稍后由 mirrorX 填充
        );
    }

    private static Cube mirrorCube(Cube c) {
        Vector3f o  = c.origin();
        Vector3f s  = c.size();
        Vector3f cp = c.pivot();
        Vector3f cr = c.rotation();

        // origin 是最小角；镜像后最小角 = -(origin.x + size.x)
        Vector3f newOrigin = new Vector3f(-(o.x() + s.x()), o.y(), o.z());
        Vector3f newPivot  = cp != null ? new Vector3f(-cp.x(), cp.y(), cp.z()) : null;
        // 欧拉角 Z→Y→X 顺序下，X 镜像的共轭是 (rx, -ry, -rz)
        Vector3f newRot    = new Vector3f(cr.x(), -cr.y(), -cr.z());

        Map<String, FaceUv> newFaces = new LinkedHashMap<>();
        for (var e : c.faceUvs().entrySet()) {
            String faceName = e.getKey();
            FaceUv f = e.getValue();
            boolean flipU = switch (faceName) {
                case "north", "south", "up", "down" -> !f.flipU();
                default -> f.flipU();
            };
            newFaces.put(faceName,
                    new FaceUv(f.u(), f.v(), f.w(), f.h(), flipU, f.flipV()));
        }
        return new Cube(newOrigin, s, newPivot, newRot, newFaces, c.mirror());
    }

    /** 每面的 UV 矩形，像素空间；负 uv_size 已在解析时折算成 flipU / flipV */
    public record FaceUv(float u, float v, float w, float h,
                         boolean flipU, boolean flipV) {}
}