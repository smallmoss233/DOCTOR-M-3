package doctor_m.tardis.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.*;

/**
 * Blockbench / 基岩版几何与动画 JSON 的解析器。
 *
 * <p><b>设计原则（与旧版的关键差别）</b>
 * <ol>
 *   <li><b>顺序无关</b>：骨骼父子关系在全部骨骼读完后统一解析，父骨骼定义在子骨骼
 *       之后同样正确。</li>
 *   <li><b>不静默降级</b>：任何被丢弃的输入都记入 {@link Diagnostics}，而不是悄悄返回
 *       null 让模型凭空少一块。</li>
 *   <li><b>类型容错</b>：Blockbench 对同一字段存在多种合法写法（如 {@code loop}
 *       既可能是布尔也可能是字符串、{@code uv} 既可能是面表也可能被省略）。
 *       解析器接受全部合法写法，而不是只认其中一种。</li>
 *   <li><b>不抛异常</b>：资源包内容属于外部输入，格式错误只会被记录，绝不会让渲染线程
 *       抛出异常。</li>
 * </ol>
 *
 * <p><b>坐标系</b>：本类是 Bedrock 坐标进入 Minecraft 的唯一入口，所有轴向与镜像的
 * 处理都集中在这里，详见 {@link BedrockAxes}。数据模型与渲染器不做任何翻转。
 */
public final class BedrockParser {

    private BedrockParser() {}

    private static final String[] FACES =
            {"north", "south", "east", "west", "up", "down"};

    /** 单个模型允许的最大骨骼数，防止畸形资源包拖垮解析。 */
    private static final int MAX_BONES = 4096;
    /** 单个骨骼允许的最大 cube 数。 */
    private static final int MAX_CUBES_PER_BONE = 4096;

    // =========================================================
    //                      诊断
    // =========================================================

    /**
     * 解析过程中的问题记录。
     *
     * <p>存在的意义：旧版解析器在遇到不认识或畸形的输入时直接 {@code return null}，
     * 表现是"模型少了一块"却没有任何线索。现在这些问题会被计数并可按需打印。
     */
    public static final class Diagnostics {
        private int invalidBones;
        private int invalidCubes;
        private int droppedFaces;
        private int invalidKeyframes;
        private int cyclesBroken;
        private int orphanBones;
        private int degenerateCubes;
        /**
         * UV 小于一个 texel 的面数（亚像素面）。
         *
         * <p>这类面<b>不是错误</b> —— 本解析器支持它们。但当纹理分辨率过低时，
         * 一个 texel 比整个面还大，细节无法表达，画面会呈现为色块。
         * 计数出来是为了让作者知道：想看到细节就得提高纹理分辨率。
         */
        private int subTexelFaces;
        private final Set<String> notes = new LinkedHashSet<>();

        public int invalidBones()      { return invalidBones; }
        public int invalidCubes()      { return invalidCubes; }
        public int droppedFaces()      { return droppedFaces; }
        public int invalidKeyframes()  { return invalidKeyframes; }
        public int cyclesBroken()      { return cyclesBroken; }
        public int orphanBones()       { return orphanBones; }
        public int degenerateCubes()   { return degenerateCubes; }
        public int subTexelFaces()     { return subTexelFaces; }
        public Set<String> notes()     { return Collections.unmodifiableSet(notes); }

        public boolean isClean() {
            return invalidBones == 0 && invalidCubes == 0 && droppedFaces == 0
                    && invalidKeyframes == 0 && cyclesBroken == 0
                    && orphanBones == 0 && degenerateCubes == 0;
        }

        /** 人类可读的一行摘要，用于日志。 */
        public String summary() {
            if (isClean() && subTexelFaces == 0) return "ok";
            StringBuilder sb = new StringBuilder();
            append(sb, "invalid bones", invalidBones);
            append(sb, "invalid cubes", invalidCubes);
            append(sb, "dropped faces", droppedFaces);
            append(sb, "invalid keyframes", invalidKeyframes);
            append(sb, "broken cycles", cyclesBroken);
            append(sb, "orphan bones", orphanBones);
            append(sb, "degenerate cubes", degenerateCubes);
            append(sb, "sub-texel faces", subTexelFaces);
            if (!notes.isEmpty()) sb.append("; ").append(String.join("; ", notes));
            return sb.toString();
        }

        private static void append(StringBuilder sb, String label, int n) {
            if (n <= 0) return;
            if (sb.length() > 0) sb.append(", ");
            sb.append(label).append('=').append(n);
        }

        void note(String text) { notes.add(text); }
    }

    // =========================================================
    //                      入口：几何
    // =========================================================

    public static BedrockGeometryModel parseGeometry(JsonObject root) {
        return parseGeometry(root, BedrockAxes.DEFAULT, new Diagnostics());
    }

    /**
     * 解析几何体。
     *
     * @param root  已解析的 JSON 根对象
     * @param axes  坐标系转换（见 {@link BedrockAxes}）
     * @param diag  诊断收集器；传 {@code null} 表示不关心
     * @return 解析结果；根对象不可用时返回 {@code null}
     */
    public static BedrockGeometryModel parseGeometry(JsonObject root,
                                                     BedrockAxes axes,
                                                     Diagnostics diag) {
        if (root == null) return null;
        BedrockAxes ax = axes == null ? BedrockAxes.DEFAULT : axes;
        Diagnostics d = diag == null ? new Diagnostics() : diag;

        JsonArray geos = asArray(root.get("minecraft:geometry"));
        if (geos == null || geos.isEmpty()) {
            d.note("no minecraft:geometry array");
            return null;
        }

        JsonObject geo = asObject(geos.get(0));
        if (geo == null) {
            d.note("geometry[0] is not an object");
            return null;
        }

        JsonObject desc = asObject(geo.get("description"));
        Identifier tex = desc == null ? null : readIdentifier(desc, "texture");
        int tw = desc == null ? 64 : readInt(desc, "texture_width", 64);
        int th = desc == null ? 64 : readInt(desc, "texture_height", 64);
        if (tw <= 0 || th <= 0) {
            d.note("non-positive texture size " + tw + "x" + th + ", falling back to 64x64");
            tw = tw <= 0 ? 64 : tw;
            th = th <= 0 ? 64 : th;
        }

        JsonArray bonesArr = asArray(geo.get("bones"));
        if (bonesArr == null) {
            d.note("geometry has no bones array");
            return null;
        }

        // ---- 第一遍：读出全部骨骼，此阶段不关心顺序 ----
        Map<String, BedrockGeometryModel.Bone> map = new LinkedHashMap<>();
        for (JsonElement e : bonesArr) {
            if (map.size() >= MAX_BONES) {
                d.note("bone count exceeds " + MAX_BONES + ", remaining bones ignored");
                break;
            }
            BedrockGeometryModel.Bone bone = parseBone(asObject(e), ax, d, tw, th);
            if (bone == null) continue;
            if (map.putIfAbsent(bone.name(), bone) != null) {
                d.note("duplicate bone name '" + bone.name() + "', later definition ignored");
            }
        }

        // ---- 第二遍：连接父子。父骨骼无论写在前后都能找到 ----
        List<BedrockGeometryModel.Bone> roots = new ArrayList<>();
        for (BedrockGeometryModel.Bone bone : map.values()) {
            String parentName = bone.parentName();
            if (parentName == null) {
                roots.add(bone);
                continue;
            }
            BedrockGeometryModel.Bone parent = map.get(parentName);
            if (parent == null || parent == bone) {
                if (parent == null) {
                    d.note("bone '" + bone.name() + "' references missing parent '"
                            + parentName + "', treated as root");
                    d.orphanBones++;
                } else {
                    d.note("bone '" + bone.name() + "' is its own parent, treated as root");
                    d.cyclesBroken++;
                }
                roots.add(bone);
                continue;
            }
            parent.children().add(bone);
        }

        // ---- 第三遍：打断环，确保渲染递归必然终止 ----
        breakCycles(map, roots, d);

        return new BedrockGeometryModel(tex, tw, th, List.copyOf(roots), Map.copyOf(map));
    }

        private static BedrockGeometryModel.Bone parseBone(JsonObject b,
                                                           BedrockAxes ax,
                                                           Diagnostics d,
                                                           int texW, int texH) {
        if (b == null) {
            d.invalidBones++;
            return null;
        }
        String name = readString(b, "name", null);
        if (name == null || name.isEmpty()) {
            d.invalidBones++;
            d.note("bone without a name was skipped");
            return null;
        }

        String parent = readString(b, "parent", null);

        Vector3f pivot = readVec3(b.get("pivot"));
        Vector3f rotation = readVec3(b.get("rotation"));
        ax.applyBone(pivot, rotation);

        List<BedrockGeometryModel.Cube> cubes = new ArrayList<>();
        JsonArray cubesArr = asArray(b.get("cubes"));
        if (cubesArr != null) {
            for (JsonElement ce : cubesArr) {
                if (cubes.size() >= MAX_CUBES_PER_BONE) {
                    d.note("bone '" + name + "' exceeds " + MAX_CUBES_PER_BONE
                            + " cubes, extra cubes ignored");
                    break;
                }
                BedrockGeometryModel.Cube c = parseCube(asObject(ce), ax, d, name, texW, texH);
                if (c != null) cubes.add(c);
            }
        }

        return new BedrockGeometryModel.Bone(
                name, parent, pivot, rotation, cubes, new ArrayList<>());
    }

    /**
     * 打断骨骼父子关系中的环。
     *
     * <p>环会让渲染器的递归渲染栈溢出 —— 这是旧版完全没有防护的一类崩溃。
     * 这里从每个根骨骼出发做一次可达性标记，凡是没被标记到却又挂在某个父骨骼下的
     * 骨骼，说明它位于一个环里：把它提升为根骨骼并断开父连接，保证渲染一定终止。
     */
    private static void breakCycles(Map<String, BedrockGeometryModel.Bone> map,
                                    List<BedrockGeometryModel.Bone> roots,
                                    Diagnostics d) {
        Set<BedrockGeometryModel.Bone> reachable =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<BedrockGeometryModel.Bone> stack = new ArrayDeque<>(roots);

        while (!stack.isEmpty()) {
            BedrockGeometryModel.Bone bone = stack.pop();
            if (!reachable.add(bone)) continue;
            for (BedrockGeometryModel.Bone child : bone.children()) {
                if (!reachable.contains(child)) stack.push(child);
            }
        }

        for (BedrockGeometryModel.Bone bone : map.values()) {
            if (reachable.contains(bone)) continue;

            // 这个骨骼在环里：从父骨骼的 children 中摘掉它，再作为根
            String parentName = bone.parentName();
            if (parentName != null) {
                BedrockGeometryModel.Bone parent = map.get(parentName);
                if (parent != null && parent.children().remove(bone)) {
                    d.cyclesBroken++;
                    d.note("cycle detected around bone '" + bone.name()
                            + "', parent link to '" + parentName + "' severed");
                }
            }
            roots.add(bone);
            for (BedrockGeometryModel.Bone child : bone.children()) stack.push(child);
            while (!stack.isEmpty()) {
                BedrockGeometryModel.Bone b = stack.pop();
                if (!reachable.add(b)) continue;
                stack.addAll(b.children());
            }
        }
    }

    // =========================================================
    //                      Cube
    // =========================================================

    private static BedrockGeometryModel.Cube parseCube(JsonObject c,
                                                       BedrockAxes ax,
                                                       Diagnostics d,
                                                       String boneName,
                                                       int texW, int texH) {
        if (c == null) {
            d.invalidCubes++;
            return null;
        }

        JsonArray oArr = asArray(c.get("origin"));
        JsonArray sArr = asArray(c.get("size"));
        if (oArr == null || sArr == null) {
            d.invalidCubes++;
            d.note("cube in bone '" + boneName + "' lacks origin/size and was skipped");
            return null;
        }

        Vector3f origin = readVec3(oArr);
        Vector3f size = readVec3(sArr);

        // ---- inflate：向外扩张，用于封住相邻面之间的接缝 ----
        // 顺序很关键：先 inflate（在未镜像的原始坐标里对称扩张），再镜像。
        // 反过来的话膨胀会把模型整体推偏。
        float inflate = readFloat(c, "inflate", 0f);
        if (inflate != 0f) {
            origin = new Vector3f(origin.x - inflate, origin.y - inflate, origin.z - inflate);
            size = new Vector3f(
                    size.x + inflate * 2f,
                    size.y + inflate * 2f,
                    size.z + inflate * 2f);
        }

        Vector3f pivot = c.has("pivot") && !c.get("pivot").isJsonNull()
                ? readVec3(c.get("pivot")) : null;
        Vector3f rotation = readVec3(c.get("rotation"));
        int uvRotation = Math.round(readFloat(c, "uv_rotation", 0f));

        boolean mirror = readBoolean(c, "mirror", false);

        ax.applyCube(origin, size, pivot, rotation, mirror);

        if (size.x() == 0f || size.y() == 0f || size.z() == 0f) {
            // 记录但不丢弃：单轴为 0 的 cube 是"平面片"，仍有可见的面。
            d.degenerateCubes++;
        }

        Map<String, BedrockGeometryModel.FaceUv> faceUvs = new LinkedHashMap<>();

        if (asObject(c.get("uv")) != null) {
            JsonObject fo = asObject(c.get("uv"));
            for (String face : FACES) {
                JsonObject faceObj = asObject(fo.get(face));
                if (faceObj == null) continue;
                BedrockGeometryModel.FaceUv uv = parseFace(faceObj, size, face, uvRotation, d);
                if (uv != null) faceUvs.put(face, uv);
            }
        } else {
            // Box UV（整块贴图）或完全没有 uv：用几何尺寸推出每个面的默认 UV。
            // 旧版在这里直接丢弃整个 cube，表现为"模型凭空少一块"。
            // 现在退化为按几何尺寸铺贴，保证几何至少可见且可诊断。
            d.note("cube in bone '" + boneName
                    + "' has no per-face uv; generated default face uv from geometry size");
            for (String face : FACES) {
                float[] def = defaultFaceSize(face, size);
                if (def[0] <= 0f || def[1] <= 0f) continue;
                faceUvs.put(face, new BedrockGeometryModel.FaceUv(
                        0f, 0f, def[0], def[1], false, false, uvRotation));
            }
        }
        return new BedrockGeometryModel.Cube(
                origin, size, pivot, rotation, faceUvs, mirror, uvRotation);
    }

    /**
     * 解析单个面。
     *
     * <p>关键：{@code uv_size} 某维为 0 时用<b>几何尺寸</b>兜底，而不是丢弃面。
     * Blockbench 对"某一轴 size = 0"的 cube 会把垂直于该轴的面写 0 尺寸，
     * 但这些面在几何上仍然有面积（例如 Y=0 薄片的 UP/DOWN 面）。
     *
     * <h2>与基岩原版的行为差异（有意为之）</h2>
     * 基岩原版在算 UV 图时会把 cube 尺寸<b>向下取整</b>，因此任何小于 1 单位的尺寸
     * 都会得到 0 像素宽的 UV 图并表现为贴图错乱 —— 这是官方已知缺陷，
     * 见 bedrock-wiki 的 "Texture Glitch" 条目，官方给的建议是改模型
     * （加 Inflate，或 size+1 再 inflate -1）。
     *
     * <p>本解析器<b>不做这个取整</b>：即使 cube 只有 0.3 单位厚，也按作者写下的
     * {@code uv_size} 精确取样。这样亚像素薄块也能正常渲染，代价是当纹理分辨率
     * 过低（一个 texel 都比该面还大）时无法表达细节 —— 那种情况由
     * {@link Diagnostics} 记录，而不是静默出错。
     */
    private static BedrockGeometryModel.FaceUv parseFace(JsonObject f,
                                                         Vector3f size,
                                                         String face,
                                                         int uvRotation,
                                                         Diagnostics d) {
        if (f == null) return null;

        JsonArray uv = asArray(f.get("uv"));
        if (uv == null || uv.size() < 2) {
            d.droppedFaces++;
            return null;
        }

        float u = readFloat(uv, 0, 0f);
        float v = readFloat(uv, 1, 0f);

        float[] def = defaultFaceSize(face, size);
        float w, h;
        boolean flipU = false, flipV = false;

        JsonArray sz = asArray(f.get("uv_size"));
        if (sz != null && sz.size() >= 2) {
            float rawW = readFloat(sz, 0, 0f);
            float rawH = readFloat(sz, 1, 0f);

            // 负 uv_size 在基岩里表示"从原点往负方向铺"，等价于翻转 + 正尺寸。
            flipU = rawW < 0f;
            flipV = rawH < 0f;
            if (flipU) { u += rawW; rawW = -rawW; }
            if (flipV) { v += rawH; rawH = -rawH; }

            w = rawW == 0f ? def[0] : rawW;
            h = rawH == 0f ? def[1] : rawH;
        } else {
            w = def[0];
            h = def[1];
        }

        if (w <= 0f || h <= 0f) {
            d.droppedFaces++;
            return null;
        }

        // 亚像素面：记录，让作者知道该提高纹理分辨率
        if (w < 1f || h < 1f) {
            d.subTexelFaces++;
        }

        // UV 坐标不做任何取整/吸附，原样保留作者写下的值。
        //
        // 曾经试过把 UV 原点四舍五入到 texel 网格以减少渗出，但那会改变渲染结果
        // （例如 uv=(0.5,0) → (1,0)），在没有实机验证的情况下属于多余的变量。
        // 渗出问题若日后确实需要处理，必须先在游戏里确认它真的是问题。
        return new BedrockGeometryModel.FaceUv(u, v, w, h, flipU, flipV, uvRotation);
    }

    /**
     * 某一面在"没有显式 uv_size"时应该占用的几何尺寸。
     * 面朝向北/南时贴图覆盖宽×高，东/西覆盖深×高，上/下覆盖宽×深。
     */
    private static float[] defaultFaceSize(String face, Vector3f size) {
        float w = Math.abs(size.x()), h = Math.abs(size.y()), d = Math.abs(size.z());
        return switch (face) {
            case "north", "south" -> new float[]{ w, h };
            case "east",  "west"  -> new float[]{ d, h };
            case "up",    "down"  -> new float[]{ w, d };
            default -> new float[]{ w, h };
        };
    }

    // =========================================================
    //                      入口：动画
    // =========================================================

    public static Map<String, BedrockAnimationModel.Animation> parseAnimations(JsonObject root) {
        return parseAnimations(root, BedrockAxes.DEFAULT, new Diagnostics());
    }

    public static Map<String, BedrockAnimationModel.Animation> parseAnimations(
            JsonObject root, BedrockAxes axes, Diagnostics diag) {
        Map<String, BedrockAnimationModel.Animation> out = new LinkedHashMap<>();
        if (root == null) return out;

        BedrockAxes ax = axes == null ? BedrockAxes.DEFAULT : axes;
        Diagnostics d = diag == null ? new Diagnostics() : diag;

        JsonObject anims = asObject(root.get("animations"));
        if (anims == null) {
            d.note("no animations object");
            return out;
        }

        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            JsonObject a = asObject(e.getValue());
            if (a == null) {
                d.note("animation '" + e.getKey() + "' is not an object, skipped");
                continue;
            }

            float length = readFloat(a, "animation_length", 1f);
            if (!(length > 0f) || Float.isInfinite(length)) {
                d.note("animation '" + e.getKey() + "' has non-positive length, using 1s");
                length = 1f;
            }

            boolean loop = readLoopMode(a, e.getKey(), d);

            Map<String, BedrockAnimationModel.BoneTracks> tracks = new LinkedHashMap<>();
            JsonObject bones = asObject(a.get("bones"));
            if (bones != null) {
                for (Map.Entry<String, JsonElement> be : bones.entrySet()) {
                    JsonObject b = asObject(be.getValue());
                    if (b == null) continue;

                    List<BedrockAnimationModel.Keyframe> rot = mirrorKeys(
                            parseKeys(b.get("rotation"), be.getKey(), "rotation", d),
                            ax, true);
                    List<BedrockAnimationModel.Keyframe> pos = mirrorKeys(
                            parseKeys(b.get("position"), be.getKey(), "position", d),
                            ax, false);
                    // scale 不受轴镜像影响：缩放是各轴独立的倍率。
                    List<BedrockAnimationModel.Keyframe> scl =
                            parseKeys(b.get("scale"), be.getKey(), "scale", d);

                    tracks.put(be.getKey(),
                            new BedrockAnimationModel.BoneTracks(rot, pos, scl));
                }
            }
            out.put(e.getKey(), new BedrockAnimationModel.Animation(length, loop, tracks));
        }
        return out;
    }

    /**
     * 读取循环模式，接受 Blockbench 导出的全部合法写法。
     *
     * <p>历史缺陷：旧版写作 {@code "true".equals(el.getAsString())}，
     * 当 {@code loop} 是布尔 {@code true} 时 {@code getAsString()} 会抛
     * {@code UnsupportedOperationException}。而布尔写法是 Blockbench 的正常输出。
     */
    private static boolean readLoopMode(JsonObject a, String animName, Diagnostics d) {
        JsonElement el = a.get("loop");
        if (el == null || el.isJsonNull()) return false;

        if (el.isJsonPrimitive()) {
            var prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isString()) {
                String s = prim.getAsString().trim();
                if (s.equalsIgnoreCase("true")) return true;
                if (s.equalsIgnoreCase("false") || s.isEmpty()) return false;
                // "hold_on_last_frame" 的语义是"播完停在最后一帧"，由 Animation.loop=false
                // 加时长 clamp 实现（见 BedrockRenderPipeline.sample），不是无限循环。
                return false;
            }
            if (prim.isNumber()) return prim.getAsInt() != 0;
        }
        d.note("animation '" + animName + "' has unrecognised loop value, treated as no-loop");
        return false;
    }

    /**
     * 对一组关键帧做轴向转换。
     *
     * <p>分量语义由 {@link BedrockAxes} 统一定义，这里只负责搬运，
     * 不再自己决定该取反哪个轴 —— 那正是旧版各处的分歧来源。
     *
     * @param isRotation true = 旋转通道，false = 位移通道
     */
    private static List<BedrockAnimationModel.Keyframe> mirrorKeys(
            List<BedrockAnimationModel.Keyframe> keys,
            BedrockAxes ax, boolean isRotation) {
        if (keys == null || keys.isEmpty() || !ax.mirrorsAnimation()) return keys;

        List<BedrockAnimationModel.Keyframe> out = new ArrayList<>(keys.size());
        for (BedrockAnimationModel.Keyframe k : keys) {
            Vector3f v = k.value();
            Vector3f mapped = isRotation
                    ? ax.applyAnimationRotation(v.x(), v.y(), v.z())
                    : ax.applyAnimationPosition(v.x(), v.y(), v.z());
            out.add(new BedrockAnimationModel.Keyframe(k.time(), mapped));
        }
        return out;
    }

    private static List<BedrockAnimationModel.Keyframe> parseKeys(JsonElement e,
                                                                  String boneName,
                                                                  String channel,
                                                                  Diagnostics d) {
        List<BedrockAnimationModel.Keyframe> list = new ArrayList<>();
        JsonObject obj = asObject(e);
        if (obj == null) return list;

        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            float time;
            try {
                time = Float.parseFloat(entry.getKey());
            } catch (NumberFormatException ex) {
                d.note("bone '" + boneName + "' " + channel
                        + " has non-numeric keyframe time '" + entry.getKey() + "'");
                d.invalidKeyframes++;
                continue;
            }

            JsonElement v = entry.getValue();
            // 关键帧可以写成裸数组，也可以写成带 pre/post 的对象。
            // 采样是线性插值，post 是"经过该时间点之后"的值，取 post 与旧行为一致。
            JsonObject vObj = asObject(v);
            if (vObj != null && vObj.has("post")) {
                v = vObj.get("post");
            }

            JsonArray a = asArray(v);
            if (a == null || a.size() < 3) {
                d.note("bone '" + boneName + "' " + channel
                        + " keyframe at " + entry.getKey() + " is not a 3-element array");
                d.invalidKeyframes++;
                continue;
            }
            list.add(new BedrockAnimationModel.Keyframe(time, new Vector3f(
                    readFloat(a, 0, 0f),
                    readFloat(a, 1, 0f),
                    readFloat(a, 2, 0f))));
        }
        list.sort(Comparator.comparingDouble(BedrockAnimationModel.Keyframe::time));
        return list;
    }

    // =========================================================
    //                      JSON 取值（全部容错）
    // =========================================================

    private static JsonObject asObject(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray asArray(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String readString(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static Identifier readIdentifier(JsonObject o, String key) {
        String s = readString(o, key, null);
        return s == null ? null : Identifier.tryParse(s);
    }

    private static int readInt(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static float readFloat(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsFloat();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static float readFloat(JsonArray a, int index, float fallback) {
        if (a == null || index < 0 || index >= a.size()) return fallback;
        JsonElement e = a.get(index);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsFloat();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        var prim = e.getAsJsonPrimitive();
        try {
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isNumber()) return prim.getAsInt() != 0;
            if (prim.isString()) return Boolean.parseBoolean(prim.getAsString().trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
        return fallback;
    }

    private static Vector3f readVec3(JsonElement e) {
        JsonArray a = asArray(e);
        if (a == null) return new Vector3f();
        return new Vector3f(
                readFloat(a, 0, 0f),
                readFloat(a, 1, 0f),
                readFloat(a, 2, 0f));
    }
}
