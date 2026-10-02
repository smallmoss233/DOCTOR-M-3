package doctor_m.client.tardis.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public final class TardisAnimationModelParser {

    private TardisAnimationModelParser() {}

    public static TardisAnimationModelData parse(JsonObject json) {
        JsonArray elements = json.getAsJsonArray("elements");
        JsonArray groups = json.has("groups") ? json.getAsJsonArray("groups") : new JsonArray();

        // 1) 找出每个动画组包含的 element 索引
        Map<String, Set<Integer>> groupElements = new LinkedHashMap<>();
        collectAnimGroups(groups, groupElements);

        // 2) 静态 = 所有不在任何动画组里的 element
        Set<Integer> animatedIndices = new HashSet<>();
        for (Set<Integer> s : groupElements.values()) animatedIndices.addAll(s);

        List<TardisAnimationModelData.Face> staticFaces = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (!animatedIndices.contains(i)) {
                staticFaces.addAll(parseElement(elements.get(i).getAsJsonObject()));
            }
        }

        // 3) 每个动画组：解析 faces + 枢轴 + 旋转
        Map<String, TardisAnimationModelData.AnimatedGroup> groupsOut = new LinkedHashMap<>();
        for (var entry : groupElements.entrySet()) {
            String name = entry.getKey();
            JsonObject groupJson = findGroupByName(groups, name);
            if (groupJson == null) continue;

            Vector3f pivot = readVec3(groupJson.getAsJsonArray("origin")).mul(1f / 16f);
            Quaternionf rot = readRotation(groupJson);

            List<TardisAnimationModelData.Face> faces = new ArrayList<>();
            for (int idx : entry.getValue()) {
                faces.addAll(parseElement(elements.get(idx).getAsJsonObject()));
            }
            groupsOut.put(name, new TardisAnimationModelData.AnimatedGroup(pivot, rot, rot, faces));
        }

        // 4) 纹理（v1 取 textures.0）
        Identifier tex = null;
        if (json.has("textures")) {
            JsonObject textures = json.getAsJsonObject("textures");
            if (textures.has("0")) tex = Identifier.parse(textures.get("0").getAsString());
        }

        return new TardisAnimationModelData(staticFaces, groupsOut, tex);
    }

    /** 递归收集以 anim_ 开头的组，以及它们包含的 element 索引。 */
    private static void collectAnimGroups(JsonArray groups, Map<String, Set<Integer>> out) {
        for (JsonElement g : groups) {
            if (!g.isJsonObject()) continue;
            JsonObject obj = g.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";

            if (name.startsWith("anim_")) {
                Set<Integer> indices = new HashSet<>();
                collectIndices(obj, indices);
                out.put(name, indices);
            } else {
                // 继续递归子组
                if (obj.has("children") && obj.get("children").isJsonArray()) {
                    collectAnimGroups(obj.getAsJsonArray("children"), out);
                }
            }
        }
    }

    /** 收集一个组及其所有子组里的 element 索引。 */
    private static void collectIndices(JsonObject group, Set<Integer> out) {
        if (!group.has("children")) return;
        for (JsonElement child : group.getAsJsonArray("children")) {
            if (child.isJsonPrimitive()) {
                out.add(child.getAsInt());
            } else if (child.isJsonObject()) {
                collectIndices(child.getAsJsonObject(), out);
            }
        }
    }

    /** 按名字查找组（用于读 origin/rotation）。 */
    private static JsonObject findGroupByName(JsonArray groups, String name) {
        for (JsonElement g : groups) {
            if (!g.isJsonObject()) continue;
            JsonObject obj = g.getAsJsonObject();
            if (name.equals(obj.has("name") ? obj.get("name").getAsString() : "")) return obj;
            if (obj.has("children") && obj.get("children").isJsonArray()) {
                JsonObject found = findGroupByName(obj.getAsJsonArray("children"), name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 解析一个 element 的所有面。 */
    private static List<TardisAnimationModelData.Face> parseElement(JsonObject element) {
        List<TardisAnimationModelData.Face> faces = new ArrayList<>();
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");

        float x0 = from.get(0).getAsFloat() / 16f;
        float y0 = from.get(1).getAsFloat() / 16f;
        float z0 = from.get(2).getAsFloat() / 16f;
        float x1 = to.get(0).getAsFloat() / 16f;
        float y1 = to.get(1).getAsFloat() / 16f;
        float z1 = to.get(2).getAsFloat() / 16f;

        JsonObject facesJson = element.getAsJsonObject("faces");
        for (var entry : facesJson.entrySet()) {
            String dirName = entry.getKey();
            JsonObject faceJson = entry.getValue().getAsJsonObject();
            Direction dir = Direction.byName(dirName);
            if (dir == null) continue;

            JsonArray uv = faceJson.getAsJsonArray("uv");
            float u0 = uv.get(0).getAsFloat() / 16f;
            float v0 = uv.get(1).getAsFloat() / 16f;
            float u1 = uv.get(2).getAsFloat() / 16f;
            float v1 = uv.get(3).getAsFloat() / 16f;

            float[] positions = buildFacePositions(dir, x0, y0, z0, x1, y1, z1);
            float[] uvs = buildFaceUvs(dir, u0, v0, u1, v1);
            int tintIndex = faceJson.has("tintindex") ? faceJson.get("tintindex").getAsInt() : -1;

            faces.add(new TardisAnimationModelData.Face(positions, uvs, dir, tintIndex));
        }
        return faces;
    }

    /** 根据方向生成 4 个顶点（顺时针）。 */
    private static float[] buildFacePositions(Direction dir,
                                              float x0, float y0, float z0,
                                              float x1, float y1, float z1) {
        return switch (dir) {
            case DOWN  -> new float[]{ x0,y0,z1,  x1,y0,z1,  x1,y0,z0,  x0,y0,z0 };
            case UP    -> new float[]{ x0,y1,z0,  x1,y1,z0,  x1,y1,z1,  x0,y1,z1 };
            case NORTH -> new float[]{ x1,y0,z0,  x1,y1,z0,  x0,y1,z0,  x0,y0,z0 };
            case SOUTH -> new float[]{ x0,y0,z1,  x0,y1,z1,  x1,y1,z1,  x1,y0,z1 };
            case WEST  -> new float[]{ x0,y0,z1,  x0,y1,z1,  x0,y1,z0,  x0,y0,z0 };
            case EAST  -> new float[]{ x1,y0,z0,  x1,y1,z0,  x1,y1,z1,  x1,y0,z1 };
        };
    }

    /** UV 顺序对应上面顶点顺序。 */
    private static float[] buildFaceUvs(Direction dir,
                                        float u0, float v0, float u1, float v1) {
        // 根据方向决定 UV 走向。这里先给一个基础版本，
        // 具体朝向错误时按方向微调。
        return switch (dir) {
            case DOWN  -> new float[]{ u0,v0, u1,v0, u1,v1, u0,v1 };
            case UP    -> new float[]{ u0,v1, u1,v1, u1,v0, u0,v0 };
            case NORTH -> new float[]{ u1,v1, u1,v0, u0,v0, u0,v1 };
            case SOUTH -> new float[]{ u0,v1, u0,v0, u1,v0, u1,v1 };
            case WEST  -> new float[]{ u1,v1, u1,v0, u0,v0, u0,v1 };
            case EAST  -> new float[]{ u0,v1, u0,v0, u1,v0, u1,v1 };
        };
    }

    private static Vector3f readVec3(JsonArray arr) {
        return new Vector3f(arr.get(0).getAsFloat(),
                arr.get(1).getAsFloat(),
                arr.get(2).getAsFloat());
    }

    private static Quaternionf readRotation(JsonObject group) {
        if (!group.has("rotation")) return new Quaternionf();
        JsonElement rot = group.get("rotation");

        // Blockbench 两种格式：
        // 1. 数组 [x, y, z]
        // 2. 对象 {"x":..., "y":..., "z":..., "origin":[...]}
        float rx = 0, ry = 0, rz = 0;
        if (rot.isJsonArray()) {
            JsonArray a = rot.getAsJsonArray();
            rx = a.get(0).getAsFloat();
            ry = a.get(1).getAsFloat();
            rz = a.get(2).getAsFloat();
        } else if (rot.isJsonObject()) {
            JsonObject o = rot.getAsJsonObject();
            if (o.has("x")) rx = o.get("x").getAsFloat();
            if (o.has("y")) ry = o.get("y").getAsFloat();
            if (o.has("z")) rz = o.get("z").getAsFloat();
        }
        return new Quaternionf().rotationXYZ(
                (float) Math.toRadians(rx),
                (float) Math.toRadians(ry),
                (float) Math.toRadians(rz));
    }
}