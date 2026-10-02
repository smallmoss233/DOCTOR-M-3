package doctor_m.client.tardis.anim;

import net.minecraft.resources.Identifier;
import java.util.HashMap;
import java.util.Map;

public final class TardisAnimationModelCache {

    private TardisAnimationModelCache() {}

    private static final Map<Identifier, TardisAnimationModelData> CACHE = new HashMap<>();

    public static void put(Identifier id, TardisAnimationModelData data) {
        CACHE.put(id, data);
    }

    public static TardisAnimationModelData get(Identifier id) {
        return CACHE.get(id);
    }

    public static void clear() {
        CACHE.clear();
    }
}