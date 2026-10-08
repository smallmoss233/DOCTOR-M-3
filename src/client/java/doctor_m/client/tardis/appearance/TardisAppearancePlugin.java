package doctor_m.client.tardis.appearance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 扫描 {@code assets/doctor_m/tardis/exterior/*.json}，构建外观注册表。
 *
 * <p><b>关于容错</b>：这个解析器跑在<b>资源重载线程</b>上，而它读的是资源包内容
 * （属于外部输入）。旧版对 {@code offset} / {@code scale} 等字段直接调
 * {@code getAsFloat()}，一个手写错误的 JSON 就会抛异常 —— 在重载线程上意味着
 * 整个资源重载失败。现在所有取值都走容错路径，坏文件只会被跳过并记录原因。
 *
 * <p>单个外观解析失败不影响其他外观：这是"一个模型坏了不要拖垮全部"的最低要求。
 */
public final class TardisAppearancePlugin
        implements PreparableModelLoadingPlugin<TardisAppearanceData> {

    private static final Logger LOGGER = LoggerFactory.getLogger("TARDIS");

    /** 扫描目录：assets/doctor_m/tardis/exterior/*.json */
    private static final String DIR = "tardis/exterior";
    private static final String SUFFIX = ".json";

    private TardisAppearancePlugin() {}

    public static void register() {
        PreparableModelLoadingPlugin.register(
                TardisAppearancePlugin::load,
                new TardisAppearancePlugin());
    }

    @Override
    public void initialize(TardisAppearanceData data, ModelLoadingPlugin.Context ctx) {
        TardisAppearanceRegistry.setAll(data.appearances());
        LOGGER.info("[TARDIS] loaded {} appearance(s)", data.appearances().size());
    }

    private static CompletableFuture<TardisAppearanceData> load(
            PreparableReloadListener.SharedState state, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceManager manager = state.resourceManager();

            // TreeMap：让加载顺序稳定，日志与注册表遍历可复现。
            Map<Identifier, TardisAppearance> map = new TreeMap<>(
                    (a, b) -> a.toString().compareTo(b.toString()));
            List<String> problems = new ArrayList<>();

            Map<Identifier, Resource> resources = manager.listResources(
                    DIR, loc -> loc.getPath().endsWith(SUFFIX));

            for (var entry : resources.entrySet()) {
                Identifier fileId = entry.getKey();
                Identifier appearanceId = appearanceIdOf(fileId);
                if (appearanceId == null) {
                    problems.add("cannot derive appearance id from " + fileId);
                    continue;
                }

                try (Reader reader = entry.getValue().openAsReader()) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (root == null || !root.isJsonObject()) {
                        problems.add(fileId + ": root is not a JSON object");
                        continue;
                    }
                    TardisAppearance app = parse(appearanceId, root.getAsJsonObject(), problems);
                    if (app != null) map.put(appearanceId, app);
                } catch (Exception e) {
                    problems.add(fileId + ": " + e);
                    LOGGER.warn("[TARDIS] failed to load appearance {}", fileId, e);
                }
            }

            if (!problems.isEmpty()) {
                LOGGER.warn("[TARDIS] {} appearance problem(s):\n  {}",
                        problems.size(), String.join("\n  ", problems));
            }
            return new TardisAppearanceData(Map.copyOf(map));
        }, executor);
    }

    /** {@code tardis/exterior/police_box.json} → {@code doctor_m:police_box} */
    private static Identifier appearanceIdOf(Identifier fileId) {
        String path = fileId.getPath();
        if (!path.startsWith(DIR + "/") || !path.endsWith(SUFFIX)) return null;
        String name = path.substring(DIR.length() + 1, path.length() - SUFFIX.length());
        if (name.isEmpty()) return null;
        return Identifier.fromNamespaceAndPath(fileId.getNamespace(), name);
    }

    private static TardisAppearance parse(Identifier id, JsonObject json, List<String> problems) {
        String display = readString(json, "display_name", id.getPath());

        // 动画是可选的（静态模型外观）：只要求几何体存在。
        TardisAsset exterior = parseBedrockAsset(json, "exterior", id, problems);
        TardisAsset interior = parseBedrockAsset(json, "interior", id, problems);
        if (exterior == null || interior == null) {
            problems.add(id + ": missing usable exterior/interior bedrock asset");
            return null;
        }

        boolean variant = readBoolean(json, "variant", false);

        String category = readString(json, "category", null);
        if (category != null) {
            category = category.trim();
            if (category.isEmpty()) category = null;
        }

        return new TardisAppearance(id, display, exterior, interior, variant, category);
    }

    private static TardisAsset parseBedrockAsset(JsonObject json, String prefix,
                                                 Identifier appearanceId,
                                                 List<String> problems) {
        JsonObject o = asObject(json.get(prefix));
        if (o == null) return null;

        Identifier geo = readIdentifier(o, "geometry");
        if (geo == null) {
            problems.add(appearanceId + ": " + prefix + " has no valid 'geometry' id");
            return null;
        }

        // cube 欧拉角定序：按模型指定。Blockbench 手工自由旋转对定序极敏感，
        // 不同时期导出的模型可能不同，所以它必须是 per-model 而非全局。
        doctor_m.client.tardis.render.BedrockCache.setGeometryRotationOrder(
                geo,
                doctor_m.tardis.bedrock.BedrockRenderMath.parseOrder(
                        readString(o, "rotation_order", null), null));

        // animation 缺失 = 静态模型，允许。
        Identifier anim = readIdentifier(o, "animation");
        Identifier tex = readIdentifier(o, "texture");

        // 没有动画文件时，开关动画名必须为空，否则采样必然落空。
        String openAnim = anim == null ? "" : readString(o, "open", "");
        String closeAnim = anim == null ? "" : readString(o, "close", "");

        float[] offset = readVec3(o.get("offset"));
        float scale = readFloat(o, "scale", 1f);
        if (!(scale > 0f) || Float.isInfinite(scale)) {
            problems.add(appearanceId + ": " + prefix + " has non-positive scale, using 1.0");
            scale = 1f;
        }

        return new TardisAsset.Bedrock(geo, anim, tex,
                openAnim, closeAnim, offset[0], offset[1], offset[2], scale);
    }

    // =========================================================
    //                  容错取值
    // =========================================================

    private static JsonObject asObject(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
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

    private static float readFloat(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsFloat();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static Identifier readIdentifier(JsonObject o, String key) {
        String s = readString(o, key, null);
        if (s == null || s.isBlank()) return null;
        return Identifier.tryParse(s.trim());
    }

    /** 读取三元向量，长度不足或元素非法时按 0 补齐 —— 绝不抛异常。 */
    private static float[] readVec3(JsonElement e) {
        float[] out = { 0f, 0f, 0f };
        if (e == null || !e.isJsonArray()) return out;
        JsonArray a = e.getAsJsonArray();
        for (int i = 0; i < 3; i++) {
            if (i >= a.size()) break;
            JsonElement el = a.get(i);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) continue;
            try {
                out[i] = el.getAsFloat();
            } catch (RuntimeException ignored) {
                // 保持 0
            }
        }
        return out;
    }
}
