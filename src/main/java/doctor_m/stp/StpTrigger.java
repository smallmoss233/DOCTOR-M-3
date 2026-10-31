package doctor_m.stp;

import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * STP 触发器：按玩家与门的距离决定何时开始预加载。
 *
 * <h2>外门索引（新增）</h2>
 * 旧版对每个主世界玩家都遍历全部 {@code TardisData} 判断是否靠近外门，
 * 开销是 {@code O(玩家 × TARDIS 数量)}。现在按外门所在维度建一次索引，
 * 每 tick 只检查玩家所在维度的 TARDIS，开销降到
 * {@code O(TARDIS + 玩家 × 该维度 TARDIS 数量)}。
 */
public final class StpTrigger {

    private StpTrigger() {}

    /** 已预加载世界维度的玩家（防止重复触发）。 */
    private static final Set<UUID> WORLD_PRELOADED = new HashSet<>();

    /** 外门所在维度 → 该维度上所有有外门位置的 TARDIS。每 tick 重建一次。 */
    private static final Map<ResourceKey<Level>, List<TardisData>> EXTERIOR_INDEX = new HashMap<>();
    /** 索引上次重建的 tick。 */
    private static long indexTick = Long.MIN_VALUE;

    public static void tick(MinecraftServer server) {
        // 区块滴灌必须每 tick 跑，不能跟着 CHECK_INTERVAL 跳 ——
        // 它才是"把预加载摊到多帧"的执行者。
        StpChunkPump.tick(server);

        if (server.getTickCount() % StpConfig.CHECK_INTERVAL != 0) return;

        ensureExteriorIndex(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            BlockPos pos = player.blockPosition();
            UUID playerId = player.getUUID();
            boolean inTardis = TardisManager.isTardisDimension(level.dimension());

            if (inTardis) {
                // 玩家在 TARDIS 里：检测靠近内门 → 预加载主世界
                UUID tardisId = TardisManager.tardisIdFromDimension(level.dimension());
                if (tardisId == null) continue;
                TardisData data = TardisManager.get(server, tardisId);
                if (data == null) continue;

                if (isNearInterior(pos, data)) {
                    if (WORLD_PRELOADED.add(playerId)) {
                        StpManager.requestPreloadWorld(server, player,
                                data.exteriorDim(),
                                data.exteriorPos().getX(),
                                data.exteriorPos().getZ());
                    }
                } else {
                    if (WORLD_PRELOADED.remove(playerId)) {
                        // 玩家离开了内门附近：把没发完的队列丢掉，避免继续占用带宽。
                        StpChunkPump.clear(playerId);
                    }
                }
            } else {
                // 玩家在主世界：只检查本维度有外门的 TARDIS，不再全表扫描。
                if (WORLD_PRELOADED.remove(playerId)) {
                    StpChunkPump.clear(playerId);
                }

                Set<UUID> preloaded = StpServerState.getPreloaded(playerId);
                List<TardisData> candidates = EXTERIOR_INDEX.getOrDefault(level.dimension(), List.of());
                for (TardisData data : candidates) {
                    boolean nearby = isNearExterior(pos, data);
                    UUID tardisId = data.id();

                    if (nearby && !preloaded.contains(tardisId)) {
                        StpManager.requestPreload(server, player, data);
                    } else if (!nearby && preloaded.contains(tardisId)) {
                        StpManager.releasePreload(server, player, data);
                    }
                }
            }
        }
    }

    /** 按维度重建外门索引（同 tick 内只重建一次）。 */
    private static void ensureExteriorIndex(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick == indexTick) return;
        indexTick = tick;

        EXTERIOR_INDEX.clear();
        Collection<TardisData> all = TardisManager.getRegistry(server).all();
        for (TardisData data : all) {
            if (data.exteriorDim() == null || data.exteriorPos() == null) continue;
            EXTERIOR_INDEX.computeIfAbsent(data.exteriorDim(), k -> new ArrayList<>()).add(data);
        }
    }

    private static boolean isNearExterior(BlockPos pos, TardisData data) {
        return pos.distSqr(data.exteriorPos()) <= StpConfig.PRELOAD_DISTANCE_SQ;
    }

    private static boolean isNearInterior(BlockPos pos, TardisData data) {
        return pos.distSqr(data.interiorPos()) <= StpConfig.PRELOAD_DISTANCE_SQ;
    }
}
