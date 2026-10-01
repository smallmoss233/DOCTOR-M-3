package mosslib.dimension;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.UUID;

/**
 * 动态维度的模板定义。
 */
public final class DimensionTemplate {

    private DimensionTemplate() {}

    /** TARDIS 维度类型。 */
    public static final ResourceKey<DimensionType> TARDIS_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE,
                    Identifier.fromNamespaceAndPath("doctor_m", "tardis"));

    /** 从 UUID 派生维度 ID。 */
    public static Identifier dimensionIdFor(UUID id) {
        return Identifier.fromNamespaceAndPath("doctor_m", "tardis/" + id);
    }

    /** TARDIS 群系——空 spawners，不刷怪。 */
    public static final ResourceKey<Biome> TARDIS_BIOME =
            ResourceKey.create(Registries.BIOME,
                    Identifier.fromNamespaceAndPath("doctor_m", "tardis"));
}