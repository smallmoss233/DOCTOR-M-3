package doctor_m.client.tardis.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import doctor_m.tardis.bedrock.BedrockAnimationModel;
import doctor_m.tardis.bedrock.BedrockAxes;
import doctor_m.tardis.bedrock.BedrockGeometryModel;
import doctor_m.tardis.bedrock.BedrockParser;
import doctor_m.tardis.bedrock.BedrockRenderMath;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基岩版几何 / 动画的按需缓存（<b>客户端专属</b>）。
 *
 * <h2>为什么在客户端源集</h2>
 * 它读的是客户端资源管理器，只服务渲染。旧版把它放在双端通用的 {@code src/main}
 * 里，同时又提供 {@code ensureServerGeometryLoaded} 服务端入口，于是同一份静态 map
 * 被客户端重载线程与服务端逻辑共同读写 —— 在集成服务器上就是一个真实的竞态。
 * 边界划在源集上，这类问题不会再出现。
 *
 * <h2>惰性加载取代预加载</h2>
 * 旧版在资源重载时扫描全部外观并预读资源，隐含依赖"外观注册表必须先于本缓存完成
 * 重载"，而两者的重载时机分属不同 Fabric 回调，顺序并无保证。现在改为首次请求时
 * 读取，监听器只负责清空，顺序依赖消失。
 *
 * <h2>失败可诊断</h2>
 * 加载失败不再只是"打一行日志然后模型凭空消失"，而是记录在案，
 * 可用 {@link #report()} 与 {@link #warmUp(Identifier)} 查询。
 */
public final class BedrockCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/bedrock");

    private BedrockCache() {}

    /**
     * 生产使用的坐标定式（镜像/动画语义部分）。
     *
     * <p>cube 的欧拉角定序<b>不在这里</b> —— 它按模型单独指定，见
     * {@link #setGeometryRotationOrder}。不同模型是在不同时期导出的，
     * 需要的定序不同，用全局值必然按下葫芦浮起瓢。
     */
    private static final BedrockAxes BASE_AXES = BedrockAxes.DEFAULT;

    // ============================================================
    //                   按模型的欧拉角定序
    // ============================================================

    /**
     * 几何体 ID → 该模型 cube 旋转的欧拉角定序。
     *
     * <p>由外观加载器在解析 JSON 的 {@code rotation_order} 字段时登记。
     * 未登记的几何体用 {@link BedrockRenderMath.EulerOrder#ZYX}（历史默认）。
     */
    private static final Map<Identifier, BedrockRenderMath.EulerOrder> ROTATION_ORDERS =
            new ConcurrentHashMap<>();

    /**
     * 为某个几何体指定 cube 欧拉角定序。
     *
     * <p>如果该几何体已经缓存过，会作废它以便用新定序重新解析。
     *
     * @param geometryId 几何体资源 ID
     * @param order      该模型导出时使用的定序；null 表示回到默认
     */
    public static void setGeometryRotationOrder(Identifier geometryId,
                                                BedrockRenderMath.EulerOrder order) {
        if (geometryId == null) return;
        BedrockRenderMath.EulerOrder previous =
                order == null ? ROTATION_ORDERS.remove(geometryId)
                              : ROTATION_ORDERS.put(geometryId, order);
        if (previous != order) {
            // 定序变了，缓存的几何体必须重新解析
            GEOMETRY.remove(geometryId);
            FAILED_GEOMETRY.remove(geometryId);
            LOGGER.debug("[bedrock] rotation order for {} = {}", geometryId, order);
        }
    }

    private static BedrockRenderMath.EulerOrder orderFor(Identifier geometryId) {
        return ROTATION_ORDERS.getOrDefault(geometryId,
                BedrockRenderMath.EulerOrder.ZYX);
    }

    public static void clearRotationOrders() {
        ROTATION_ORDERS.clear();
    }

    // ============================================================
    //                        缓存
    // ============================================================

    private static final Map<Identifier, BedrockGeometryModel> GEOMETRY =
            new ConcurrentHashMap<>();
    private static final Map<Identifier, Map<String, BedrockAnimationModel.Animation>> ANIMATIONS =
            new ConcurrentHashMap<>();

    /** 已确认不可用的资源，避免每帧重复读取并重复刷日志。 */
    private static final Set<Identifier> FAILED_GEOMETRY = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> FAILED_ANIMATION = ConcurrentHashMap.newKeySet();

    /** 本次加载过程中的问题，按资源 ID 归档。 */
    private static final Map<Identifier, String> DIAGNOSTICS = new ConcurrentHashMap<>();

    /**
     * 取几何体。未加载且未被判定为失败时，尝试即时从客户端资源包读取。
     *
     * @return 几何体；不可用时返回 {@code null}，调用方应跳过渲染而不是抛异常
     */
    public static BedrockGeometryModel geometry(Identifier id) {
        if (id == null) return null;

        BedrockGeometryModel cached = GEOMETRY.get(id);
        if (cached != null) return cached;
        if (FAILED_GEOMETRY.contains(id)) return null;

        return loadGeometry(id);
    }

    /**
     * 取动画。与 {@link #geometry} 同为按需加载。
     *
     * @return 动画片段；不存在时返回 {@code null}
     */
    public static BedrockAnimationModel.Animation animation(Identifier id, String animName) {
        if (id == null || animName == null) return null;

        Map<String, BedrockAnimationModel.Animation> map = animations(id);
        return map == null ? null : map.get(animName);
    }

    /** 取某个动画文件的全部片段；不可用时返回 null。 */
    public static Map<String, BedrockAnimationModel.Animation> animations(Identifier id) {
        if (id == null) return null;

        Map<String, BedrockAnimationModel.Animation> map = ANIMATIONS.get(id);
        if (map != null) return map;
        if (FAILED_ANIMATION.contains(id)) return null;

        return loadAnimations(id);
    }

    // ============================================================
    //                        加载
    // ============================================================

    private static BedrockGeometryModel loadGeometry(Identifier id) {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            // 资源管理器尚未就绪。不要标记为失败，下次请求再试。
            return null;
        }

        JsonObject json = loadJson(rm, id);
        if (json == null) {
            markGeometryFailed(id, "file not found or not valid JSON");
            return null;
        }

        BedrockParser.Diagnostics diag = new BedrockParser.Diagnostics();
        // 该几何体自己的欧拉角定序（未登记则用 ZYX）
        BedrockAxes axes = BASE_AXES.withCubeOrder(orderFor(id));
        BedrockGeometryModel model = BedrockParser.parseGeometry(json, axes, diag);
        if (model == null) {
            markGeometryFailed(id, "parser rejected geometry: " + diag.summary());
            return null;
        }

        if (!diag.isClean()) {
            // "能渲染但有问题"是最需要被看见的一类情况，旧版在这里完全静默。
            DIAGNOSTICS.put(id, diag.summary());
            LOGGER.warn("[bedrock] geometry {} parsed with issues: {}", id, diag.summary());
        }

        GEOMETRY.put(id, model);
        return model;
    }

    private static Map<String, BedrockAnimationModel.Animation> loadAnimations(Identifier id) {
        ResourceManager rm = resourceManager();
        if (rm == null) return null;

        JsonObject json = loadJson(rm, id);
        if (json == null) {
            FAILED_ANIMATION.add(id);
            DIAGNOSTICS.put(id, "animation file not found or not valid JSON");
            LOGGER.warn("[bedrock] animation {} not found", id);
            return null;
        }

        BedrockParser.Diagnostics diag = new BedrockParser.Diagnostics();
        Map<String, BedrockAnimationModel.Animation> parsed =
                BedrockParser.parseAnimations(json, BASE_AXES, diag);

        if (!diag.isClean()) {
            DIAGNOSTICS.put(id, diag.summary());
            LOGGER.warn("[bedrock] animation {} parsed with issues: {}", id, diag.summary());
        }
        if (parsed.isEmpty()) {
            // 空表也要入缓存：这是"文件存在但没有动画片段"，不该反复重读。
            LOGGER.warn("[bedrock] animation {} contains no animations", id);
        }

        ANIMATIONS.put(id, parsed);
        return parsed;
    }

    private static void markGeometryFailed(Identifier id, String reason) {
        FAILED_GEOMETRY.add(id);
        DIAGNOSTICS.put(id, reason);
        LOGGER.warn("[bedrock] geometry {} unavailable: {}", id, reason);
    }

    /** 客户端资源管理器；不可用时返回 null。 */
    private static ResourceManager resourceManager() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getResourceManager();
    }

    // ============================================================
    //                        诊断
    // ============================================================

    /** 一次加载报告的快照，用于排查"模型 / 动画没出来"。 */
    public record Report(int geometryCount, int animationCount,
                         Set<Identifier> failedGeometry,
                         Set<Identifier> failedAnimation,
                         Map<Identifier, String> issues) {

        public boolean hasProblems() {
            return !failedGeometry.isEmpty() || !failedAnimation.isEmpty() || !issues.isEmpty();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("geometry=").append(geometryCount)
              .append(" animation=").append(animationCount);
            if (!failedGeometry.isEmpty()) {
                sb.append("\n  failed geometry:");
                for (Identifier id : failedGeometry) sb.append("\n    ").append(id);
            }
            if (!failedAnimation.isEmpty()) {
                sb.append("\n  failed animation:");
                for (Identifier id : failedAnimation) sb.append("\n    ").append(id);
            }
            if (!issues.isEmpty()) {
                sb.append("\n  parsed with issues:");
                for (Map.Entry<Identifier, String> e : issues.entrySet()) {
                    sb.append("\n    ").append(e.getKey()).append(" -> ").append(e.getValue());
                }
            }
            return sb.toString();
        }
    }

    /** 取当前缓存与诊断的快照。 */
    public static Report report() {
        return new Report(
                GEOMETRY.size(),
                ANIMATIONS.size(),
                Collections.unmodifiableSet(FAILED_GEOMETRY),
                Collections.unmodifiableSet(FAILED_ANIMATION),
                Collections.unmodifiableMap(new LinkedHashMap<>(DIAGNOSTICS)));
    }

    /**
     * 主动加载某个外观当前引用的全部资源，用于在应用外观前暴露问题
     * （例如动画名拼错、几何文件缺失）。
     *
     * @return 问题描述列表；一切正常时为空
     */
    public static List<String> warmUp(Identifier appearanceId) {
        List<String> problems = new ArrayList<>();
        TardisAppearance app = TardisAppearanceRegistry.get(appearanceId);
        if (app == null) {
            problems.add("unknown appearance " + appearanceId);
            return problems;
        }
        warmUpAsset(app.exterior(), problems);
        warmUpAsset(app.interior(), problems);
        return problems;
    }

    private static void warmUpAsset(TardisAsset asset, List<String> problems) {
        if (!(asset instanceof TardisAsset.Bedrock b)) return;

        if (b.geometry() != null && geometry(b.geometry()) == null) {
            problems.add("geometry " + b.geometry() + " unavailable");
        }
        if (b.animation() != null) {
            Map<String, BedrockAnimationModel.Animation> all = animations(b.animation());
            if (all == null) {
                problems.add("animation " + b.animation() + " unavailable");
            } else {
                for (String name : new String[]{ b.openAnimation(), b.closeAnimation() }) {
                    if (name != null && !name.isEmpty() && !all.containsKey(name)) {
                        problems.add("animation " + b.animation()
                                + " has no clip named '" + name + "'");
                    }
                }
            }
        }
    }

    // ============================================================
    //                        重载
    // ============================================================

    /**
     * 注册资源重载监听器。
     *
     * <p>监听器只清空缓存与失败标记；真正的读取推迟到首次请求，
     * 因此不再依赖"外观注册表已先完成重载"这一隐式前提。
     */
    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath("doctor_m", "tardis_bedrock");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        invalidateAll();
                    }
                });
    }

    /** 清空全部缓存。资源重载与调试命令都走这里。 */
    public static void invalidateAll() {
        GEOMETRY.clear();
        ANIMATIONS.clear();
        FAILED_GEOMETRY.clear();
        FAILED_ANIMATION.clear();
        DIAGNOSTICS.clear();
    }

    // ============================================================
    //                        工具
    // ============================================================

    private static JsonObject loadJson(ResourceManager rm, Identifier id) {
        Identifier file = Identifier.fromNamespaceAndPath(
                id.getNamespace(), id.getPath() + ".json");
        Optional<Resource> r = rm.getResource(file);
        if (r.isEmpty()) return null;
        try (Reader reader = r.get().openAsReader()) {
            var element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                LOGGER.warn("[bedrock] {} is not a JSON object", file);
                return null;
            }
            return element.getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("[bedrock] failed to parse {}", file, e);
            return null;
        }
    }
}
