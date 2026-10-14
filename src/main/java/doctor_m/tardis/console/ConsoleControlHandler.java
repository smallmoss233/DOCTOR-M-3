package doctor_m.tardis.console;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
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
 *   <li>找到 {@link ControlHandler}</li>
 *   <li>前置检查（{@code canInteract}）</li>
 *   <li>算新档位 —— 无变化直接返回，不播动画不触发</li>
 *   <li>写 {@code tier} + {@code targetTime} 到实体的同步字段</li>
 *   <li>调 {@code handler.onTierChanged}</li>
 * </ol>
 *
 * <h2>挡位规则</h2>
 * <ul>
 *   <li>右键：+1</li>
 *   <li>左键：-1</li>
 *   <li>潜行右键：拉到顶（{@code maxTier}）</li>
 *   <li>潜行左键：拉到底（0）</li>
 * </ul>
 * CLAMP 模式夹取，CYCLE 模式回绕。到顶再右键 → 无变化 → 不播动画。
 */
public final class ConsoleControlHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("doctor_m/console");

    private ConsoleControlHandler() {}

    /** 交互类型。 */
    public enum ClickType {
        RIGHT,          // 右键：挡位 +1
        SNEAK_RIGHT,    // 潜行右键：直接拉到最大
        LEFT,           // 左键：挡位 -1
        SNEAK_LEFT      // 潜行左键：直接拉到最小
    }

    // =========================================================
    //                      主入口
    // =========================================================

    public static void handleClick(ServerPlayer player,
                                   ConsoleControlEntity entity,
                                   ClickType type) {

        if (!(player.level() instanceof ServerLevel level)) return;

        BlockPos consolePos = entity.getConsolePos();
        if (consolePos == null) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        // 1. 控制台 BE
        if (!(level.getBlockEntity(consolePos) instanceof TardisConsoleBlockEntity be)) {
            return;
        }

        // 2. TARDIS
        UUID tardisId = be.getTardisId();
        if (tardisId == null) return;    // 装饰品
        TardisData data = TardisManager.get(server, tardisId);
        if (data == null) return;

        // 3. 控件配置
        TardisConsoleAppearance app = TardisConsoleRegistry.get(be.getAppearanceId());
        if (app == null) return;
        ControlConfig config = ConsoleBoneMatcher.resolve(entity.getBoneName(), app);
        if (config == null) return;

        // 4. 处理器
        ControlHandler handler = ControlHandlers.get(config.type().id());
        if (handler == null) return;

        // 5. 前置检查
        if (!handler.canInteract(player, entity, data)) return;

        // 6. 计算新档位
        int oldTier = entity.getTier();
        int maxTier = config.tiers() - 1;
        int newTier = nextTier(oldTier, maxTier, config.tierMode(), type);

        // 7. 无变化 → 不播动画不触发（到顶再右键的行为）
        if (newTier == oldTier) return;

        // 8. 写档位 + 目标时间（会同步到客户端，驱动动画）
        entity.setTier(newTier);
        entity.setTargetTime(config.timeForTier(newTier));

        LOGGER.debug("[control] {} bone={} {}->{} by {}",
                player.getName().getString(), entity.getBoneName(),
                oldTier, newTier, type);

        // 9. 业务回调
        handler.onTierChanged(level, data, entity, oldTier, newTier);
    }

    // =========================================================
    //                      挡位运算
    // =========================================================

    /**
     * 计算下一个挡位。
     *
     * @param current 当前挡位
     * @param maxTier 最大挡位（{@code tiers - 1}）
     * @param mode    到顶后的行为（CLAMP / CYCLE）
     * @param type    点击类型
     */
    public static int nextTier(int current, int maxTier, TierMode mode, ClickType type) {
        if (maxTier <= 0) return 0;    // 单档控件恒为 0

        int target;
        switch (type) {
            case RIGHT       -> target = current + 1;
            case LEFT        -> target = current - 1;
            case SNEAK_RIGHT -> target = maxTier;
            case SNEAK_LEFT  -> target = 0;
            default          -> target = current;
        }

        if (mode == TierMode.CLAMP) {
            return Math.max(0, Math.min(target, maxTier));
        } else {
            // CYCLE：越界回绕
            int range = maxTier + 1;
            return ((target % range) + range) % range;
        }
    }
}