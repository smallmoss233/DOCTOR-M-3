package doctor_m.client.tardis.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public final class TardisAnimParser {

    private TardisAnimParser() {}

    private static final String ANIM_PREFIX = "anim_";

    private static int[] faceCorners(Direction dir) {
        return switch (dir) {
            case NORTH -> new int[]{2, 1, 0, 3};
            case SOUTH -> new int[]{7, 4, 5, 6};
            case UP    -> new int[]{3, 7, 6, 2};
            case DOWN  -> new int[]{4, 0, 1, 5};
            case WEST  -> new int[]{3, 0, 4, 7};
            case EAST  -> new int[]{6, 5, 1, 2};
        };
    }

    private static float[] buildUvs(float u1, float v1, float u2, float v2) {
        return new float[]{
                u1, v1,
                u1, v2,
                u2, v2,
                u2, v1
        };
    }

    public static TardisAnimModel parse(JsonObject json) {
        JsonArray elements = json.getAsJsonArray("elements");
        JsonArray groups = json.has("groups") ? json.getAsJsonArray("groups") : new JsonArray();

        Map<String, JsonObject> groupObjs = new LinkedHashMap<>();
        Map<String, List<Integer>> groupElements = new LinkedHashMap<>();
        collectAnimGroups(groups, groupObjs, groupElements);

        Set<Integer> animated = new HashSet<>();
        for (List<Integer> s : groupElements.values()) animated.addAll(s);

        // ---- 静态部分 ----
        List<TardisAnimModel.Face> staticFaces = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (!animated.contains(i)) {
                staticFaces.addAll(parseElement(elements.get(i).getAsJsonObject(), true));
            }
        }

        // ---- 动画组：逐 element 解析 ----
        Map<String, TardisAnimModel.Group> groupsOut = new LinkedHashMap<>();
        for (var e : groupElements.entrySet()) {
            String name = e.getKey();
            JsonObject g = groupObjs.get(name);
            if (g == null) continue;

            List<Integer> indices = e.getValue();

            // group 级兜底
            Vector3f groupPivot = g.has("origin")
                    ? readVec3(g.getAsJsonArray("origin")).mul(1f / 16f)
                    : new Vector3f(0.5f, 0.5f, 0.5f);

            Quaternionf groupRot = g.has("rotation")
                    ? readRotation(g)
                    : new Quaternionf();

            Vector3f groupTrans = g.has("position")
                    ? readVec3(g.getAsJsonArray("position")).mul(1f / 16f)
                    : new Vector3f();

            Vector3f groupScale = g.has("scale")
                    ? readVec3(g.getAsJsonArray("scale"))
                    : new Vector3f(1, 1, 1);

            List<TardisAnimModel.Element> elementsOut = new ArrayList<>();
            for (int idx : indices) {
                JsonObject el = elements.get(idx).getAsJsonObject();

                Vector3f pivot = groupPivot;
                Quaternionf rot = groupRot;
                Vector3f trans = groupTrans;
                Vector3f scl = groupScale;

                if (el.has("rotation")) {
                    JsonObject r = el.getAsJsonObject("rotation");
                    if (r.has("origin")) {
                        pivot = readVec3(r.getAsJsonArray("origin")).mul(1f / 16f);
                    }
                    rot = readRotation(r);
                }
                if (el.has("position")) {
                    trans = readVec3(el.getAsJsonArray("position")).mul(1f / 16f);
                }
                if (el.has("scale")) {
                    scl = readVec3(el.getAsJsonArray("scale"));
                }

                List<TardisAnimModel.Face> elFaces = parseElement(el, true);
                elementsOut.add(new TardisAnimModel.Element(pivot, rot, trans, scl, elFaces));
            }

            groupsOut.put(name, new TardisAnimModel.Group(elementsOut));
        }

        Identifier tex = null;
        if (json.has("textures")) {
            JsonObject t = json.getAsJsonObject("textures");
            if (t.has("0")) tex = Identifier.parse(t.get("0").getAsString());
        }

        return new TardisAnimModel(staticFaces, groupsOut, tex);
    }

    // ---------- 组收集 ----------

    private static void collectAnimGroups(JsonArray groups,
                                          Map<String, JsonObject> outObjs,
                                          Map<String, List<Integer>> outElems) {
        for (JsonElement g : groups) {
            if (!g.isJsonObject()) continue;
            JsonObject o = g.getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "";

            if (name.startsWith(ANIM_PREFIX)) {
                List<Integer> idx = new ArrayList<>();
                collectIndices(o, idx);
                outObjs.put(name, o);
                outElems.put(name, idx);
            } else if (o.has("children") && o.get("children").isJsonArray()) {
                collectAnimGroups(o.getAsJsonArray("children"), outObjs, outElems);
            }
        }
    }

    private static void collectIndices(JsonObject group, List<Integer> out) {
        if (!group.has("children")) return;
        for (JsonElement c : group.getAsJsonArray("children")) {
            if (c.isJsonPrimitive()) out.add(c.getAsInt());
            else if (c.isJsonObject()) collectIndices(c.getAsJsonObject(), out);
        }
    }

    // ---------- element 解析 ----------

    private static List<TardisAnimModel.Face> parseElement(JsonObject el, boolean bakeRotation) {
        JsonArray from = el.getAsJsonArray("from");
        JsonArray to = el.getAsJsonArray("to");

        float x0 = from.get(0).getAsFloat() / 16f, y0 = from.get(1).getAsFloat() / 16f, z0 = from.get(2).getAsFloat() / 16f;
        float x1 = to.get(0).getAsFloat() / 16f,   y1 = to.get(1).getAsFloat() / 16f,   z1 = to.get(2).getAsFloat() / 16f;

        float[][] c = {
                {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}
        };

        Matrix4f mat = null;
        Matrix3f normalMat = null;
        if (bakeRotation && el.has("rotation")) {
            JsonObject rot = el.getAsJsonObject("rotation");

            // ★ 兼容三种格式：{x,y,z} / {angle,axis} / [x,y,z]
            Vector3f deg = readEulerDeg(rot);
            float rx = deg.x, ry = deg.y, rz = deg.z;

            JsonArray org = rot.has("origin") ? rot.getAsJsonArray("origin") : null;
            float px = org != null ? org.get(0).getAsFloat() / 16f : 0.5f;
            float py = org != null ? org.get(1).getAsFloat() / 16f : 0.5f;
            float pz = org != null ? org.get(2).getAsFloat() / 16f : 0.5f;

            mat = new Matrix4f()
                    .translate(px, py, pz)
                    .rotateXYZ((float) Math.toRadians(rx),
                            (float) Math.toRadians(ry),
                            (float) Math.toRadians(rz))
                    .translate(-px, -py, -pz);
            normalMat = new Matrix3f(mat);

            for (float[] v : c) {
                Vector3f p = new Vector3f(v[0], v[1], v[2]);
                mat.transformPosition(p);
                v[0] = p.x; v[1] = p.y; v[2] = p.z;
            }
        }

        List<TardisAnimModel.Face> faces = new ArrayList<>();
        JsonObject facesJson = el.getAsJsonObject("faces");
        for (var entry : facesJson.entrySet()) {
            Direction dir = Direction.byName(entry.getKey());
            if (dir == null) continue;

            // 跳过零面积退化面
            boolean degenerate = switch (dir) {
                case NORTH, SOUTH -> (x0 == x1) || (y0 == y1);
                case UP, DOWN     -> (x0 == x1) || (z0 == z1);
                case EAST, WEST   -> (y0 == y1) || (z0 == z1);
            };
            if (degenerate) continue;

            JsonObject fJson = entry.getValue().getAsJsonObject();
            JsonArray uv = fJson.getAsJsonArray("uv");

            float u1 = uv.get(0).getAsFloat() / 16f;
            float v1 = uv.get(1).getAsFloat() / 16f;
            float u2 = uv.get(2).getAsFloat() / 16f;
            float v2 = uv.get(3).getAsFloat() / 16f;

            int[] ci = faceCorners(dir);
            float[] positions = new float[12];
            for (int i = 0; i < 4; i++) {
                float[] corner = c[ci[i]];
                positions[i * 3]     = corner[0];
                positions[i * 3 + 1] = corner[1];
                positions[i * 3 + 2] = corner[2];
            }

            Vector3f normal = new Vector3f(dir.getStepX(), dir.getStepY(), dir.getStepZ());
            if (normalMat != null) normalMat.transform(normal);

            faces.add(new TardisAnimModel.Face(positions, buildUvs(u1, v1, u2, v2), normal));
        }
        return faces;
    }

    // ---------- 工具 ----------

    private static float getFloat(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsFloat() : 0f;
    }

    private static Vector3f readVec3(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    /**
     * 从 rotation 对象读欧拉角（度），兼容三种格式：
     * <ul>
     *   <li>{@code {x, y, z, origin}}</li>
     *   <li>{@code {angle, axis, origin}}</li>
     *   <li>{@code [x, y, z]}</li>
     * </ul>
     */
    private static Vector3f readEulerDeg(JsonObject rot) {
        if (rot == null) return new Vector3f();

        if (rot.has("angle") && rot.has("axis")) {
            float a = rot.get("angle").getAsFloat();
            return switch (rot.get("axis").getAsString()) {
                case "x" -> new Vector3f(a, 0, 0);
                case "y" -> new Vector3f(0, a, 0);
                case "z" -> new Vector3f(0, 0, a);
                default -> new Vector3f();
            };
        }

        if (rot.isJsonArray()) {
            JsonArray a = rot.getAsJsonArray();
            return new Vector3f(
                    a.get(0).getAsFloat(),
                    a.get(1).getAsFloat(),
                    a.get(2).getAsFloat());
        }

        return new Vector3f(
                rot.has("x") ? rot.get("x").getAsFloat() : 0f,
                rot.has("y") ? rot.get("y").getAsFloat() : 0f,
                rot.has("z") ? rot.get("z").getAsFloat() : 0f);
    }

    /**
     * 读旋转四元数。入参可以是 group / element 本身（含 rotation 字段），
     * 也可以是 rotation 对象本身。
     */
    private static Quaternionf readRotation(JsonObject rotationObject) {
        if (rotationObject == null) return new Quaternionf();

        JsonObject r = rotationObject;
        if (rotationObject.has("rotation") && rotationObject.get("rotation").isJsonObject()) {
            r = rotationObject.getAsJsonObject("rotation");
        }

        Vector3f deg = readEulerDeg(r);
        return new Quaternionf().rotationXYZ(
                (float) Math.toRadians(deg.x),
                (float) Math.toRadians(deg.y),
                (float) Math.toRadians(deg.z));
    }
}