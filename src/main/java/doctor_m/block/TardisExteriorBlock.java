package doctor_m.block;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class TardisExteriorBlock extends AbstractTardisDoorBlock {

    public TardisExteriorBlock(Properties props) {
        super(props);
    }

    // ================================================================
    //   外门碰撞箱：完整方块，但从正面（FACING 方向）凹进 6 像素
    // ================================================================

    /** 6 = 凹进去的像素数，玩家从正面能走进凹陷区触发传送。 */
    private static final int INDENT = 6;

    private static final VoxelShape EXT_NORTH = Block.box(0, 0, INDENT, 16, 16, 16);
    private static final VoxelShape EXT_SOUTH = Block.box(0, 0, 0, 16, 16, 16 - INDENT);
    private static final VoxelShape EXT_EAST  = Block.box(0, 0, 0, 16 - INDENT, 16, 16);
    private static final VoxelShape EXT_WEST  = Block.box(INDENT, 0, 0, 16, 16, 16);

    private static VoxelShape extShape(Direction facing) {
        return switch (facing) {
            case NORTH -> EXT_NORTH;
            case SOUTH -> EXT_SOUTH;
            case EAST  -> EXT_EAST;
            case WEST  -> EXT_WEST;
            default    -> Shapes.block();
        };
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return extShape(s.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return extShape(s.getValue(FACING));
    }

    // ================================================================
    //                      其余不变
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
        MinecraftServer server = level.getServer();
        if (server == null) return;
        TardisManager.onExteriorBroken(server, level.dimension(), pos);
    }
}