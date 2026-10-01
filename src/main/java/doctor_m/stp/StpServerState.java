package doctor_m.stp;

import java.util.*;

/**
 * 服务端 STP 会话状态（纯内存）。
 * <p>记录"哪个玩家已对哪个 TARDIS 发起过预加载"，
 * 用于避免重复请求 / 重复释放。
 * <p>服务器重启 = 状态清空 = 下次靠近重新预加载，这是正确行为。
 */
public final class StpServerState {

    private StpServerState() {}

    private static final Map<UUID, Set<UUID>> PLAYER_TO_TARDIS = new HashMap<>();

    public static boolean addPreloaded(UUID playerId, UUID tardisId) {
        return PLAYER_TO_TARDIS.computeIfAbsent(playerId, k -> new HashSet<>()).add(tardisId);
    }

    public static boolean removePreloaded(UUID playerId, UUID tardisId) {
        Set<UUID> set = PLAYER_TO_TARDIS.get(playerId);
        if (set == null) return false;
        boolean removed = set.remove(tardisId);
        if (set.isEmpty()) PLAYER_TO_TARDIS.remove(playerId);
        return removed;
    }

    public static Set<UUID> getPreloaded(UUID playerId) {
        Set<UUID> set = PLAYER_TO_TARDIS.get(playerId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    /** 玩家断线时清理。 */
    public static void clearPlayer(UUID playerId) {
        PLAYER_TO_TARDIS.remove(playerId);
    }

    /** TARDIS 被删除时清理所有玩家对它的引用。 */
    public static void clearTardis(UUID tardisId) {
        for (Set<UUID> set : PLAYER_TO_TARDIS.values()) {
            set.remove(tardisId);
        }
        PLAYER_TO_TARDIS.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public static void clearAll() {
        PLAYER_TO_TARDIS.clear();
    }
}