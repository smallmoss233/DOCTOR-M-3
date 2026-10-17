package doctor_m.block;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 控制台方块。
 *
 * <h2>交互职责</h2>
 * 方块本身<b>不响应任何右键/攻击</b>（不复写 {@code useWithoutItem}，
 * 默认 PASS）。起飞 / 降落全部经由控件实体驱动：
 * 玩家操作 {@code ConsoleControlEntity} → {@code ConsoleControlHandler}
 * → {@code ControlBehavior}（如油门控件的起飞/降落逻辑）。
 *
 * <h2>放置：在 TARDIS 维度内自动绑定 tardisId</h2>
 */
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
    //   放置
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
    //   右键：无行为
    // ----------------------------------------------------------------
    //  起飞 / 降落改由控件实体驱动（油门等控件 + ControlBehavior），
    //  方块本体不处理交互。不复写 useWithoutItem —— 默认即 PASS。

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

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return (lvl, pos, st, be) -> {
                if (be instanceof TardisConsoleBlockEntity console) {
                    console.clientTick();
                }
            };
        } else {
            return (lvl, pos, st, be) -> {
                if (be instanceof TardisConsoleBlockEntity console) {
                    console.serverTick();
                }
            };
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}