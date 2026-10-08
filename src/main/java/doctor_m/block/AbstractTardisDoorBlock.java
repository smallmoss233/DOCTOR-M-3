package doctor_m.block;

import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

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
    //                      形状
    // ================================================================

    /**
     * 门的碰撞板在方块内的横向占位（像素）。
     *
     * <p>门宽 16 像素，两侧各留 {@code SIDE} 像素不参与碰撞 —— 这决定玩家能沿着
     * 门的边缘走到哪。取 3 时玩家（宽 0.6 格 ≈ 9.6 像素）可以贴着门框走过而不被卡住。
     */
    protected static final int SIDE = 3;

    /**
     * 门平面在方块内的像素位置（0..16），即碰撞板的中心。
     *
     * <p><b>这是碰撞形状与传送触发判定的共同锚点</b>：碰撞板以它为中心，
     * 穿越判定以它为零点。两者必须一致，否则会出现"还没走到门板就传送"
     * 或"穿模了才触发"的偏移。
     *
     * <h2>取值依据（实测，不是推导）</h2>
     * 用 {@code tools/door-sim/AlignCheck} 以真实解析器算出各模型在渲染空间里的
     * 包围盒中心（相对方块中心，单位格）：
     *
     * <pre>
     *   police_box   外门  X +0.005  Z +0.013
     *   blue_box     外门  X -0.002  Z +0.022
     *   x_police_box 外门  X +0.006  Z -0.002
     *   blue_box     内门  X +0.031  Z +0.594
     *   x_police_box 内门  X -0.006  Z +0.687
     * </pre>
     *
     * <p><b>内门的模型在 Z 上比外门偏了约 +0.6 格</b>（内门几何体的根骨骼带
     * {@code rotation: [0,180,0]}，建模原点与外门不同）。因此两者必须用
     * <b>同一个平面位置</b> 11（板 6..16，中心 0.6875）才能同时贴合各自的模型。
     *
     * <p>曾经的错误：给内门用了镜像的平面位置 5（板 0..10，中心 0.3125），
     * 结果碰撞板比模型多深入房间 0.375 格 —— 表现为"碰撞箱离模型更远、往房间内侧偏"。
     */
    protected final int doorPlanePixels() {
        return 11;
    }

    /**
     * 碰撞板的厚度（像素）。
     *
     * <p>这是"像不像一块门板"的关键。旧值是 10 像素（0.625 格），厚得像一堵墙，
     * 玩家会明显感觉自己撞在体积上而不是门上 —— 观感上就是"这不是门"。
     *
     * <p>取 2 像素（0.125 格）与现实中门板的厚度量级一致，且<t>必须让板跨越门平面</t>：
     * {@code entityInside} 只在玩家包围盒真正碰到碰撞板时才会被调用，
     * 板若整块位于平面一侧，触发就会整体偏到那一侧去。
     * 2 像素的板以平面 11 为中心 → 覆盖 10..12（z=0.6250..0.7500），
     * 门平面 0.6875 恰好落在其中央。
     */
    protected int collisionThickness() {
        return 2;
    }

    /**
     * 触发传送所需的"越过门平面"距离（格）。
     *
     * <p>0 = 玩家身体中心正好到达门平面时传送。这是手感的主要调节点：
     * <ul>
     *   <li>调大 → 更早触发，几乎看不到门（穿模最少，但"还没走过去"的感觉更强）；</li>
     *   <li>调小或为负 → 更晚触发，会看到自己半个身子插进门里。</li>
     * </ul>
     * 默认取 0（中心到达平面即传送），对应"走到门口就走过去了"的手感。
     */
    protected static final double CROSS_THRESHOLD = 0.0;

    /**
     * 构造本门型的碰撞形状：以门平面为中心、厚度
     * {@link #collisionThickness()} 的竖板，横向留出 {@link #SIDE}。
     *
     * <p>形状随 {@code FACING} 与 {@link #doorPlaneOnOuterSide()} 计算得出，
     * 不再每种朝向手写一份常量 —— 旧版那四份手写常量正是"内外门形状不一致"
     * 这类问题容易藏身的地方。
     */
    protected VoxelShape shapeFor(Direction facing) {
        int half = Math.max(1, collisionThickness() / 2);
        int center = doorPlanePixels();
        int lo = Math.max(0, center - half);
        int hi = Math.min(16, center + half);
        int min = SIDE;
        int max = 16 - SIDE;

        return switch (facing) {
            // 板在方块内 z=[lo,hi] 区间；随 FACING 旋转到对应朝向。
            case NORTH -> Block.box(min, 0, lo, max, 16, hi);
            case SOUTH -> Block.box(min, 0, 16 - hi, max, 16, 16 - lo);
            case EAST  -> Block.box(16 - hi, 0, min, 16 - lo, 16, max);
            case WEST  -> Block.box(lo, 0, min, hi, 16, max);
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

        // ★ 必须真正"走过门平面"才触发。
        //
        // 旧版用 dot < -0.3 判断"不是从背面撞进来的"，只要玩家身体与门的碰撞箱有
        // 任何重叠就触发 —— 而内门当时用的是基类那块贴边 2 像素的薄板，
        // 结果人在门内侧还没迈出门就传走了。
        //
        // 现在以门平面为零点判断玩家中心是否越过它。配合让碰撞板跨越门平面，
        // entityInside 恰好在玩家接近平面时被调用，于是触发时机与门板位置一致。
        if (!hasCrossedDoorPlane(state, pos, player)) return;

        handlePassThrough(player, (ServerLevel) level, pos);
    }

    /**
     * 玩家是否已经越过门平面。
     *
     * <p>门平面垂直于 {@code FACING}，位于方块内 {@link #doorPlanePixels()} 处。
     * 玩家在平面两侧的符号相反：从"门背后"走向"门正面"时，带符号距离由负转正。
     *
     * <p><b>平面在哪一侧由 {@link #doorPlaneOnOuterSide()} 决定</b>，
     * 与外门的几何镜像关系由此保持一致 —— 之前内外门共用同一个平面位置，
     * 导致其中一边的符号整体反号（走过去不传送、退回来反而传送）。
     *
     * <p>另外要求玩家在水平面上确实位于门洞范围内，避免沿着门侧面走过时被误判。
     */
    protected boolean hasCrossedDoorPlane(BlockState state, BlockPos pos, ServerPlayer player) {
        Direction facing = state.getValue(FACING);

        double relX = player.getX() - (pos.getX() + 0.5);
        double relZ = player.getZ() - (pos.getZ() + 0.5);

        // 沿 FACING 的带符号距离，减去"平面相对方块中心的偏移"（换算成格）。
        // 平面越靠近 FACING 那一侧，偏移越正，玩家要更靠外才算越过。
        double planeOffset = (doorPlanePixels() - 8) / 16.0;
        double along = relX * facing.getStepX() + relZ * facing.getStepZ() - planeOffset;

        if (along < CROSS_THRESHOLD) return false;

        // 横向限制：沿门的宽度方向不能跑出门框
        double lateral = Math.abs(relX * facing.getStepZ() - relZ * facing.getStepX());
        if (lateral > 0.5) return false;

        // 法向限制：不能离门太远（防止斜向擦过时误判）
        return along <= 0.5 + CROSS_THRESHOLD;
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

        // ★ 新增：飞行期间禁止开关门
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