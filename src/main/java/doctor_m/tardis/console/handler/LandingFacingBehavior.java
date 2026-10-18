package doctor_m.tardis.console.handler;

import doctor_m.tardis.console.ClickType;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlBehavior;
import doctor_m.tardis.console.ControlTypes;
import doctor_m.tardis.data.TardisData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 降落朝向罗盘控件：控制塔迪斯外壳<b>下次降落时</b>的朝向。
 *
 * <h2>档位 = 朝向</h2>
 * 4 档 {@code CYCLE}（见 {@link ControlTypes#LANDING_FACING}），档位 0..3 按
 * {@link #ORDER} 顺时针映射 北 → 东 → 南 → 西。右键顺时针转一档、左键逆时针，
 * 潜行右键直接拉到西、潜行左键拉回北（见 {@code ConsoleControlHandler#nextTier}）。
 * 档位存在 {@code TardisData#controlTiers}（随存档持久化、多台控制台共享，
 * 关服重开不丢），实体只是视图——所以本类几乎没有状态。
 *
 * <h2>什么时候生效</h2>
 * 档位只影响<b>降落瞬间</b>：{@code TardisManager#applyDestination} 在落地
 * 结算时读 {@link #targetFacing(TardisData)}，把外壳按该朝向放置，
 * 落地后它就成为"当前朝向"（{@code TardisData#exteriorFacing}）。
 * 起飞前、飞行中都可以拨——飞行途中临时改主意也来得及；
 * 拆门 / 淡入淡出期间拨动也无害（结算只在落地 tick 读一次）。
 *
 * <h2>动作栏反馈</h2>
 * 每次点击向<b>操作者</b>发动作栏：目标朝向（金色）+ 当前朝向（灰色），
 * 例如 {@code 目标 东  当前 北}。与坐标控件同一套反馈风格
 * （{@code ClientboundSetActionBarTextPacket}，26.3 的
 * {@code displayClientMessage(comp, true)} 已不可用），只发操作者本人。
 * 文案走翻译键 {@code doctor_m.control.facing.*}，键缺失时回退中文
 * （{@code translatableWithFallback}），方向名走
 * {@code doctor_m.direction.<name>}，同理回退。
 */
public final class LandingFacingBehavior implements ControlBehavior {

    /** 档位 0..3 的朝向顺序：北 → 东 → 南 → 西（顺时针）。 */
    public static final Direction[] ORDER = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    public LandingFacingBehavior() {}

    /** 档位 → 朝向。越界按 4 取模（防御性，正常档位表不会越界）。 */
    public static Direction facingOf(int tier) {
        return ORDER[Math.floorMod(tier, ORDER.length)];
    }

    /** 朝向 → 档位；未在表内（理论上不可能）回退 0（北）。 */
    public static int tierOf(Direction facing) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == facing) return i;
        }
        return 0;
    }

    /** 当前设定值：下次降落时外壳将采用的朝向（实时从档位表换算）。 */
    public static Direction targetFacing(TardisData data) {
        return facingOf(data.tier(ControlTypes.LANDING_FACING.id()));
    }

    // =========================================================
    //                      交互反馈
    // =========================================================

    @Override
    public void onTierChanged(ServerLevel level, TardisData data, ConsoleControlEntity entity,
                              @Nullable ServerPlayer player,
                              ClickType click, int oldTier, int newTier) {
        if (player == null) return;

        Direction target = facingOf(newTier);
        Direction current = data.exteriorFacing();

        Component msg = Component.empty()
                .append(Component.translatableWithFallback(
                                "doctor_m.control.facing.target", "目标 %s", name(target))
                        .withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatableWithFallback(
                                "doctor_m.control.facing.current", "当前 %s", name(current))
                        .withStyle(ChatFormatting.GRAY));
        player.connection.send(new ClientboundSetActionBarTextPacket(msg));
    }

    /** 方向的显示名：翻译键 {@code doctor_m.direction.<name>}，缺失回退中文单字。 */
    private static Component name(Direction d) {
        String fallback = switch (d) {
            case NORTH -> "北";
            case EAST  -> "东";
            case SOUTH -> "南";
            case WEST  -> "西";
            default    -> d.getName();
        };
        return Component.translatableWithFallback("doctor_m.direction." + d.getName(), fallback);
    }
}