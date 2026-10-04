package doctor_m.client.tardis.appearance;

import net.minecraft.resources.Identifier;

public record TardisAppearance(
        Identifier id,
        String displayName,
        TardisAsset exterior,
        TardisAsset interior
) {}