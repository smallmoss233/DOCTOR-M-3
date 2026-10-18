package doctor_m.tardis.appearance;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public record TardisAppearance(
        Identifier id,
        String displayName,
        TardisAsset exterior,
        TardisAsset interior,
        boolean variant,
        String category
) {
    /** 供 UI 渲染使用的本地化名称 */
    public Component getDisplayName() {
        return Component.translatableWithFallback(
                "doctor_m.appearance.name." + id.getNamespace() + "." + id.getPath(),
                displayName
        );
    }

    /** 供 UI 渲染使用的本地化分类名 */
    public Component getCategoryName() {
        if (category == null) {
            return Component.translatable("doctor_m.appearance.category.uncategorized");
        }
        return Component.translatableWithFallback("doctor_m.appearance.category." + category, category);
    }
}