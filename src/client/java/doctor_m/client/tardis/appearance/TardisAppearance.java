package doctor_m.client.tardis.appearance;

import net.minecraft.resources.Identifier;

import java.util.List;

/** 单个外观的 4 个模型 ID。 */
public record TardisAppearance(
        Identifier id,
        String displayName,
        Identifier exteriorClosed,
        Identifier exteriorOpen,
        Identifier interiorClosed,
        Identifier interiorOpen
) {
    public List<Identifier> allModelIds() {
        return List.of(exteriorClosed, exteriorOpen, interiorClosed, interiorOpen);
    }
}