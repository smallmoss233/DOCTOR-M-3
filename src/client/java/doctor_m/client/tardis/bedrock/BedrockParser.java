package doctor_m.client.tardis.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.*;

public final class BedrockParser {

    private BedrockParser() {}

    private static final String[] FACES = {
            "north", "south", "east", "west", "up", "down"
    };

    // =========================================================
    //                       Geometry
    // =========================================================

    public static BedrockGeometryModel parseGeometry(JsonObject root) {
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

            List<BedrockGeometryModel.Cube> cubes = new ArrayList<>();
            if (b.has("cubes") && b.get("cubes").isJsonArray()) {
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    BedrockGeometryModel.Cube c = parseCube(ce.getAsJsonObject());
                    if (c != null) cubes.add(c);
                }
            }
            map.put(name, new BedrockGeometryModel.Bone(
                    name, parent, pivot, rot, cubes, new ArrayList<>()));
        }

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

    private static BedrockGeometryModel.Cube parseCube(JsonObject c) {
        JsonArray oArr = c.getAsJsonArray("origin");
        JsonArray sArr = c.getAsJsonArray("size");
        if (oArr == null || sArr == null) return null;

        Vector3f origin = readVec3(oArr);
        Vector3f size = readVec3(sArr);
        Vector3f pivot = (c.has("pivot") && !c.get("pivot").isJsonNull())
                ? readVec3(c.get("pivot")) : null;
        Vector3f rotation = readVec3(c.get("rotation"));

        boolean mirror = c.has("mirror")
                && !c.get("mirror").isJsonNull()
                && c.get("mirror").getAsBoolean();

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

    private static BedrockGeometryModel.FaceUv parseFace(JsonObject f, Vector3f size, String face) {
        JsonArray uv = f.getAsJsonArray("uv");
        if (uv == null || uv.size() < 2) return null;

        // 亚像素 UV：用 float，不用 int
        float u = uv.get(0).getAsFloat();
        float v = uv.get(1).getAsFloat();

        float w, h;
        boolean flipU = false, flipV = false;

        if (f.has("uv_size") && f.get("uv_size").isJsonArray()) {
            JsonArray sz = f.getAsJsonArray("uv_size");
            float rawW = sz.get(0).getAsFloat();
            float rawH = sz.get(1).getAsFloat();

            // 负 uv_size 折算成「原点左移 + 正尺寸 + 翻转标记」
            flipU = rawW < 0f;
            flipV = rawH < 0f;
            if (flipU) { u += rawW; rawW = -rawW; }
            if (flipV) { v += rawH; rawH = -rawH; }
            w = rawW;
            h = rawH;
        } else {
            float[] wh = defaultFaceSize(face, size);
            w = wh[0];
            h = wh[1];
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

    public static Map<String, BedrockAnimationModel.Animation> parseAnimations(JsonObject root) {
        Map<String, BedrockAnimationModel.Animation> out = new LinkedHashMap<>();
        if (!root.has("animations") || !root.get("animations").isJsonObject()) return out;
        JsonObject anims = root.getAsJsonObject("animations");

        for (var e : anims.entrySet()) {
            JsonObject a = e.getValue().getAsJsonObject();
            float length = a.has("animation_length")
                    ? a.get("animation_length").getAsFloat() : 1f;
            if (length <= 0f) length = 1f;

            String loopStr = a.has("loop") ? a.get("loop").getAsString() : "false";
            boolean loop = "true".equals(loopStr);

            Map<String, BedrockAnimationModel.BoneTracks> tracks = new LinkedHashMap<>();
            if (a.has("bones") && a.get("bones").isJsonObject()) {
                for (var be : a.getAsJsonObject("bones").entrySet()) {
                    JsonObject b = be.getValue().getAsJsonObject();
                    tracks.put(be.getKey(), new BedrockAnimationModel.BoneTracks(
                            parseKeys(b.get("rotation")),
                            parseKeys(b.get("position")),
                            parseKeys(b.get("scale"))));
                }
            }
            out.put(e.getKey(),
                    new BedrockAnimationModel.Animation(length, loop, tracks));
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