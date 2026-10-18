package doctor_m.block;

import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * TARDIS 门（内门 / 外门）共用骨架。
 *
 * <h2>几何完全下放到子类</h2>
 * 内外门是两种完全不同的几何：外门是"面向玩家挖去 6 像素的凹坑"，内门是
 * "贴在方块背面的 2 像素薄板"。基类<b>不再</b>假设两者共用一个几何参数 ——
 * 旧版用一个全局的 {@code doorPlanePixels()} 同时服务两者，是"还没走到门板
 * 就被传送"的直接原因。
 *
 * <p>子类必须实现 {@link #doorPanelShape}（门板形状），可选实现
 * {@link #ledgeShape}（向外延伸的薄底座，只在下半生效）。碰撞、触发、
 * 轮廓全部以这两个形状为准。
 *
 * <h2>上下半</h2>
 * 门是两格高的双方块。门板两半都有，底座只有下半有 —— 否则上半会伸出一条
 * 悬空的横梁。{@link #doorPanelShape} 与 {@link #ledgeShape} 都接
 * {@link DoubleBlockHalf} 参数由子类自行决定。
 *
 * <h2>穿门判定</h2>
 * 玩家 AABB 与 {@link #doorPanelShape}（下半）实际重叠 → 视为已穿过。
 * 与门板位置严格同步，不会出现"提前传送"。
 */
public abstract class AbstractTardisDoorBlock extends Block implements EntityBlock {

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
    //                      形状（子类实现）
    // ================================================================

    /**
     * 门板形状：门关着时是碰撞主体，门开着时玩家能穿过。
     *
     * <p><b>触发判定用同一个形状</b>（只取下半）：玩家 AABB 与它重叠 →
     * 视为已穿过门。门板在哪里，触发点就在哪里 —— 不再用"沿 FACING 的
     * 带符号距离"这类公式，那个公式的符号方向按朝向翻转，正是旧版
     * "还没走到门板就传送"的直接原因。
     *
     * @param half 该方块是门的下半还是上半；子类可据此返回不同形状
     */
    protected abstract VoxelShape doorPanelShape(Direction facing, DoubleBlockHalf half);

    /**
     * 底座形状：无论开关门都存在的薄片，用于"玩家站在门框边缘不掉下去"。
     *
     * <p>默认没有。<b>只应在下半返回非空</b> —— 上半若也返回，会伸出一条
     * 悬空的横梁。
     *
     * <p>参考 AIT 的 {@code LEDGE_DOOM}：覆盖方块底面并向 {@code FACING}
     * 正向（玩家来的方向）延伸若干像素。
     */
    protected VoxelShape ledgeShape(Direction facing, DoubleBlockHalf half) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Direction facing = state.getValue(FACING);
        DoubleBlockHalf half = state.getValue(HALF);
        return Shapes.or(doorPanelShape(facing, half), ledgeShape(facing, half));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Direction facing = state.getValue(FACING);
        DoubleBlockHalf half = state.getValue(HALF);
        return Shapes.or(doorPanelShape(facing, half), ledgeShape(facing, half));
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

        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
        onPlacedByPlayer(level, pos, state, placer, stack);
    }

    /** 子类实现：玩家放置下半天之后调用。系统放置不会触发。 */
    protected void onPlacedByPlayer(Level level, BlockPos pos, BlockState state,
                                    @Nullable LivingEntity placer, ItemStack stack) {
    }

    // ================================================================
    //                      移除
    // ================================================================

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level,
                                               BlockPos pos, boolean movedByPiston) {
        DoubleBlockHalf half = state.getValue(HALF);

        if (half == DoubleBlockHalf.LOWER) {
            BlockPos above = pos.above();
            BlockState upper = level.getBlockState(above);
            if (upper.is(this)) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(2001, above, Block.getId(upper));
            }
            onLowerRemoved(level, pos);
        } else {
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

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier effectApplier, boolean isPrecise) {
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!player.isAlive() || player.isSpectator()) return;
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) return;
        if (!state.getValue(OPEN)) return;

        if (!hasCrossedDoorPlane(state, pos, player)) return;

        handlePassThrough(player, (ServerLevel) level, pos);
    }

    /**
     * 玩家是否已经穿过门板。
     *
     * <p>判定方式：玩家 AABB 与 {@link #doorPanelShape}（下半）的实际重叠。
     */
    protected boolean hasCrossedDoorPlane(BlockState state, BlockPos pos, ServerPlayer player) {
        VoxelShape panel = doorPanelShape(state.getValue(FACING), DoubleBlockHalf.LOWER);
        if (panel.isEmpty()) return false;

        AABB entityBox = player.getBoundingBox();
        AABB localBox = new AABB(
                entityBox.minX - pos.getX() - 0.05,
                entityBox.minY - pos.getY(),
                entityBox.minZ - pos.getZ() - 0.05,
                entityBox.maxX - pos.getX() + 0.05,
                entityBox.maxY - pos.getY(),
                entityBox.maxZ - pos.getZ() + 0.05);

        return panel.toAabbs().stream().anyMatch(aabb -> aabb.intersects(localBox));
    }

    // ================================================================
    //                      开关门
    // ================================================================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level,
                                               BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        MinecraftServer server = sl.getServer();
        if (server == null) return InteractionResult.FAIL;

        BlockPos base = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        TardisData data = findTardis(server, sl, base);
        if (data == null) return InteractionResult.PASS;

        if (!data.state().canOpenDoors()) {
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.translatable(
                        "message.doctor_m.tardis.door_locked"));
            }
            return InteractionResult.SUCCESS;
        }

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

    protected abstract void handlePassThrough(
            net.minecraft.server.level.ServerPlayer player, ServerLevel level, BlockPos pos);

    protected abstract void onLowerRemoved(ServerLevel level, BlockPos pos);

    protected abstract TardisData findTardis(MinecraftServer server, ServerLevel level, BlockPos pos);

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TardisDoorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state,
            net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (!level.isClientSide()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof TardisDoorBlockEntity door) {
                door.clientTick();
            }
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}