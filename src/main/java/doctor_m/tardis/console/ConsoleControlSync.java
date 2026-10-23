package doctor_m.tardis.console;

import doctor_m.network.SyncConsoleControlsPayload;
import doctor_m.tardis.console.control.ConsoleControlEntity;
import doctor_m.tardis.console.control.ControlConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 控件实体的对账（reconcile）：把"服务端现有实体"对齐到"客户端算出的控件列表"。
 *
 * <h2>为什么要对账而不是重建</h2>
 * 客户端每次朝向 / 外观变化都会重发一份 AABB 列表。如果每次都全删重建，
 * 实体会被反复 discard + spawn，客户端动画状态（挡位对位、播放进度）会丢失，
 * 表现为"转个方向控件就跳回 0 档"。
 *
 * <p>所以按<b>骨骼名</b>把旧实体从池里"认领"回来，只更新 AABB：
 * <ul>
 *   <li>池里找到同名 → 复用，仅刷新碰撞箱</li>
 *   <li>池里没有 → 新建</li>
 *   <li>最后池里剩下的 → 说明该控件已不存在，discard</li>
 * </ul>
 *
 * <h2>为什么 AABB 来自客户端</h2>
 * 几何数据（{@code .geo.json}）只在客户端资源包里，服务端拿不到，
 * 因此控件位置的唯一可靠来源是客户端（见 {@code TardisConsoleBlockEntity} 类文档）。
 */
public final class ConsoleControlSync {

    private ConsoleControlSync() {}

    /**
     * 把 {@code tracked} 对齐到 {@code entries}。
     *
     * @param level      服务端世界
     * @param consolePos 控制台方块位置
     * @param appearance 控制台外观（用于解析未在池中的骨骼的控件配置）
     * @param tracked    服务端当前登记的实体列表；<b>会被原地改写</b>
     * @param entries    客户端发来的控件列表
     */
    public static void reconcile(ServerLevel level,
                                 BlockPos consolePos,
                                 TardisConsoleAppearance appearance,
                                 List<ConsoleControlEntity> tracked,
                                 List<SyncConsoleControlsPayload.Entry> entries) {
        if (appearance == null) return;

        // ---- 1. 收集现有实体（用 Set，同名不互相覆盖） ----
        Set<ConsoleControlEntity> pool = new LinkedHashSet<>();
        for (ConsoleControlEntity c : tracked) {
            if (!c.isRemoved()) pool.add(c);
        }
        pool.addAll(level.getEntitiesOfClass(
                ConsoleControlEntity.class,
                new AABB(consolePos).inflate(8),
                e -> consolePos.equals(e.getConsolePos())));

        tracked.clear();

        // ---- 2. 按客户端列表消费 pool ----
        for (SyncConsoleControlsPayload.Entry e : entries) {
            String boneName = e.boneName();

            // 从 pool 里找第一个同名骨骼（消耗掉它）
            ConsoleControlEntity entity = null;
            Iterator<ConsoleControlEntity> it = pool.iterator();
            while (it.hasNext()) {
                ConsoleControlEntity c = it.next();
                if (boneName.equals(c.getBoneName())) {
                    entity = c;
                    it.remove();
                    break;
                }
            }

            if (entity == null) {
                // 新控件：需要能解析出配置才生成
                ControlConfig config = ConsoleBoneMatcher.resolve(boneName, appearance);
                if (config == null) continue;

                ConsoleControlLayout.ControlBounds bounds =
                        new ConsoleControlLayout.ControlBounds(boneName, config, e.box());
                entity = ConsoleControlEntity.spawn(level, consolePos, bounds, 0);
            } else {
                entity.applyBounds(e.box());
            }
            tracked.add(entity);
        }

        // ---- 3. 剩下的全部 discard（包括同名重复的旧实体） ----
        for (ConsoleControlEntity leftover : pool) {
            leftover.discard();
        }
    }
}
