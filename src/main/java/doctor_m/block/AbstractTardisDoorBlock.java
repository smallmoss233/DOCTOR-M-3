package doctor_m.block;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractTardisDoorBlock extends Block {

    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    /** 26.3：DirectionProperty 类被合并，直接用 HorizontalDirectionalBlock.FACING。 */
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    protected AbstractTardisDoorBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any()
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(HALF, FACING, OPEN);
    }

    // ================================================================
    //                      形状
    // ================================================================

    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SHAPE_EAST  = Block.box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape SHAPE_WEST  = Block.box(14, 0, 0, 16, 16, 16);

    protected static VoxelShape shapeFor(Direction facing) {
        return switch (facing) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST  -> SHAPE_EAST;
            case WEST  -> SHAPE_WEST;
            default    -> Shapes.block();
        };
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return shapeFor(s.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return shapeFor(s.getValue(FACING));
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) { return true; }

    @Override
    protected boolean isPathfindable(BlockState s, PathComputationType t) { return false; }

    // ================================================================
    //                      放置
    // ================================================================

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(OPEN, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide()) return;
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) return;

        // 补上半
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);

        // 通知子类
        onPlacedByPlayer(level, pos, state, placer, stack);
    }

    /**
     * 子类实现：玩家放置下半天之后调用。
     * <p>系统通过 {@code level.setBlock} 放门不会触发此方法。
     */
    protected void onPlacedByPlayer(Level level, BlockPos pos, BlockState state,
                                    @Nullable LivingEntity placer, ItemStack stack) {
        // 默认什么都不做
    }

    // ================================================================
    //                      移除（替代旧 onRemove）
    // ================================================================

    /**
     * 26.3 起 onRemove 被 affectNeighborsAfterRemoval 取代。
     * 该方法在方块被移除后调用，state 是被移除前那一刻的状态。
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level,
                                               BlockPos pos, boolean movedByPiston) {
        DoubleBlockHalf half = state.getValue(HALF);

        if (half == DoubleBlockHalf.LOWER) {
            // 破坏上半
            BlockPos above = pos.above();
            BlockState upper = level.getBlockState(above);
            if (upper.is(this)) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(2001, above, Block.getId(upper));
            }
            // 通知子类
            onLowerRemoved(level, pos);
        } else {
            // 上半被破坏 → 连带下半
            BlockPos below = pos.below();
            BlockState lower = level.getBlockState(below);
            if (lower.is(this)) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(2001, below, Block.getId(lower));
            }
        }
    }

    // ================================================================
    //                      穿门
    // ================================================================

    /** 26.3 签名：多了 InsideBlockEffectApplier 和 isPrecise。 */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier effectApplier, boolean isPrecise) {
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!player.isAlive() || player.isSpectator()) return;
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) return;
        if (!state.getValue(OPEN)) return;

        // ★ 用玩家相对门中心的位置判断：正面 / 侧面都允许，只有背面拒绝
        Direction facing = state.getValue(FACING);
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        double dot = dx * facing.getStepX() + dz * facing.getStepZ();

        // 阈值 -0.3：给侧面和边界一点宽容，只拒绝明确从背面撞的
        if (dot < -0.3) return;

        handlePassThrough(player, (ServerLevel) level, pos);
    }

    // ================================================================
    //                      开关门（走 TardisManager 联动内外门）
    // ================================================================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level,
                                               BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        MinecraftServer server = sl.getServer();
        if (server == null) return InteractionResult.FAIL;

        // ★ 归一到下半，跟 TardisData 里存的坐标对齐
        BlockPos base = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        TardisData data = findTardis(server, sl, base);
        if (data == null) return InteractionResult.PASS;

        boolean newOpen = !state.getValue(OPEN);
        TardisManager.setDoorOpen(server, data, newOpen);
        return InteractionResult.SUCCESS;
    }

    // ================================================================
    //                      旋转 / 镜像
    // ================================================================

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // ================================================================
    //                      子类实现
    // ================================================================

    /** 玩家撞到门（且门开着）时调用，负责传进 / 传出。 */
    protected abstract void handlePassThrough(
            net.minecraft.server.level.ServerPlayer player, ServerLevel level, BlockPos pos);

    /** 下半天被破坏时调用。 */
    protected abstract void onLowerRemoved(ServerLevel level, BlockPos pos);

    /**
     * 子类实现：判断这个位置的门属于哪个 TARDIS。
     * <ul>
     *   <li>{@link TardisExteriorBlock} → {@link TardisManager#findByExterior}</li>
     *   <li>{@link TardisInteriorDoorBlock} → {@link TardisManager#findByInterior}</li>
     * </ul>
     */
    protected abstract TardisData findTardis(MinecraftServer server, ServerLevel level, BlockPos pos);
}