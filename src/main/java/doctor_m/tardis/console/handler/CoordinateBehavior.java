package doctor_m.tardis.console.handler;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.console.ClickType;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/**
 * 目的地坐标控件（X / Y / Z 三个实例共用本类，仅轴向不同）。
 *
 * <p>每次点击把目的地沿本轴移动 {@link TardisData#coordStep()} 格：
 * 右键加、左键减（潜行变体同向，见 {@link ClickType#direction()}）。
 * 没有目的地时以当前外门位置为基准开始偏移。
 *
 * <h2>为什么是 2 档 CYCLE</h2>
 * 坐标控件是"脉冲式"的：每次点击都要触发一次，不能被"挡位没变就不动"
 * 的机制挡住。2 档 CYCLE（0↔1）保证每次点击都产生档位变化；动画上就是一个
 * 小拨动。单次增量大小由数值增量器（{@link IncrementBehavior}）控制。
 */
public final class CoordinateBehavior implements ControlBehavior {

    /** 坐标轴。 */
    public enum Axis {
        X { @Override public BlockPos move(BlockPos p, int d) { return p.offset(d, 0, 0); } },
        Y { @Override public BlockPos move(BlockPos p, int d) { return p.offset(0, d, 0); } },
        Z { @Override public BlockPos move(BlockPos p, int d) { return p.offset(0, 0, d); } };

        /** 沿本轴移动 {@code delta} 格（可为负）。 */
        public abstract BlockPos move(BlockPos pos, int delta);
    }

    private final Axis axis;

    public CoordinateBehavior(Axis axis) {
        this.axis = axis;
    }

    public Axis axis() { return axis; }

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              ClickType click, int oldTier, int newTier) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        // 没目的地 → 以当前位置为基准
        if (!data.hasDestination()) {
            TardisManager.setDestination(server, data, data.exteriorDim(), data.exteriorPos());
        }

        BlockPos next = axis.move(data.targetPos(), data.coordStep() * click.direction());

        // Y 轴夹取到目标维度的合法高度范围，避免把目的地设到虚空外
        if (axis == Axis.Y) {
            ServerLevel targetLevel = server.getLevel(data.targetDim());
            if (targetLevel != null) {
                int minY = targetLevel.getMinY();
                int maxY = targetLevel.getMinY() + targetLevel.getHeight() - 1;
                next = new BlockPos(next.getX(), Mth.clamp(next.getY(), minY, maxY), next.getZ());
            }
        }

        TardisManager.setDestination(server, data, data.targetDim(), next);
    }
}
