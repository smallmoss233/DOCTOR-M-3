package doctor_m.client.tardis.appearance;

import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;

/** 把一个模型文件 ID 包装成可作为额外模型注册的 UnbakedExtraModel。 */
public final class TardisExtraModel implements UnbakedExtraModel<BlockStateModel> {

    private final Identifier modelId;

    public TardisExtraModel(Identifier modelId) {
        this.modelId = modelId;
    }

    @Override
    public void resolveDependencies(ResolvableModel.Resolver resolver) {
        resolver.markDependency(modelId);
    }

    @Override
    public BlockStateModel bake(ModelBaker baker) {
        BlockStateModelPart part = new Variant(modelId).bake(baker);
        return new SingleVariant(part);
    }
}