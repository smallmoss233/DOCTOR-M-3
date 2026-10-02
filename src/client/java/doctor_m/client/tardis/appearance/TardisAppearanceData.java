package doctor_m.client.tardis.appearance;

import net.minecraft.resources.Identifier;

import java.util.Map;

/** 从资源包读到的所有外观。 */
public record TardisAppearanceData(Map<Identifier, TardisAppearance> appearances) {
    public static final TardisAppearanceData EMPTY =
            new TardisAppearanceData(Map.of());
}