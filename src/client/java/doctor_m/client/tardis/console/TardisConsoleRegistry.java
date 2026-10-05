package doctor_m.client.tardis.console;

import net.minecraft.resources.Identifier;

import java.util.Map;

public final class TardisConsoleRegistry {

    private TardisConsoleRegistry() {}

    private static volatile Map<Identifier, TardisConsoleAppearance> ALL = Map.of();

    public static TardisConsoleAppearance get(Identifier id) {
        if (id == null) return null;
        return ALL.get(id);
    }

    public static Map<Identifier, TardisConsoleAppearance> all() {
        return ALL;
    }

    static void setAll(Map<Identifier, TardisConsoleAppearance> map) {
        ALL = map;
    }
}