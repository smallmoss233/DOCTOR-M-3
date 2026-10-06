package doctor_m.tardis.appearance;

import net.minecraft.resources.Identifier;

import java.util.*;

public final class TardisAppearanceRegistry {

    private TardisAppearanceRegistry() {}

    private static volatile Map<Identifier, TardisAppearance> APPEARANCES = Map.of();

    /** UI 层用来表示未分类的显示名。不要写进 JSON——JSON 里 category 为空 = null = 未分类。 */
    public static final String UNCATEGORIZED = "未分类";

    /**
     * 分类的固定显示顺序。
     * 不在这个列表里的分类，按名字字母序排在后面。
     * 未分类（category == null）永远排最后。
     */
    private static final List<String> CATEGORY_ORDER = List.of(
            "警亭",
            "灯塔",
            "蛋糕"
    );

    public static TardisAppearance get(Identifier id) {
        if (id == null) return null;
        return APPEARANCES.get(id);
    }

    public static Map<Identifier, TardisAppearance> all() {
        return APPEARANCES;
    }

    public static void setAll(Map<Identifier, TardisAppearance> map) {
        APPEARANCES = map;
    }

    // ============================================================
    //                      分类查询
    // ============================================================

    /**
     * 按 {@link #CATEGORY_ORDER} 排序返回所有分类。
     * 未分类（null）排最后，UI 层显示用 {@link #UNCATEGORIZED}。
     */
    public static List<String> allCategories() {
        Set<String> present = new LinkedHashSet<>();
        for (TardisAppearance a : APPEARANCES.values()) {
            present.add(a.category());   // 可能是 null
        }

        List<String> result = new ArrayList<>();

        // 1) 硬编码顺序里的
        for (String c : CATEGORY_ORDER) {
            if (present.remove(c)) result.add(c);
        }

        // 2) 剩下的按字母序
        List<String> rest = new ArrayList<>(present);
        rest.remove(null);
        rest.sort(Comparator.naturalOrder());
        result.addAll(rest);

        // 3) 未分类最后
        if (present.contains(null)) result.add(null);

        return result;
    }

    /** 某分类下的所有外观，按 id 字母序。category 传 null = 未分类。 */
    public static List<TardisAppearance> byCategory(String category) {
        List<TardisAppearance> list = new ArrayList<>();
        for (TardisAppearance a : APPEARANCES.values()) {
            if (Objects.equals(a.category(), category)) list.add(a);
        }
        list.sort(Comparator.comparing(a -> a.id().toString()));
        return list;
    }
}