package doctor_m.client.tardis.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.tardis.console.*;
import mosslib.core.bedrock.BedrockCache;
import mosslib.core.bedrock.BedrockRenderMath;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * 扫描 assets/doctor_m/tardis/console/*.json 并填充 TardisConsoleRegistry。
 */
public final class TardisConsoleLoader {

    private TardisConsoleLoader() {}

    private static final String DIR = "tardis/console";

    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath("doctor_m", "tardis_console");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        Map<Identifier, TardisConsoleAppearance> loaded = new HashMap<>();

                        Map<Identifier, Resource> files = rm.listResources(
                                DIR, id -> id.getPath().endsWith(".json"));

                        for (var entry : files.entrySet()) {
                            Identifier fileId = entry.getKey();
                            // fileId 形如 doctor_m:tardis/console/classic.json
                            String path = fileId.getPath();
                            String name = path.substring(DIR.length() + 1,
                                    path.length() - ".json".length());
                            Identifier appearanceId =
                                    Identifier.fromNamespaceAndPath(fileId.getNamespace(), name);

                            try (Reader r = entry.getValue().openAsReader()) {
                                JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
                                TardisConsoleAppearance app =
                                        parse(appearanceId, json, rm);
                                if (app != null) loaded.put(appearanceId, app);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }

                        TardisConsoleRegistry.setAll(Map.copyOf(loaded));
                    }
                });
    }

    // =========================================================
    //                      解析入口
    // =========================================================

    private static TardisConsoleAppearance parse(Identifier id, JsonObject json,
                                                 ResourceManager rm) {
        Identifier geometry = readId(json, "geometry");
        if (geometry == null) return null;

        Identifier animation = readId(json, "animation");
        Identifier texture   = readId(json, "texture");

        // cube 欧拉角定序：按模型指定，避免全局切换把其他模型一起改坏。
        // Blockbench 手工拖出来的自由旋转对定序极敏感，而不同时期导出的模型
        // 可能用不同定序，所以它必须是 per-model 的。
        BedrockCache.setGeometryRotationOrder(
                geometry,
                BedrockRenderMath.parseOrder(
                        readString(json, "rotation_order"), null));

        String displayName = json.has("display_name")
                ? json.get("display_name").getAsString() : id.getPath();

        String idle = json.has("idle") && !json.get("idle").isJsonNull()
                ? json.get("idle").getAsString() : null;

        Direction modelFacing = Direction.NORTH;
        if (json.has("model_facing") && !json.get("model_facing").isJsonNull()) {
            try {
                modelFacing = Direction.valueOf(
                        json.get("model_facing").getAsString().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        float ox = 0f, oy = 0f, oz = 0f, scale = 1f;
        if (json.has("offset") && json.get("offset").isJsonArray()) {
            var arr = json.getAsJsonArray("offset");
            if (arr.size() >= 3) {
                ox = arr.get(0).getAsFloat();
                oy = arr.get(1).getAsFloat();
                oz = arr.get(2).getAsFloat();
            }
        }
        if (json.has("scale") && !json.get("scale").isJsonNull()) {
            scale = json.get("scale").getAsFloat();
        }

        return new TardisConsoleAppearance(
                id, displayName, geometry, animation, texture, idle,
                modelFacing, ox, oy, oz, scale,
                parseControls(json, rm));
    }

    // =========================================================
    //                      controls 解析
    // =========================================================

    /**
     * 解析外观 JSON 的 "controls" 字段。
     *
     * <pre>{@code
     * "controls": {
     *   "油门": { "type": "throttle", "tiers": 5, "animation": "animation.console.throttle" }
     * }
     * }</pre>
     *
     * <h2>duration 的取值优先级</h2>
     * <ol>
     *   <li>JSON 里显式写的 {@code duration}</li>
     *   <li>否则从该外观的动画文件里读对应片段的 {@code animation_length}</li>
     *   <li>否则用 {@link ControlType} 的默认时长</li>
     * </ol>
     * 优先级 2 让"改动画时长"和"改控制台手感"两件事解耦 —— 只要改
     * {@code .animation.json} 的 {@code animation_length}，游戏自动跟着变。
     */
    private static Map<String, ControlConfig> parseControls(JsonObject json,
                                                            ResourceManager rm) {
        if (!json.has("controls") || !json.get("controls").isJsonObject()) {
            return Map.of();
        }

        // 一次性读出动画文件里所有片段的长度，供下面逐个控件查
        Identifier animFileId = readId(json, "animation");
        Map<String, Float> animLengths = animFileId == null
                ? Map.of()
                : readAnimationLengths(rm, animFileId);

        JsonObject controls = json.getAsJsonObject("controls");
        Map<String, ControlConfig> out = new HashMap<>();

        for (var entry : controls.entrySet()) {
            String boneName = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject cfg = entry.getValue().getAsJsonObject();

            // type id：显式写；没写就从骨骼名推（ctrl_xxx）
            String typeId = readString(cfg, "type");
            if (typeId == null) {
                typeId = ConsoleBoneMatcher.typeId(boneName);
            }
            if (typeId == null) continue;

            ControlType type = ControlTypes.byId(typeId);
            if (type == null) continue;   // 未注册的类型，跳过

            int tiers = cfg.has("tiers")
                    ? cfg.get("tiers").getAsInt() : type.defaultTiers();

            TierMode mode = cfg.has("tier_mode")
                    ? TierMode.valueOf(cfg.get("tier_mode").getAsString().toUpperCase())
                    : type.defaultTierMode();

            String animationId = readString(cfg, "animation");

            // duration：手写 > 动画文件 > 类型默认
            float duration = type.defaultDuration();
            if (animationId != null) {
                Float auto = animLengths.get(animationId);
                if (auto != null) duration = auto;
            }
            if (cfg.has("duration")) {
                duration = cfg.get("duration").getAsFloat();
            }

            out.put(boneName, new ControlConfig(type, tiers, duration, mode, animationId));
        }
        return Map.copyOf(out);
    }

    /**
     * 读取动画文件里所有片段的长度（秒）。
     *
     * <p>不走 {@code BedrockCache} —— 那是懒加载 + 缓存，与本 Loader
     * 的重载顺序有微妙耦合（谁先跑不确定）。直接读资源管理器更稳。
     *
     * @return 片段名 → 长度；文件缺失或格式错误时返回空 map
     */
    private static Map<String, Float> readAnimationLengths(ResourceManager rm,
                                                           Identifier animFileId) {
        Identifier file = Identifier.fromNamespaceAndPath(
                animFileId.getNamespace(),
                animFileId.getPath() + ".json");

        var res = rm.getResource(file);
        if (res.isEmpty()) return Map.of();

        try (Reader r = res.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonObject animations = root.getAsJsonObject("animations");
            if (animations == null) return Map.of();

            Map<String, Float> out = new HashMap<>();
            for (var entry : animations.entrySet()) {
                JsonObject anim = entry.getValue().isJsonObject()
                        ? entry.getValue().getAsJsonObject() : null;
                if (anim == null) continue;
                if (anim.has("animation_length")) {
                    out.put(entry.getKey(), anim.get("animation_length").getAsFloat());
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // =========================================================
    //                      工具
    // =========================================================

    private static Identifier readId(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return null;
        return Identifier.tryParse(json.get(key).getAsString());
    }

    /** 容错读字符串；不存在或类型不对时返回 null。 */
    private static String readString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return null;
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}