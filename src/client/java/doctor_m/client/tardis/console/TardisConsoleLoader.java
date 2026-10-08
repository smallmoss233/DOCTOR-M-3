package doctor_m.client.tardis.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
                                        parse(appearanceId, json);
                                if (app != null) loaded.put(appearanceId, app);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }

                        TardisConsoleRegistry.setAll(Map.copyOf(loaded));
                    }
                });
    }

    private static TardisConsoleAppearance parse(Identifier id, JsonObject json) {
        Identifier geometry = readId(json, "geometry");
        if (geometry == null) return null;

        Identifier animation = readId(json, "animation");
        Identifier texture   = readId(json, "texture");

        // cube 欧拉角定序：按模型指定，避免全局切换把其他模型一起改坏。
        // Blockbench 手工拖出来的自由旋转对定序极敏感，而不同时期导出的模型
        // 可能用不同定序，所以它必须是 per-model 的。
        doctor_m.client.tardis.render.BedrockCache.setGeometryRotationOrder(
                geometry,
                doctor_m.tardis.bedrock.BedrockRenderMath.parseOrder(
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
                modelFacing, ox, oy, oz, scale);
    }

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