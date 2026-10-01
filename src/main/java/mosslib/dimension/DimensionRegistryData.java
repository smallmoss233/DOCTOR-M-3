package mosslib.dimension;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动态维度清单。持久化记录所有"曾经创建过"的维度 key。
 * <p>作用：服务器启动时读回清单 → 逐个恢复维度实例，
 * 让游戏"承认"这些维度存在。
 * <p>卸载维度不会清除记录——下次进入时仍能恢复。
 * 只有显式"删除"命令才会从清单移除。
 */
public class DimensionRegistryData extends SavedData {

    /** 数据文件名。 */
    private static final Identifier DATA_ID =
            Identifier.fromNamespaceAndPath("doctor_m", "dynamic_dimensions");

    private final Set<Identifier> dimensionIds = new HashSet<>();

    // ================================================================
    //                      Codec / Type
    // ================================================================

    private static final Codec<DimensionRegistryData> CODEC =
            Identifier.CODEC.listOf().xmap(
                    list -> {
                        DimensionRegistryData data = new DimensionRegistryData();
                        data.dimensionIds.addAll(list);
                        return data;
                    },
                    data -> List.copyOf(data.dimensionIds)
            );

    public static final SavedDataType<DimensionRegistryData> TYPE =
            new SavedDataType<>(DATA_ID, DimensionRegistryData::new, CODEC, null);

    // ================================================================
    //                      操作
    // ================================================================

    public DimensionRegistryData() {}

    /** 记录一个维度 key。已存在则无操作。 */
    public void add(ResourceKey<Level> key) {
        if (dimensionIds.add(key.identifier())) {
            setDirty();
        }
    }

    /** 移除记录（删除维度时用）。 */
    public void remove(ResourceKey<Level> key) {
        if (dimensionIds.remove(key.identifier())) {
            setDirty();
        }
    }

    /** 返回所有已记录的维度 key。 */
    public Set<ResourceKey<Level>> all() {
        Set<ResourceKey<Level>> result = new HashSet<>();
        for (Identifier id : dimensionIds) {
            result.add(ResourceKey.create(Registries.DIMENSION, id));
        }
        return result;
    }

    public int size() {
        return dimensionIds.size();
    }
}