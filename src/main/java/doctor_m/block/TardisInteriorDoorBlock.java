package doctor_m.block;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.UUID;

/**
 * TARDIS 内门。
 *
 * <p>碰撞就是一块 <b>2 像素厚的纯薄板</b>，贴在方块<b>背离玩家来向</b>的边缘
 * （即 FACING 反向边缘）。不加底座 —— 房间里是平地，玩家不会掉下去。
 */
public class TardisInteriorDoorBlock extends AbstractTardisDoorBlock {

    /** 门板两侧留空（像素）。玩家宽 0.6 格 ≈ 9.6 像素，留 3 像素可贴着门框通过。 */
    private static final int SIDE = 3;
    /** 门板厚度（像素）。 */
    private static final int PANEL = 2;

    public TardisInteriorDoorBlock(Properties props) {
        super(props);
    }

    // ================================================================
    //   门板：贴在方块背离玩家来向的边缘（FACING 反向）
    //   上下半形状相同
    // ================================================================

    @Override
    protected VoxelShape doorPanelShape(Direction facing, DoubleBlockHalf half) {
        return switch (facing) {
            case NORTH -> Block.box(0, 0, 16 - PANEL, 16, 16, 16);
            case SOUTH -> Block.box(0, 0, 0, 16, 16, PANEL);
            case EAST  -> Block.box(0, 0, 0, PANEL, 16, 16);
            case WEST  -> Block.box(16 - PANEL, 0, 0, 16, 16, 16);
            default    -> Shapes.empty();
        };
    }

    // ================================================================
    //   其余逻辑不变
    // ================================================================

    @Override
    protected TardisData findTardis(MinecraftServer server, ServerLevel level, BlockPos pos) {
        // findByInterior 只匹配真门位置，备用门返回 null → 不响应右键
        return TardisManager.findByInterior(server, level.dimension(), pos);
    }

    @Override
    protected void handlePassThrough(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        TardisData data = TardisManager.findByInterior(server, level.dimension(), pos);
        if (data == null) return;
        TardisManager.teleportOut(player, data);
    }

    @Override
    protected void onPlacedByPlayer(Level level, BlockPos pos, BlockState state,
                                    LivingEntity placer, ItemStack stack) {
        if (!(level instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;

        UUID id = TardisManager.tardisIdFromDimension(sl.dimension());
        if (id == null) {
            sl.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            sl.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
            if (placer instanceof ServerPlayer sp) {
                sp.sendOverlayMessage(
                        Component.translatable("block.doctor_m.tardis_interior_door.not_in_tardis"));
            }
            return;
        }

        TardisData data = TardisManager.get(server, id);
        if (data == null) return;

        TardisManager.addSpareDoor(server, data, pos);
    }

    @Override
    protected void onLowerRemoved(ServerLevel level, BlockPos pos) {
        UUID id = TardisManager.tardisIdFromDimension(level.dimension());
        if (id == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;

        TardisData data = TardisManager.get(server, id);
        if (data == null) return;

        if (pos.equals(data.interiorPos())) {
            TardisManager.promoteSpareDoor(level, data);
        } else {
            TardisManager.removeSpareDoor(server, data, pos);
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}