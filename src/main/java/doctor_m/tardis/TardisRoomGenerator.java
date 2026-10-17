package doctor_m.tardis;

import doctor_m.block.TardisInteriorDoorBlock;
import doctor_m.register.DMBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TARDIS 控制室生成器（临时版）。
 * <p>一个矩形房间 + 一个占位控制台，只为测试进出用。
 * <p>将来会被数据包结构替换，本类可直接删除。
 */
public final class TardisRoomGenerator {

    private TardisRoomGenerator() {}

    // 内部尺寸
    private static final int HALF_WIDTH = 4;   // 房间内部左右各 4 格，总宽 9
    private static final int DEPTH      = 9;   // 内部深度
    private static final int HEIGHT     = 5;   // 内部高度

    // 材质
    private static final BlockState FLOOR   = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    private static final BlockState WALL    = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState CEILING = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState LIGHT   = Blocks.SEA_LANTERN.defaultBlockState();

    // 占位控制台
    private static final BlockState CONSOLE = DMBlocks.TARDIS_CONSOLE.defaultBlockState();

    public static void generate(ServerLevel level, TardisData data) {
        BlockPos doorPos = data.interiorPos(); // 假设这是内墙 f=0 的位置
        Direction forward = data.interiorFacing();
        Direction right = forward.getClockWise();

        // 幂等标记：门前方地板已经是 FLOOR，就认为房间已存在
        BlockPos sentinel = doorPos.relative(forward, 1).offset(0, -1, 0);
        if (level.getBlockState(sentinel).is(FLOOR.getBlock())) return;

        // 1) 主体：完整实心墙（不再镂空门洞）
        for (int f = -1; f <= DEPTH + 1; f++) {
            for (int r = -HALF_WIDTH - 1; r <= HALF_WIDTH + 1; r++) {
                for (int y = -1; y <= HEIGHT; y++) {

                    BlockPos p = doorPos.relative(forward, f)
                            .relative(right, r)
                            .offset(0, y, 0);

                    boolean isFloor   = (y == -1);
                    boolean isCeiling = (y == HEIGHT);
                    boolean isWall    = (f == -1 || f == 0 || f == DEPTH + 1
                            || r == -HALF_WIDTH - 1 || r == HALF_WIDTH + 1);

                    BlockState s;
                    if (isFloor)        s = FLOOR;
                    else if (isCeiling) s = CEILING;
                    else if (isWall)    s = WALL;
                    else                s = Blocks.AIR.defaultBlockState();

                    level.setBlock(p, s, 3);
                }
            }
        }

        // 2) 天花板灯：3x3 网格
        for (int f = 2; f <= DEPTH - 1; f += 3) {
            for (int r = -HALF_WIDTH + 1; r <= HALF_WIDTH - 1; r += 3) {
                BlockPos p = doorPos.relative(forward, f)
                        .relative(right, r)
                        .offset(0, HEIGHT, 0);
                level.setBlock(p, LIGHT, 3);
            }
        }

        // 3) 放置内门：向房间内移动一格 (f=1)，紧贴着内墙
        BlockPos doorPlacementPos = doorPos.relative(forward, 1);
        BlockState doorState = DMBlocks.TARDIS_INTERIOR_DOOR.defaultBlockState();

        // 设置朝向：如果门模型有正面/反面，通常朝向房间内部即可
        if (doorState.hasProperty(TardisInteriorDoorBlock.FACING)) {
            doorState = doorState.setValue(TardisInteriorDoorBlock.FACING, forward);
        }

        // 如果门是 2 格高的（结合图1看），且需要放两次
        level.setBlock(doorPlacementPos, doorState, 3);

        // 注释：如果你的门方块是类似 vanilla 的双格高，可能需要再放上面那格：
        // level.setBlock(doorPlacementPos.above(), doorState, 3);

        // 4) 占位控制台：房间内部中心（并移除上方多余的海晶灯）
        int centerF = (1 + DEPTH) / 2; // DEPTH = 9 → 5
        BlockPos consolePos = doorPos.relative(forward, centerF);
        level.setBlock(consolePos, CONSOLE, 3);
        // 删除了原先加在这里的 level.setBlock(consolePos.above(), LIGHT, 3);
    }
}