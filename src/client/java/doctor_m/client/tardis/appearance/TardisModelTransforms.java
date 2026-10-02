package doctor_m.client.tardis.appearance;

import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/** 缓存"模型 ID → display.fixed 变换"。资源重载时重建。 */
public final class TardisModelTransforms {

    private TardisModelTransforms() {}

    private static final Map<Identifier, ItemTransform> MAP = new HashMap<>();

    public static void put(Identifier modelId, ItemTransform t) {
        if (t != null) MAP.put(modelId, t);
    }

    public static ItemTransform get(Identifier modelId) {
        return MAP.get(modelId);
    }

    public static void clear() {
        MAP.clear();
    }
}