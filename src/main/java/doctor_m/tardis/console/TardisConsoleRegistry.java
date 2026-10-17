package doctor_m.tardis.console;

import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * 控制台外观注册表。
 *
 * <p>全部已注册的外观由客户端 Loader 在资源重载时整体替换。
 *
 * <p>{@code volatile} 是因为读在服务端/渲染线程、写在资源重载线程；
 * 整替换（而非 clear+put）对 volatile 字段是原子的，读端永远不会看到"半空"的中间状态。
 */
public final class TardisConsoleRegistry {

    private TardisConsoleRegistry() {}

    private static volatile Map<Identifier, TardisConsoleAppearance> ALL = Map.of();

    /** 按 id 查找；未注册或 id 为 null 返回 null。 */
    public static TardisConsoleAppearance get(Identifier id) {
        if (id == null) return null;
        return ALL.get(id);
    }

    public static Map<Identifier, TardisConsoleAppearance> all() {
        return ALL;
    }

    /** 替换整个注册表。由 {@code TardisConsoleLoader}（客户端）在资源重载时调用。 */
    public static void setAll(Map<Identifier, TardisConsoleAppearance> map) {
        ALL = map == null ? Map.of() : Map.copyOf(map);
    }
}
