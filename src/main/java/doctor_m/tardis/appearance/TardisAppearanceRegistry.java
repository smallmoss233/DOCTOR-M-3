package doctor_m.tardis.appearance;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.stream.Collectors;

public final class TardisAppearanceRegistry {

    private TardisAppearanceRegistry() {}

    private static volatile Map<Identifier, TardisAppearance> APPEARANCES = Map.of();
    private static volatile List<String> CACHED_CATEGORIES = List.of();

    public static TardisAppearance get(Identifier id) {
        if (id == null) return null;
        return APPEARANCES.get(id);
    }

    public static Map<Identifier, TardisAppearance> all() {
        return APPEARANCES;
    }

    public static synchronized void setAll(Map<Identifier, TardisAppearance> map) {
        APPEARANCES = map;
        CACHED_CATEGORIES = computeAllCategories(map);
    }

    public static List<String> allCategories() {
        return CACHED_CATEGORIES;
    }

    /**
     * 计算所有存在的分类，并自动排序。
     */
    private static List<String> computeAllCategories(Map<Identifier, TardisAppearance> map) {
        Set<String> present = new HashSet<>();
        for (TardisAppearance a : map.values()) {
            if (a.category() != null) {
                present.add(extractCategoryName(a.category()));
            }
        }

        List<String> sortedList = present.stream()
                .sorted((c1, c2) -> {
                    int p1 = getCategoryWeight(c1);
                    int p2 = getCategoryWeight(c2);
                    if (p1 != p2) return Integer.compare(p1, p2);
                    return c1.compareTo(c2);
                })
                .collect(Collectors.toList());

        boolean hasNull = map.values().stream().anyMatch(a -> a.category() == null);
        if (hasNull) {
            sortedList.add(null);
        }

        return Collections.unmodifiableList(sortedList);
    }

    /** 提取权重： "01_police_box" -> 1, "警亭" -> 999 */
    private static int getCategoryWeight(String category) {
        if (category == null || category.isEmpty()) return 999;
        if (category.matches("^\\d+_.*")) {
            try {
                return Integer.parseInt(category.substring(0, category.indexOf('_')));
            } catch (NumberFormatException ignored) {}
        }
        return 999;
    }

    /**
     * 解析真实分类名（去掉数字前缀，去掉 :leader 等后缀）。
     * "01_警亭:leader" -> "警亭"
     */
    public static String extractCategoryName(String rawCategory) {
        if (rawCategory == null) return null;
        String name = rawCategory;
        // 去掉特殊标记（冒号后面的内容）
        int colonIndex = name.indexOf(':');
        if (colonIndex != -1) {
            name = name.substring(0, colonIndex);
        }
        // 去掉数字前缀
        if (name.matches("^\\d+_.*")) {
            name = name.substring(name.indexOf('_') + 1);
        }
        return name;
    }

    /**
     * 获取 UI 渲染用的文本（自动本地化）。
     */
    public static Component getCategoryComponent(String rawCategory) {
        if (rawCategory == null) {
            return Component.translatable("doctor_m.appearance.category.uncategorized");
        }
        String cleanId = extractCategoryName(rawCategory);
        // 直接使用 translatableWithFallback，如果 lang 文件里没配，就显示 cleanId
        return Component.translatableWithFallback(
                "doctor_m.appearance.category." + cleanId,
                cleanId
        );
    }

    /**
     * 获取某分类下的所有外观。
     * 规则：组长带 :leader 标记的优先排前面，多个组长按 ID 的 A-Z / 0-9 排序。
     */
    public static List<TardisAppearance> byCategory(String targetCleanCategory) {
        List<TardisAppearance> list = new ArrayList<>();
        for (TardisAppearance a : APPEARANCES.values()) {
            if (a.category() != null && Objects.equals(extractCategoryName(a.category()), targetCleanCategory)) {
                list.add(a);
            } else if (a.category() == null && targetCleanCategory == null) {
                list.add(a);
            }
        }

        // 排序规则
        list.sort((a1, a2) -> {
            boolean isLeader1 = isLeader(a1.category());
            boolean isLeader2 = isLeader(a2.category());

            // 1. 组长优先
            if (isLeader1 && !isLeader2) return -1;
            if (!isLeader1 && isLeader2) return 1;

            // 2. 如果都是组长，或者都是普通成员，按 ID 字母序排
            return a1.id().toString().compareTo(a2.id().toString());
        });

        return list;
    }

    /** 判断该外观是否是组长 */
    public static boolean isLeader(String rawCategory) {
        if (rawCategory == null) return false;
        return rawCategory.contains(":leader");
    }
}