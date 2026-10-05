package doctor_m.client.tardis.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.*;

public final class BedrockParser {

    private BedrockParser() {}

    private static final String[] FACES =
            {"north", "south", "east", "west", "up", "down"};

    // =========================================================
    //                       Geometry
    // =========================================================

    public static BedrockGeometryModel parseGeometry(JsonObject root, boolean mirrorX) {
        JsonArray geos = root.getAsJsonArray("minecraft:geometry");
        if (geos == null || geos.isEmpty()) return null;
        JsonObject geo = geos.get(0).getAsJsonObject();
        JsonObject desc = geo.getAsJsonObject("description");

        Identifier tex = desc != null && desc.has("texture")
                ? Identifier.tryParse(desc.get("texture").getAsString()) : null;
        int tw = desc != null && desc.has("texture_width")
                ? desc.get("texture_width").getAsInt() : 64;
        int th = desc != null && desc.has("texture_height")
                ? desc.get("texture_height").getAsInt() : 64;

        JsonArray bonesArr = geo.getAsJsonArray("bones");
        if (bonesArr == null) return null;

        Map<String, BedrockGeometryModel.Bone> map = new LinkedHashMap<>();
        for (JsonElement e : bonesArr) {
            JsonObject b = e.getAsJsonObject();
            String name = b.get("name").getAsString();
            String parent = b.has("parent") && !b.get("parent").isJsonNull()
                    ? b.get("parent").getAsString() : null;

            Vector3f pivot = readVec3(b.get("pivot"));
            Vector3f rot = readVec3(b.get("rotation"));
            if (mirrorX) {
                pivot = new Vector3f(-pivot.x, pivot.y, pivot.z);
                rot   = new Vector3f(rot.x, -rot.y, -rot.z);
            }

            List<BedrockGeometryModel.Cube> cubes = new ArrayList<>();
            if (b.has("cubes") && b.get("cubes").isJsonArray()) {
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    BedrockGeometryModel.Cube c = parseCube(ce.getAsJsonObject(), mirrorX);
                    if (c != null) cubes.add(c);
                }
            }
            map.put(name, new BedrockGeometryModel.Bone(
                    name, parent, pivot, rot, cubes, new ArrayList<>()));
        }

        // 重建父子关系
        List<BedrockGeometryModel.Bone> roots = new ArrayList<>();
        for (var b : map.values()) {
            if (b.parentName() == null) {
                roots.add(b);
            } else {
                var p = map.get(b.parentName());
                if (p != null) p.children().add(b);
                else roots.add(b);
            }
        }
        return new BedrockGeometryModel(tex, tw, th, roots, map);
    }

    private static BedrockGeometryModel.Cube parseCube(JsonObject c, boolean mirrorX) {
        JsonArray oArr = c.getAsJsonArray("origin");
        JsonArray sArr = c.getAsJsonArray("size");
        if (oArr == null || sArr == null) return null;

        Vector3f origin = readVec3(oArr);
        Vector3f size   = readVec3(sArr);
        // ★ 不再把 0 撑成 0.001。让几何体自己表达"退化"，由 UV 兜底决定是否可见。

        Vector3f pivot = (c.has("pivot") && !c.get("pivot").isJsonNull())
                ? readVec3(c.get("pivot")) : null;
        Vector3f rotation = readVec3(c.get("rotation"));

        boolean mirror = c.has("mirror") && !c.get("mirror").isJsonNull()
                && c.get("mirror").getAsBoolean();

        if (mirrorX) {
            origin = new Vector3f(-(origin.x + size.x), origin.y, origin.z);
            if (pivot != null) pivot = new Vector3f(-pivot.x, pivot.y, pivot.z);
            rotation = new Vector3f(rotation.x, -rotation.y, -rotation.z);
        }

        Map<String, BedrockGeometryModel.FaceUv> faceUvs = new LinkedHashMap<>();
        if (c.has("uv") && c.get("uv").isJsonObject()) {
            JsonObject fo = c.getAsJsonObject("uv");
            for (String face : FACES) {
                if (!fo.has(face) || !fo.get(face).isJsonObject()) continue;
                BedrockGeometryModel.FaceUv uv =
                        parseFace(fo.getAsJsonObject(face), size, face);
                if (uv != null) faceUvs.put(face, uv);
            }
        }
        if (faceUvs.isEmpty()) return null;
        return new BedrockGeometryModel.Cube(
                origin, size, pivot, rotation, faceUvs, mirror);
    }

    /**
     * 解析单个面。
     *
     * <p>关键：{@code uv_size} 某维为 0 时用<b>几何尺寸</b>兜底，而不是丢弃面。
     * Blockbench 对"某一轴 size = 0"的 cube 会把垂直于该轴的面写 0 尺寸，
     * 但这些面在几何上仍然有面积（例如 Y=0 薄片的 UP/DOWN 面）。
     * 老代码在这里 return null，导致 Y 薄片只剩下 0.001 粗的退化 N/S 面——
     * 视觉上"消失"或"错位一格"。
     */
    private static BedrockGeometryModel.FaceUv parseFace(
            JsonObject f, Vector3f size, String face) {
        JsonArray uv = f.getAsJsonArray("uv");
        if (uv == null || uv.size() < 2) return null;

        float u = uv.get(0).getAsFloat();
        float v = uv.get(1).getAsFloat();
        float w, h;
        boolean flipU = false, flipV = false;

        float[] def = defaultFaceSize(face, size);

        if (f.has("uv_size") && f.get("uv_size").isJsonArray()) {
            JsonArray sz = f.getAsJsonArray("uv_size");
            float rawW = sz.get(0).getAsFloat();
            float rawH = sz.get(1).getAsFloat();

            flipU = rawW < 0f;
            flipV = rawH < 0f;
            if (flipU) { u += rawW; rawW = -rawW; }
            if (flipV) { v += rawH; rawH = -rawH; }

            // ★ 0 尺寸用几何尺寸兜底
            w = rawW == 0f ? def[0] : rawW;
            h = rawH == 0f ? def[1] : rawH;
        } else {
            w = def[0];
            h = def[1];
        }
        if (w <= 0f || h <= 0f) return null;
        return new BedrockGeometryModel.FaceUv(u, v, w, h, flipU, flipV);
    }

    private static float[] defaultFaceSize(String face, Vector3f size) {
        float w = size.x(), h = size.y(), d = size.z();
        return switch (face) {
            case "north", "south" -> new float[]{ w, h };
            case "east",  "west"  -> new float[]{ d, h };
            case "up",    "down"  -> new float[]{ w, d };
            default -> new float[]{ w, h };
        };
    }

    // =========================================================
    //                       Animation
    // =========================================================

    public static Map<String, BedrockAnimationModel.Animation> parseAnimations(
            JsonObject root, boolean mirrorX) {
        Map<String, BedrockAnimationModel.Animation> out = new LinkedHashMap<>();
        if (!root.has("animations") || !root.get("animations").isJsonObject()) return out;
        JsonObject anims = root.getAsJsonObject("animations");

        for (var e : anims.entrySet()) {
            JsonObject a = e.getValue().getAsJsonObject();
            float length = a.has("animation_length")
                    ? a.get("animation_length").getAsFloat() : 1f;
            if (length <= 0f) length = 1f;

            boolean loop = a.has("loop") && "true".equals(a.get("loop").getAsString());

            Map<String, BedrockAnimationModel.BoneTracks> tracks = new LinkedHashMap<>();
            if (a.has("bones") && a.get("bones").isJsonObject()) {
                for (var be : a.getAsJsonObject("bones").entrySet()) {
                    JsonObject b = be.getValue().getAsJsonObject();

                    List<BedrockAnimationModel.Keyframe> rot = parseKeys(b.get("rotation"));
                    List<BedrockAnimationModel.Keyframe> pos = parseKeys(b.get("position"));
                    List<BedrockAnimationModel.Keyframe> scl = parseKeys(b.get("scale"));

                    if (mirrorX) {
                        rot = mirrorRotation(rot);
                        pos = mirrorPosition(pos);
                        // scale 不受 X 镜像影响
                    }
                    tracks.put(be.getKey(),
                            new BedrockAnimationModel.BoneTracks(rot, pos, scl));
                }
            }
            out.put(e.getKey(),
                    new BedrockAnimationModel.Animation(length, loop, tracks));
        }
        return out;
    }

    private static List<BedrockAnimationModel.Keyframe> mirrorRotation(
            List<BedrockAnimationModel.Keyframe> keys) {
        if (keys == null) return null;
        List<BedrockAnimationModel.Keyframe> out = new ArrayList<>(keys.size());
        for (var k : keys) {
            Vector3f v = k.value();
            out.add(new BedrockAnimationModel.Keyframe(
                    k.time(), new Vector3f(v.x, -v.y, -v.z)));
        }
        return out;
    }

    private static List<BedrockAnimationModel.Keyframe> mirrorPosition(
            List<BedrockAnimationModel.Keyframe> keys) {
        if (keys == null) return null;
        List<BedrockAnimationModel.Keyframe> out = new ArrayList<>(keys.size());
        for (var k : keys) {
            Vector3f v = k.value();
            out.add(new BedrockAnimationModel.Keyframe(
                    k.time(), new Vector3f(-v.x, v.y, v.z)));
        }
        return out;
    }

    private static List<BedrockAnimationModel.Keyframe> parseKeys(JsonElement e) {
        List<BedrockAnimationModel.Keyframe> list = new ArrayList<>();
        if (e == null || !e.isJsonObject()) return list;

        for (var entry : e.getAsJsonObject().entrySet()) {
            float time;
            try {
                time = Float.parseFloat(entry.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }

            JsonElement v = entry.getValue();
            if (v.isJsonObject() && v.getAsJsonObject().has("post")) {
                v = v.getAsJsonObject().get("post");
            }
            if (!v.isJsonArray()) continue;

            JsonArray a = v.getAsJsonArray();
            if (a.size() < 3) continue;
            list.add(new BedrockAnimationModel.Keyframe(time, new Vector3f(
                    a.get(0).getAsFloat(),
                    a.get(1).getAsFloat(),
                    a.get(2).getAsFloat())));
        }
        list.sort(Comparator.comparingDouble(BedrockAnimationModel.Keyframe::time));
        return list;
    }

    private static Vector3f readVec3(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonArray()) return new Vector3f();
        JsonArray a = e.getAsJsonArray();
        return new Vector3f(
                a.size() > 0 && !a.get(0).isJsonNull() ? a.get(0).getAsFloat() : 0f,
                a.size() > 1 && !a.get(1).isJsonNull() ? a.get(1).getAsFloat() : 0f,
                a.size() > 2 && !a.get(2).isJsonNull() ? a.get(2).getAsFloat() : 0f);
    }
}