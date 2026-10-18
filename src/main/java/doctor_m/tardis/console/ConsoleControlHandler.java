package doctor_m.tardis.console;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 控件交互的服务端处理。
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>从 {@code consolePos} 找到控制台 BE</li>
 *   <li>从 BE 拿 {@code tardisId} → {@link TardisData}</li>
 *   <li>从 BE 外观 + 骨骼名解析 {@link ControlConfig}</li>
 *   <li>经 {@link ControlType#behavior()} 取 {@link ControlBehavior}</li>
 *   <li>前置检查（{@code canInteract}）</li>
 *   <li>算新档位（读 {@code TardisData} 全局档位表）—— 无变化直接返回，
 *       不播动画不触发</li>
 *   <li>经 {@code TardisManager#setControlTier} 写全局档位并广播到该 TARDIS
 *       内所有同类型控件实体（每个实体刷新自己的动画对位字段）</li>
 *   <li>调 {@code behavior.onTierChanged}（带实体、操作者与点击类型）</li>
 * </ol>
 *
 * <h2>一份档位，全体控制台共用</h2>
 * 档位的权威在 {@code TardisData}（key = 控件类型 id），控件实体只是视图。
 * 任何一台控制台上的操作，其它台上的同类型控件会同步动到同一档。
 *
 * <h2>挡位规则</h2>
 * <ul>
 *   <li>右键：+1</li>
 *   <li>左键：-1</li>
 *   <li>潜行右键：拉到顶（{@code maxTier}）</li>
 *   <li>潜行左键：拉到底（0）</li>
 * </ul>
 * CLAMP 模式夹取，CYCLE 模式回绕（见 {@link TierMode#apply}）。
 * 到顶再右键 → 无变化 → 不播动画。
 *
 * <h2>脉冲式控件（坐标类）</h2>
 * 用 2 档 CYCLE 让<b>每一次点击</b>都产生档位变化（0↔1），从而每次点击都
 * 触发回调；方向由 {@link ClickType} 传给行为。
 */
public final class ConsoleControlHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/console");

    private ConsoleControlHandler() {}

    // =========================================================
    //                      主入口
    // =========================================================

    public static void handleClick(ServerPlayer player,
                                   ConsoleControlEntity entity,
                                   ClickType type) {

        if (!(player.level() instanceof ServerLevel level)) return;

        BlockPos consolePos = entity.getConsolePos();
        if (consolePos == null) return;

        // 1. 控制台 BE
        if (!(level.getBlockEntity(consolePos) instanceof TardisConsoleBlockEntity be)) {
            return;
        }

        // 2. TARDIS
        MinecraftServer server = level.getServer();
        UUID tardisId = be.getTardisId();
        if (server == null || tardisId == null) return;    // 装饰品
        TardisData data = TardisManager.get(server, tardisId);
        if (data == null) return;

        // 3. 控件配置
        TardisConsoleAppearance app = TardisConsoleRegistry.get(be.getAppearanceId());
        if (app == null) return;
        ControlConfig config = ConsoleBoneMatcher.resolve(entity.getBoneName(), app);
        if (config == null) return;

        // 4. 行为（类型与行为绑定在同一个 ControlType 上）
        ControlBehavior behavior = config.type().behavior();
        if (!behavior.canInteract(player, entity, data)) return;

        // 5. 计算新档位 —— 以 TARDIS 全局档位表为准，实体只是视图
        //    （多台控制台的同类控件共享同一档位，见 TardisData#controlTiers）
        String typeId = config.type().id();
        int oldTier = data.tier(typeId);
        int maxTier = config.tiers() - 1;
        int newTier = nextTier(oldTier, maxTier, config.tierMode(), type);

        // 6. 无变化 → 不播动画不触发（到顶再右键的行为）
        if (newTier == oldTier) return;

        // 7. 写全局档位 + 广播到该 TARDIS 内所有同类型控件实体
        //    （每个实体按自己的外观配置刷新 DATA_TIER / target_time，驱动动画）
        TardisManager.setControlTier(server, data, typeId, newTier);

        LOGGER.debug("[control] {} bone={} {}->{} by {}",
                player.getName().getString(), entity.getBoneName(),
                oldTier, newTier, type);

        // 8. 业务回调（player 用于动作栏等操作者反馈）
        behavior.onTierChanged(level, data, entity, player, type, oldTier, newTier);
    }

    // =========================================================
    //                      挡位运算
    // =========================================================

    /**
     * 计算下一个挡位。折算规则委托给 {@link TierMode#apply}。
     *
     * @param current 当前挡位
     * @param maxTier 最大挡位（{@code tiers - 1}）
     * @param mode    到顶后的行为（CLAMP / CYCLE）
     * @param type    点击类型
     */
    public static int nextTier(int current, int maxTier, TierMode mode, ClickType type) {
        if (maxTier <= 0) return 0;    // 单档控件恒为 0

        int target = switch (type) {
            case RIGHT       -> current + 1;
            case LEFT        -> current - 1;
            case SNEAK_RIGHT -> maxTier;
            case SNEAK_LEFT  -> 0;
        };
        return mode.apply(target, maxTier);
    }
}
