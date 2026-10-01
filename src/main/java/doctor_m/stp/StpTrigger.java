package doctor_m.stp;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class StpTrigger {

    private StpTrigger() {}

    /** 已预加载世界维度的玩家（防止重复触发）。 */
    private static final Set<UUID> WORLD_PRELOADED = new HashSet<>();

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % StpConfig.CHECK_INTERVAL != 0) return;

        Collection<TardisData> all = TardisManager.getRegistry(server).all();
        if (all.isEmpty()) return;

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
                    WORLD_PRELOADED.remove(playerId);
                }
            } else {
                // 玩家在主世界：检测靠近外门 → 预加载 TARDIS
                WORLD_PRELOADED.remove(playerId);

                Set<UUID> preloaded = StpServerState.getPreloaded(playerId);
                for (TardisData data : all) {
                    boolean nearby = isNearExterior(level, pos, data);
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

    private static boolean isNearExterior(ServerLevel level, BlockPos pos, TardisData data) {
        if (data.exteriorDim() == null || data.exteriorPos() == null) return false;
        if (!level.dimension().equals(data.exteriorDim())) return false;
        return pos.distSqr(data.exteriorPos()) <= StpConfig.PRELOAD_DISTANCE_SQ;
    }

    private static boolean isNearInterior(BlockPos pos, TardisData data) {
        return pos.distSqr(data.interiorPos()) <= StpConfig.PRELOAD_DISTANCE_SQ;
    }
}