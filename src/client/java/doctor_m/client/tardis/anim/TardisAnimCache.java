package doctor_m.client.tardis.anim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class TardisAnimCache {

    private TardisAnimCache() {}

    private static final Map<Identifier, TardisAnimModel> CACHE = new HashMap<>();

    public static TardisAnimModel get(Identifier id) {
        return id == null ? null : CACHE.get(id);
    }

    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath("doctor_m", "tardis_anim_models");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        CACHE.clear();
                        for (TardisAppearance app : TardisAppearanceRegistry.all().values()) {
                            for (Identifier mid : app.allModelIds()) {
                                if (mid == null) continue;
                                Identifier path = Identifier.fromNamespaceAndPath(
                                        mid.getNamespace(), "models/" + mid.getPath() + ".json");
                                Optional<Resource> res = rm.getResource(path);
                                if (res.isEmpty()) continue;
                                try (Reader r = res.get().openAsReader()) {
                                    JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
                                    CACHE.put(mid, TardisAnimParser.parse(json));
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                    }
                });
    }
}