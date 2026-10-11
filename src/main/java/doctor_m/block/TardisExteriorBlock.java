package doctor_m.block;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * TARDIS 外门。
 *
 * <p>碰撞是"面向 FACING 正向挖去 {@code INDENT} 像素的凹坑" —— 玩家会走到
 * 凹坑里触发传送，观感上是一块门洞而不是薄板。
 *
 * <p>底座（{@link #ledgeShape}）覆盖方块底面并向 FACING 正向延伸 3.5 像素，
 * 让玩家站在门框边缘潜行时不会掉下去。**只在下半生成**。
 */
public class TardisExteriorBlock extends AbstractTardisDoorBlock {

    /** 凹坑深度（像素）。 */
    private static final int INDENT = 6;
    /** 底座厚度（像素）。与 AIT 的 LEDGE_DOOM 一致。 */
    private static final int LEDGE_THICKNESS = 1;
    /** 底座向 FACING 正向延伸（像素）。与 AIT 的 LEDGE_DOOM 一致。 */
    private static final double LEDGE_OVERHANG = 3.5;

    public TardisExteriorBlock(Properties props) {
        super(props);
    }

    // ================================================================
    //   门板：面向 FACING 正向一侧挖去 INDENT 像素的凹坑
    //   上下半形状相同
    // ================================================================

    @Override
    protected VoxelShape doorPanelShape(Direction facing, DoubleBlockHalf half) {
        return switch (facing) {
            case NORTH -> Block.box(0, 0, INDENT, 16, 16, 16);
            case SOUTH -> Block.box(0, 0, 0, 16, 16, 16 - INDENT);
            case EAST  -> Block.box(0, 0, 0, 16 - INDENT, 16, 16);
            case WEST  -> Block.box(INDENT, 0, 0, 16, 16, 16);
            default    -> Shapes.block();
        };
    }

    // ================================================================
    //   底座：完整方块底面 + 向 FACING 正向延伸 3.5 像素
    //   只在下半生成
    // ================================================================

    @Override
    protected VoxelShape ledgeShape(Direction facing, DoubleBlockHalf half) {
        if (half != DoubleBlockHalf.LOWER) return Shapes.empty();
        return switch (facing) {
            case NORTH -> Block.box(0, 0, -LEDGE_OVERHANG, 16, LEDGE_THICKNESS, 16);
            case SOUTH -> Block.box(0, 0, 0, 16, LEDGE_THICKNESS, 16 + LEDGE_OVERHANG);
            case EAST  -> Block.box(0, 0, 0, 16 + LEDGE_OVERHANG, LEDGE_THICKNESS, 16);
            case WEST  -> Block.box(-LEDGE_OVERHANG, 0, 0, 16, LEDGE_THICKNESS, 16);
            default    -> Shapes.empty();
        };
    }

    // ================================================================
    //   其余逻辑不变
    // ================================================================

    @Override
    protected TardisData findTardis(MinecraftServer server, ServerLevel level, BlockPos pos) {
        return TardisManager.findByExterior(server, level.dimension(), pos);
    }

    @Override
    protected void handlePassThrough(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        TardisData data = TardisManager.findByExterior(server, level.dimension(), pos);
        if (data == null) return;
        TardisManager.teleportInto(player, data);
    }

    @Override
    protected void onLowerRemoved(ServerLevel level, BlockPos pos) {
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}