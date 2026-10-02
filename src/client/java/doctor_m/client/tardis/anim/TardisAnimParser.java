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

    /**
     * 8 个角索引（相对 from/to 顺序）：
     *  0:(x0,y0,z0) 1:(x1,y0,z0) 2:(x1,y1,z0) 3:(x0,y1,z0)
     *  4:(x0,y0,z1) 5:(x1,y0,z1) 6:(x1,y1,z1) 7:(x0,y1,z1)
     */
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

    /** UV 顺序对应 faceCorners：UL → LL → LR → UR */
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
        Map<String, Set<Integer>> groupElements = new LinkedHashMap<>();
        collectAnimGroups(groups, groupObjs, groupElements);

        Set<Integer> animated = new HashSet<>();
        for (Set<Integer> s : groupElements.values()) animated.addAll(s);

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

            List<Integer> indices = new ArrayList<>(e.getValue());

            // group 级兜底
            Vector3f groupPivot = g.has("origin")
                    ? readVec3(g.getAsJsonArray("origin")).mul(1f / 16f)
                    : new Vector3f(0.5f, 0.5f, 0.5f);

            Quaternionf groupRot = g.has("rotation")
                    ? readRotation(g.getAsJsonObject("rotation"))
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

                List<TardisAnimModel.Face> elFaces = parseElement(el, false);
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
                                          Map<String, Set<Integer>> outElems) {
        for (JsonElement g : groups) {
            if (!g.isJsonObject()) continue;
            JsonObject o = g.getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "";

            if (name.startsWith(ANIM_PREFIX)) {
                Set<Integer> idx = new HashSet<>();
                collectIndices(o, idx);
                outObjs.put(name, o);
                outElems.put(name, idx);
            } else if (o.has("children") && o.get("children").isJsonArray()) {
                collectAnimGroups(o.getAsJsonArray("children"), outObjs, outElems);
            }
        }
    }

    private static void collectIndices(JsonObject group, Set<Integer> out) {
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
            float rx = getFloat(rot, "x"), ry = getFloat(rot, "y"), rz = getFloat(rot, "z");
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

    private static Quaternionf readRotation(JsonObject rotationObject) {
        if (rotationObject == null) return new Quaternionf();

        JsonObject r = rotationObject;
        if (rotationObject.has("rotation") && rotationObject.get("rotation").isJsonObject()) {
            r = rotationObject.getAsJsonObject("rotation");
        }

        if (r.isJsonArray()) {
            JsonArray a = r.getAsJsonArray();
            return new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(a.get(0).getAsFloat()),
                    (float) Math.toRadians(a.get(1).getAsFloat()),
                    (float) Math.toRadians(a.get(2).getAsFloat()));
        }

        if (r.has("angle") && r.has("axis")) {
            float angle = r.get("angle").getAsFloat();
            String axis = r.get("axis").getAsString();
            Quaternionf q = new Quaternionf();
            switch (axis) {
                case "x" -> q.rotationX((float) Math.toRadians(angle));
                case "y" -> q.rotationY((float) Math.toRadians(angle));
                case "z" -> q.rotationZ((float) Math.toRadians(angle));
            }
            return q;
        }

        float rx = r.has("x") ? r.get("x").getAsFloat() : 0f;
        float ry = r.has("y") ? r.get("y").getAsFloat() : 0f;
        float rz = r.has("z") ? r.get("z").getAsFloat() : 0f;
        return new Quaternionf().rotationXYZ(
                (float) Math.toRadians(rx),
                (float) Math.toRadians(ry),
                (float) Math.toRadians(rz));
    }
}