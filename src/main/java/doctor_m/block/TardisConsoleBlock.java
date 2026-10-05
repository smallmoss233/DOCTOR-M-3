package doctor_m.block;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.TardisState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TardisConsoleBlock extends Block implements EntityBlock {

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    public TardisConsoleBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return Shapes.block();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) { return true; }

    // ----------------------------------------------------------------
    //   放置：在 TARDIS 维度内自动绑定 tardisId
    // ----------------------------------------------------------------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState()
                .setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;

        UUID id = TardisManager.tardisIdFromDimension(sl.dimension());
        if (id == null) return;  // 塔迪斯外 → 装饰品

        if (level.getBlockEntity(pos) instanceof TardisConsoleBlockEntity be) {
            be.setTardisId(id);
        }
    }

    // ----------------------------------------------------------------
    //   右键：起飞 / 潜行右键：降落
    // ----------------------------------------------------------------

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               net.minecraft.world.entity.player.Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        MinecraftServer server = sl.getServer();
        if (server == null) return InteractionResult.FAIL;

        if (!(level.getBlockEntity(pos) instanceof TardisConsoleBlockEntity be)) {
            return InteractionResult.PASS;
        }

        UUID tardisId = be.getTardisId();
        if (tardisId == null) return InteractionResult.PASS;  // 装饰品

        TardisData data = TardisManager.get(server, tardisId);
        if (data == null) return InteractionResult.PASS;

        TardisState s = data.state();

        // 潜行 → 降落
        if (player.isShiftKeyDown()) {
            if (s == TardisState.FLYING) {
                if (TardisManager.startLanding(server, data)) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        }

        // 普通右键 → 起飞
        if (s == TardisState.LANDED) {
            if (!data.hasDestination()) {
                player.sendSystemMessage(Component.translatable(
                        "message.doctor_m.tardis.no_destination"));
                return InteractionResult.SUCCESS;
            }
            if (TardisManager.startTakeoff(server, data)) {
                return InteractionResult.SUCCESS;
            }
        }

        // 起飞/降落过渡中
        if (s == TardisState.TAKEOFF || s == TardisState.LANDING) {
            player.sendSystemMessage(Component.translatable(
                    "message.doctor_m.tardis.busy"));
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    // ----------------------------------------------------------------
    //   移除
    // ----------------------------------------------------------------

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level,
                                               BlockPos pos, boolean movedByPiston) {
        // 目前什么都不做；Phase 3 里清理控件实体
    }

    // ----------------------------------------------------------------
    //   旋转 / 镜像
    // ----------------------------------------------------------------

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // ----------------------------------------------------------------
    //   BlockEntity
    // ----------------------------------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TardisConsoleBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}