package doctor_m.client.tardis.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.client.tardis.appearance.TardisAppearance;
import doctor_m.client.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.client.tardis.appearance.TardisAsset;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockCache {

    private BedrockCache() {}

    private static final Map<Identifier, BedrockGeometryModel> GEOMETRY =
            new ConcurrentHashMap<>();
    private static final Map<Identifier, Map<String, BedrockAnimationModel.Animation>> ANIMATIONS =
            new ConcurrentHashMap<>();

    public static BedrockGeometryModel geometry(Identifier id) {
        return id == null ? null : GEOMETRY.get(id);
    }

    public static BedrockAnimationModel.Animation animation(Identifier id, String animName) {
        if (id == null || animName == null) return null;
        Map<String, BedrockAnimationModel.Animation> map = ANIMATIONS.get(id);
        return map == null ? null : map.get(animName);
    }

    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath("doctor_m", "tardis_bedrock");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        GEOMETRY.clear();
                        ANIMATIONS.clear();

                        Set<Identifier> geoIds = new HashSet<>();
                        Set<Identifier> animIds = new HashSet<>();
                        for (TardisAppearance app : TardisAppearanceRegistry.all().values()) {
                            collect(app.exterior(), geoIds, animIds);
                            collect(app.interior(), geoIds, animIds);
                        }

                        for (Identifier id : geoIds) {
                            JsonObject json = loadJson(rm, id);
                            if (json == null) continue;
                            BedrockGeometryModel g = BedrockParser.parseGeometry(json);
                            if (g != null) GEOMETRY.put(id, g.mirrorX());
                        }
                        for (Identifier id : animIds) {
                            JsonObject json = loadJson(rm, id);
                            if (json == null) continue;
                            Map<String, BedrockAnimationModel.Animation> parsed = BedrockParser.parseAnimations(json);
                            ANIMATIONS.put(id, BedrockAnimationModel.mirrorX(parsed));
                        }
                    }
                });
    }

    private static void collect(TardisAsset asset, Set<Identifier> geo, Set<Identifier> anim) {
        if (asset instanceof TardisAsset.Bedrock b) {
            if (b.geometry() != null) geo.add(b.geometry());
            if (b.animation() != null) anim.add(b.animation());
        }
    }

    private static JsonObject loadJson(ResourceManager rm, Identifier id) {
        Identifier file = Identifier.fromNamespaceAndPath(
                id.getNamespace(), id.getPath() + ".json");
        Optional<Resource> r = rm.getResource(file);
        if (r.isEmpty()) return null;
        try (Reader reader = r.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}