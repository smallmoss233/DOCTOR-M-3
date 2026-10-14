package doctor_m.tardis.console;

import net.minecraft.resources.Identifier;

import java.util.Map;

public final class TardisConsoleRegistry {

    private TardisConsoleRegistry() {}

    /**
     * 全部已注册的外观。由客户端 Loader 在资源重载时替换。
     *
     * <p>{@code volatile} 是因为读在服务端/渲染线程、写在资源重载线程。
     */
    private static volatile Map<Identifier, TardisConsoleAppearance> ALL = Map.of();

    public static TardisConsoleAppearance get(Identifier id) {
        if (id == null) return null;
        return ALL.get(id);
    }

    public static Map<Identifier, TardisConsoleAppearance> all() {
        return ALL;
    }

    /**
     * 替换整个注册表。由 {@code TardisConsoleLoader}（客户端）调用。
     *
     * <p>整个替换而不是 clear+put —— 后者在不可变 map 上会抛异常，
     * 而且并发下读端可能看到"半空"的中间状态。整替换对 volatile 字段是原子的。
     */
    public static void setAll(Map<Identifier, TardisConsoleAppearance> map) {
        ALL = map == null ? Map.of() : Map.copyOf(map);
    }
}