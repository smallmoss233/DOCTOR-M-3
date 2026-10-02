package doctor_m.client.tardis.appearance;

import net.minecraft.resources.Identifier;

import java.util.Map;

public final class TardisAppearanceRegistry {

    private TardisAppearanceRegistry() {}

    private static volatile Map<Identifier, TardisAppearance> APPEARANCES = Map.of();

    public static TardisAppearance get(Identifier id) {
        if (id == null) return null;
        return APPEARANCES.get(id);
    }

    public static Map<Identifier, TardisAppearance> all() {
        return APPEARANCES;
    }

    static void setAll(Map<Identifier, TardisAppearance> map) {
        APPEARANCES = map;
    }
}