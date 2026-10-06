package doctor_m.tardis.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/bedrock");

    /**
     * 几何体镜像 X。
     * 与 BedrockAnimationModel 的镜像策略分离——实测几何镜像、动画不镜像才是对的。
     */
    private static final boolean GEOMETRY_MIRROR_X = true;

    /** 动画不镜像。原因见 history：镜像后"上翻"动画会变成"下翻"。 */
    private static final boolean ANIM_MIRROR_X = true;

    private BedrockCache() {}

    private static final Map<Identifier, BedrockGeometryModel> GEOMETRY =
            new ConcurrentHashMap<>();
    private static final Map<Identifier, Map<String, BedrockAnimationModel.Animation>> ANIMATIONS =
            new ConcurrentHashMap<>();

    /** 客户端专属 ID（控制台等）。客户端初始化时通过下面两个方法登记。 */
    private static final Set<Identifier> CLIENT_GEOMETRY_IDS = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> CLIENT_ANIM_IDS     = ConcurrentHashMap.newKeySet();

    public static BedrockGeometryModel geometry(Identifier id) {
        return id == null ? null : GEOMETRY.get(id);
    }

    public static BedrockAnimationModel.Animation animation(Identifier id, String animName) {
        if (id == null || animName == null) return null;
        Map<String, BedrockAnimationModel.Animation> map = ANIMATIONS.get(id);
        return map == null ? null : map.get(animName);
    }

    public static void registerClientGeometry(Identifier id) {
        if (id != null) CLIENT_GEOMETRY_IDS.add(id);
    }

    public static void registerClientAnimation(Identifier id) {
        if (id != null) CLIENT_ANIM_IDS.add(id);
    }

    // ============================================================
    //                      按需加载
    // ============================================================

    /**
     * 立即从服务端资源管理器读一个几何体到缓存。已加载过则 no-op。
     * 用于"玩家换外观"时让门碰撞箱同步更新。
     */
    public static void ensureServerGeometryLoaded(MinecraftServer server, Identifier geoId) {
        if (server == null || geoId == null) return;
        if (GEOMETRY.containsKey(geoId)) return;

        ResourceManager rm = server.getResourceManager();
        if (rm == null) {
            LOGGER.warn("Server resource manager unavailable, cannot load {}", geoId);
            return;
        }
        JsonObject json = loadJson(rm, geoId);
        if (json == null) {
            LOGGER.warn("Server geometry {} not found in data/", geoId);
            return;
        }
        BedrockGeometryModel g = BedrockParser.parseGeometry(json, GEOMETRY_MIRROR_X);
        if (g != null) GEOMETRY.put(geoId, g);
    }

    // ============================================================
    //                      重载
    // ============================================================

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

                        Set<Identifier> geoIds  = new HashSet<>();
                        Set<Identifier> animIds = new HashSet<>();

                        for (TardisAppearance app : TardisAppearanceRegistry.all().values()) {
                            collect(app.exterior(), geoIds, animIds);
                            collect(app.interior(), geoIds, animIds);
                        }

                        geoIds.addAll(CLIENT_GEOMETRY_IDS);
                        animIds.addAll(CLIENT_ANIM_IDS);

                        for (Identifier id : geoIds) {
                            JsonObject json = loadJson(rm, id);
                            if (json == null) continue;
                            BedrockGeometryModel g =
                                    BedrockParser.parseGeometry(json, GEOMETRY_MIRROR_X);
                            if (g != null) GEOMETRY.put(id, g);
                        }
                        for (Identifier id : animIds) {
                            JsonObject json = loadJson(rm, id);
                            if (json == null) continue;
                            ANIMATIONS.put(id,
                                    BedrockParser.parseAnimations(json, ANIM_MIRROR_X));
                        }
                    }
                });
    }

    // ============================================================
    //                      工具
    // ============================================================

    private static void collect(TardisAsset asset, Set<Identifier> geo, Set<Identifier> anim) {
        if (asset instanceof TardisAsset.Bedrock b) {
            if (b.geometry()  != null) geo.add(b.geometry());
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
            LOGGER.warn("Failed to parse {}", file, e);
            return null;
        }
    }
}