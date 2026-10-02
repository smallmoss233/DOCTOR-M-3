package doctor_m.client.tardis.appearance;

import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 每个模型 ID 一个唯一的 ExtraModelKey。 */
public final class TardisModelKeys {

    private TardisModelKeys() {}

    private static final Map<Identifier, ExtraModelKey<BlockStateModel>> CACHE = new ConcurrentHashMap<>();

    public static ExtraModelKey<BlockStateModel> of(Identifier modelId) {
        return CACHE.computeIfAbsent(modelId,
                id -> ExtraModelKey.create(id::toString));
    }
}