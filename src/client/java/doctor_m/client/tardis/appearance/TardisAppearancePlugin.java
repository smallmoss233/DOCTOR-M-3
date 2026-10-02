package doctor_m.client.tardis.appearance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class TardisAppearancePlugin
        implements PreparableModelLoadingPlugin<TardisAppearanceData> {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("TARDIS");

    private TardisAppearancePlugin() {}

    public static void register() {
        PreparableModelLoadingPlugin.register(
                TardisAppearancePlugin::load,
                new TardisAppearancePlugin());
    }

    @Override
    public void initialize(TardisAppearanceData data, ModelLoadingPlugin.Context ctx) {
        TardisAppearanceRegistry.setAll(data.appearances());
        TardisModelTransforms.clear();

        Set<Identifier> ourModelIds = new HashSet<>();
        for (TardisAppearance app : data.appearances().values()) {
            ourModelIds.addAll(app.allModelIds());
        }

        Set<ExtraModelKey<BlockStateModel>> registered = new HashSet<>();
        int count = 0;
        for (Identifier modelId : ourModelIds) {
            if (modelId == null) continue;
            ExtraModelKey<BlockStateModel> key = TardisModelKeys.of(modelId);
            if (registered.add(key)) {
                ctx.addModel(key, new TardisExtraModel(modelId));
                count++;
            }
        }

        // ★ 挂 Hook：模型加载时读 display.fixed
        ctx.modifyModelOnLoad().register((model, context) -> {
            if (ourModelIds.contains(context.id())) {
                ItemTransforms ts = model.transforms();
                if (ts != null) {
                    ItemTransform fixed = ts.getTransform(ItemDisplayContext.FIXED);
                    TardisModelTransforms.put(context.id(), fixed);
                }
            }
            return model;
        });

        LOGGER.info("[TARDIS] registered {} extra model(s) for {} appearance(s)",
                count, data.appearances().size());
    }

    private static CompletableFuture<TardisAppearanceData> load(
            PreparableReloadListener.SharedState state, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceManager manager = state.resourceManager();
            Map<Identifier, TardisAppearance> map = new HashMap<>();

            var resources = manager.listResources("tardis_appearance",
                    loc -> loc.getPath().endsWith(".json"));

            for (var entry : resources.entrySet()) {
                Identifier fileId = entry.getKey();
                String path = fileId.getPath();
                String name = path.substring("tardis_appearance/".length(),
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
        Identifier ec = readId(json, "exterior_closed");
        Identifier eo = readId(json, "exterior_open");
        Identifier ic = readId(json, "interior_closed");
        Identifier io = readId(json, "interior_open");
        if (ec == null || eo == null || ic == null || io == null) {
            LOGGER.warn("[TARDIS] appearance {} missing required fields", id);
            return null;
        }
        return new TardisAppearance(id, display, ec, eo, ic, io);
    }

    private static Identifier readId(JsonObject json, String key) {
        if (!json.has(key)) return null;
        return Identifier.tryParse(json.get(key).getAsString());
    }
}