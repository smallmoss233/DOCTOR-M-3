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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

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

        int minX = anchor.getX() - SCAN_RADIUS;
        int minY = anchor.getY() - SCAN_RADIUS;
        int minZ = anchor.getZ() - SCAN_RADIUS;
        int maxX = anchor.getX() + SCAN_RADIUS;
        int maxY = anchor.getY() + SCAN_RADIUS;
        int maxZ = anchor.getZ() + SCAN_RADIUS;

        // 先在 section 粒度上跳过全空气层：预构建维度里绝大多数空间是空的，
        // 逐方块调用 getBlockState 会把 49³ ≈ 11.7 万次查表全走一遍。
        int minSectionY = Math.max(level.getMinSectionY(), minY >> 4);
        int maxSectionY = Math.min(level.getMaxSectionY(), maxY >> 4);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                // 未加载的区块在 ClientLevel 里是空区块，不会触发生成。
                LevelChunk chunk = level.getChunk(cx, cz);

                int x0 = Math.max(cx << 4, minX);
                int x1 = Math.min((cx << 4) + 15, maxX);
                int z0 = Math.max(cz << 4, minZ);
                int z1 = Math.min((cz << 4) + 15, maxZ);

                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (section == null || section.hasOnlyAir()) continue;

                    int yBase = sy << 4;
                    int y0 = Math.max(yBase, minY);
                    int y1 = Math.min(yBase + 15, maxY);

                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                cursor.set(x, y, z);
                                BlockState state = level.getBlockState(cursor);
                                if (state.isAir()) continue;
                                if (state.getRenderShape() != RenderShape.MODEL) continue;

                                // 只有真正要烘焙的方块才需要不可变坐标
                                BlockPos pos = cursor.immutable();
                                BlockStateModel model = modelSet.get(state);
                                try {
                                    renderer.tesselateBlock(output,
                                            x, y, z, level, pos, state, model, state.getSeed(pos));
                                } catch (Exception e) {
                                    // 单个方块烘焙失败不影响整体；正式版建议记日志
                                }
                            }
                        }
                    }
                }
            }
        }

        quads.addAll(collector.drainQuads());
        return new PortalMesh(quads);
    }
}
