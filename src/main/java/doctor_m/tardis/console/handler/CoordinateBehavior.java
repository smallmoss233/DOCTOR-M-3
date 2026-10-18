package doctor_m.tardis.console.handler;

import doctor_m.tardis.TardisManager;
import doctor_m.tardis.console.ClickType;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlBehavior;
import doctor_m.tardis.data.TardisData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * 目的地坐标控件（X / Y / Z 三个实例共用本类，仅轴向不同）。
 *
 * <p>每次点击把目的地沿本轴移动 {@link TardisData#coordStep()} 格：
 * 右键加、左键减（潜行变体同向，见 {@link ClickType#direction()}）。
 * 没有目的地时以当前外门位置为基准开始偏移。
 *
 * <h2>动作栏反馈</h2>
 * 每次点击后向<b>操作者</b>的动作栏发送完整目的地坐标（X Y Z），被修改的轴
 * 金色高亮并标注实际变化量，其余轴灰色，例如：
 * <pre>{@code X +100 → 1,234   Y 64   Z -7,891}</pre>
 * 变化量取夹取后的<b>实际值</b>（Y 顶到世界边界时显示真实生效的位移）。
 * 只发操作者本人——其它控制台/其他玩家不弹。
 *
 * <h2>为什么是 2 档 CYCLE</h2>
 * 坐标控件是"脉冲式"的：每次点击都要触发一次，不能被"挡位没变就不动"
 * 的机制挡住。2 档 CYCLE（0↔1）保证每次点击都产生档位变化；动画上就是一个
 * 小拨动。单次增量大小由数值增量器（{@link IncrementBehavior}）控制。
 */
public final class CoordinateBehavior implements ControlBehavior {

    /** 坐标轴。 */
    public enum Axis {
        X { @Override public BlockPos move(BlockPos p, int d) { return p.offset(d, 0, 0); }
            @Override public int coordOf(BlockPos p) { return p.getX(); } },
        Y { @Override public BlockPos move(BlockPos p, int d) { return p.offset(0, d, 0); }
            @Override public int coordOf(BlockPos p) { return p.getY(); } },
        Z { @Override public BlockPos move(BlockPos p, int d) { return p.offset(0, 0, d); }
            @Override public int coordOf(BlockPos p) { return p.getZ(); } };

        /** 沿本轴移动 {@code delta} 格（可为负）。 */
        public abstract BlockPos move(BlockPos pos, int delta);

        /** 读本轴的坐标值。 */
        public abstract int coordOf(BlockPos pos);
    }

    private final Axis axis;

    public CoordinateBehavior(Axis axis) {
        this.axis = axis;
    }

    public Axis axis() { return axis; }

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              @Nullable ServerPlayer player,
                              ClickType click, int oldTier, int newTier) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        // 没目的地 → 以当前位置为基准
        if (!data.hasDestination()) {
            TardisManager.setDestination(server, data, data.exteriorDim(), data.exteriorPos());
        }

        BlockPos prev = data.targetPos();
        BlockPos next = axis.move(prev, data.coordStep() * click.direction());

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

        if (player != null) {
            int actualDelta = axis.coordOf(next) - axis.coordOf(prev);
            MutableComponent msg = Component.empty();
            for (Axis a : Axis.values()) {
                if (!msg.getSiblings().isEmpty()) {
                    msg.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY));
                }
                if (a == axis) {
                    msg.append(Component.literal(
                                    a + " " + String.format("%+d", actualDelta)
                                            + " → " + String.format("%,d", a.coordOf(next)))
                            .withStyle(ChatFormatting.GOLD));
                } else {
                    msg.append(Component.literal(
                                    a + " " + String.format("%,d", a.coordOf(next)))
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            player.connection.send(new ClientboundSetActionBarTextPacket(msg));
        }
    }
}