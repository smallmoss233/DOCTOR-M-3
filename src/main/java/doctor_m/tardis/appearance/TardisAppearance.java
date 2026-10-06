package doctor_m.tardis.appearance;

import net.minecraft.resources.Identifier;

public record TardisAppearance(
        Identifier id,
        String displayName,
        TardisAsset exterior,
        TardisAsset interior,
        boolean variant,
        String category
) {}