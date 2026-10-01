package doctor_m.tardis;

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
    private static final int DEPTH      = 9;   // 内门到后墙 9 格
    private static final int HEIGHT     = 5;   // 内部高度

    // 材质
    private static final BlockState FLOOR   = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    private static final BlockState WALL = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState CEILING = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState LIGHT   = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState CONSOLE = Blocks.LODESTONE.defaultBlockState();

    /**
     * 生成简易控制室。幂等。
     * <p>房间从 {@code interiorPos} 沿 {@code interiorFacing} 方向展开，
     * 内门正好嵌在前墙的门洞里。
     */
    public static void generate(ServerLevel level, TardisData data) {
        BlockPos doorPos = data.interiorPos();
        Direction forward = data.interiorFacing();
        Direction right = forward.getClockWise();

        // 幂等标记：门前方 1 格地板已经是 FLOOR，就认为房间已存在
        BlockPos sentinel = doorPos.relative(forward, 1).offset(0, -1, 0);
        if (level.getBlockState(sentinel).is(FLOOR.getBlock())) return;

        // 1) 主体：地板 / 墙 / 天花板 / 内部掏空
        for (int f = 0; f <= DEPTH + 1; f++) {
            for (int r = -HALF_WIDTH - 1; r <= HALF_WIDTH + 1; r++) {
                for (int y = -1; y <= HEIGHT; y++) {

                    // 门洞：前墙 (f=0) 正中 (r=0) 两层高
                    if (f == 0 && r == 0 && (y == 0 || y == 1)) continue;

                    BlockPos p = doorPos.relative(forward, f)
                            .relative(right, r)
                            .offset(0, y, 0);

                    boolean isFloor   = (y == -1);
                    boolean isCeiling = (y == HEIGHT);
                    boolean isWall    = (f == 0 || f == DEPTH + 1
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

        // 3) 占位控制台：房间正中
        BlockPos consolePos = doorPos.relative(forward, DEPTH / 2);
        level.setBlock(consolePos, CONSOLE, 3);
        level.setBlock(consolePos.above(), LIGHT, 3);
    }
}