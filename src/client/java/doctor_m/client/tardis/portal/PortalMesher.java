package doctor_m.client.tardis.portal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描 prepared level 中锚点周围半径内的方块，烘焙为 PortalMesh。
 * 用 ModelBlockRenderer.tesselateBlock，光照/AO/颜色由 level 自动提供。
 * 直接以 ClientLevel 作为 BlockAndTintGetter，方块坐标传绝对坐标。
 */
public final class PortalMesher {

    /** 扫描半径（方块）。门周围 2–3 个 section ≈ 32–48 格。 */
    private static final int SCAN_RADIUS = 24;

    private PortalMesher() {}

    public static PortalMesh build(ClientLevel level, BlockPos anchor) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        // AO 关闭（一期逐面平光）；第二参数沿用 SectionCompiler 的 true
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());

        PortalVertexCollector collector = new PortalVertexCollector();
        BlockQuadOutput output = (x, y, z, quad, instance) ->
                collector.putBlockBakedQuad(x, y, z, quad, instance);

        List<PortalQuad> quads = new ArrayList<>();
        BlockPos min = anchor.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS);
        BlockPos max = anchor.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            // 门方块本身由 TardisDoorRenderer 单独绘制，这里跳过（可选）
            BlockStateModel model = modelSet.get(state);
            try {
                renderer.tesselateBlock(output,
                        pos.getX(), pos.getY(), pos.getZ(),
                        level, pos, state, model, state.getSeed(pos));
            } catch (Exception e) {
                // 单个方块烘焙失败不影响整体；正式版建议记日志
            }
        }
        quads.addAll(collector.drainQuads());
        return new PortalMesh(quads);
    }
}