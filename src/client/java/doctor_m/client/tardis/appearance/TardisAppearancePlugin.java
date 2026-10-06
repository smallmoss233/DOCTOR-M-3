package doctor_m.client.tardis.appearance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class TardisAppearancePlugin
        implements PreparableModelLoadingPlugin<TardisAppearanceData> {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("TARDIS");

    /** 扫描目录：assets/doctor_m/tardis/exterior/*.json */
    private static final String DIR = "tardis/exterior";

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
            Map<Identifier, TardisAppearance> map = new HashMap<>();

            var resources = manager.listResources(DIR,
                    loc -> loc.getPath().endsWith(".json"));

            for (var entry : resources.entrySet()) {
                Identifier fileId = entry.getKey();
                String path = fileId.getPath();
                String name = path.substring(DIR.length() + 1,
                        path.length() - ".json".length());
                Identifier appearanceId = Identifier.fromNamespaceAndPath(
                        fileId.getNamespace(), name);

                try (Reader reader = entry.getValue().openAsReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    TardisAppearance app = parse(appearanceId, json);
                    if (app != null) map.put(appearanceId, app);
                } catch (Exception e) {
                    LOGGER.warn("[TARDIS] failed to load appearance {}", fileId, e);
                }
            }
            return new TardisAppearanceData(Map.copyOf(map));
        }, executor);
    }

    private static TardisAppearance parse(Identifier id, JsonObject json) {
        String display = json.has("display_name")
                ? json.get("display_name").getAsString() : id.getPath();

        TardisAsset exterior = parseBedrockAsset(json, "exterior");
        TardisAsset interior = parseBedrockAsset(json, "interior");
        if (exterior == null || interior == null) {
            LOGGER.warn("[TARDIS] appearance {} missing bedrock assets", id);
            return null;
        }

        boolean variant = json.has("variant") && json.get("variant").getAsBoolean();

        String category = null;
        if (json.has("category") && !json.get("category").isJsonNull()) {
            String s = json.get("category").getAsString().trim();
            if (!s.isEmpty()) category = s;
        }

        return new TardisAppearance(id, display, exterior, interior, variant, category);
    }

    private static TardisAsset parseBedrockAsset(JsonObject json, String prefix) {
        if (!json.has(prefix) || !json.get(prefix).isJsonObject()) return null;
        JsonObject o = json.getAsJsonObject(prefix);

        Identifier geo  = readId(o, "geometry");
        Identifier anim = readId(o, "animation");
        Identifier tex  = readId(o, "texture");
        if (geo == null || anim == null) return null;

        String openAnim  = o.has("open")  ? o.get("open").getAsString()  : "animation.open";
        String closeAnim = o.has("close") ? o.get("close").getAsString() : "animation.close";

        float ox = 0f, oy = 0f, oz = 0f, sc = 1f;
        if (o.has("offset")) {
            var a = o.getAsJsonArray("offset");
            ox = a.get(0).getAsFloat();
            oy = a.get(1).getAsFloat();
            oz = a.get(2).getAsFloat();
        }
        if (o.has("scale")) sc = o.get("scale").getAsFloat();

        return new TardisAsset.Bedrock(geo, anim, tex,
                openAnim, closeAnim, ox, oy, oz, sc);
    }

    private static Identifier readId(JsonObject json, String key) {
        if (!json.has(key)) return null;
        return Identifier.tryParse(json.get(key).getAsString());
    }
}