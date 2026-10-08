package doctor_m.stp;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 区块预发送的滴灌泵。
 *
 * <h2>为什么需要它</h2>
 * 传送卡顿的<b>主要来源不是传送本身，而是传送之后才到的地形</b>。
 * 玩家跨过门的那一刻，客户端需要的是一整片已经建好的地形；如果此时才开始下载，
 * 玩家就会看到空洞、卡顿、加载界面。
 *
 * <p>但把几百个区块在同一个 tick 里一次性发出去同样是灾难：网络突发，加上客户端
 * 单帧内解析几百个区块包，直接把帧率打下去。两头都会卡。
 *
 * <p>所以做法是：玩家还在门的这一边（离门还有二十几格、正在往门走）时就把目的地的
 * 区块<b>排进队列</b>，然后每个 tick 只发固定数量 —— 走过去的这几秒钟足够把
 * 几百个区块平稳铺完，全程没有突发。
 *
 * <h2>为什么要能整体丢弃</h2>
 * 玩家可能在铺完之前转身走开、掉线、或已经穿门走了。队列必须能随时清空，
 * 玩家离线时也要清理，否则会一直对着一个不存在的连接发包。
 */
public final class StpChunkPump {

    private StpChunkPump() {}

    /** 每个玩家待发的区块队列。 */
    private static final Map<UUID, Deque<ChunkPos>> QUEUES = new HashMap<>();
    /** 每个队列对应的目标维度，防止玩家换维度后继续按旧维度发。 */
    private static final Map<UUID, ServerLevel> TARGETS = new HashMap<>();
    /** 已排队过的区块，避免玩家在门前反复走动时重复排队。 */
    private static final Map<UUID, Set<Long>> MARKED = new HashMap<>();

    /**
     * 把一个半径内的区块排进该玩家的发送队列（不立即发送）。
     *
     * <p>从内圈到外圈依次排队：玩家最可能先看到的区块先发出去。
     * 已经排过的区块会被跳过，因此玩家在门前反复走动不会让队列膨胀。
     */
    public static void enqueueRadius(ServerPlayer player, ServerLevel level,
                                     int centerX, int centerZ, int radius) {
        UUID id = player.getUUID();
        Deque<ChunkPos> queue = QUEUES.computeIfAbsent(id, k -> new ArrayDeque<>());

        // 目标维度变了（例如从 TARDIS 换成主世界）：旧队列整体作废
        ServerLevel previous = TARGETS.get(id);
        if (previous != null && previous != level) {
            queue.clear();
            MARKED.remove(id);
        }
        TARGETS.put(id, level);

        int cx = centerX >> 4;
        int cz = centerZ >> 4;

        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只取这一圈的外环，内环已在前面的迭代里排过
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                    if (mark(id, pos)) {
                        queue.addLast(pos);
                    }
                }
            }
        }
    }

    /** 清空某玩家的队列（离开门附近 / 换维度 / 断线时调用）。 */
    public static void clear(UUID playerId) {
        QUEUES.remove(playerId);
        TARGETS.remove(playerId);
        MARKED.remove(playerId);
    }

    public static void clearAll() {
        QUEUES.clear();
        TARGETS.clear();
        MARKED.clear();
    }

    /** 队列里还剩多少区块（调试用）。 */
    public static int pending(UUID playerId) {
        Deque<ChunkPos> q = QUEUES.get(playerId);
        return q == null ? 0 : q.size();
    }

    /**
     * 每 tick 把各玩家队列头部的若干区块真正发出去。
     *
     * <p>每 tick 的发送量有上限，避免多个玩家同时在门前排队时把带宽打满。
     */
    public static void tick(MinecraftServer server) {
        if (QUEUES.isEmpty()) return;

        int budget = StpConfig.CHUNK_SEND_PER_TICK;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Deque<ChunkPos> queue = QUEUES.get(id);
            if (queue == null || queue.isEmpty()) continue;

            ServerLevel level = TARGETS.get(id);
            if (level == null) {
                clear(id);
                continue;
            }

            int sent = 0;
            while (sent < budget && !queue.isEmpty()) {
                StpManager.sendChunk(server, player, level, queue.pollFirst());
                sent++;
            }

            if (queue.isEmpty()) {
                QUEUES.remove(id);
                TARGETS.remove(id);
                MARKED.remove(id);
                StpConfig.debug("chunk pump drained for {}", player.getGameProfile().name());
            }
        }
    }

    /** 玩家断线清理。 */
    public static void onPlayerDisconnect(UUID playerId) {
        clear(playerId);
    }

    /** 返回 true 表示这个区块此前没被排过。 */
    private static boolean mark(UUID playerId, ChunkPos pos) {
        return MARKED.computeIfAbsent(playerId, k -> new HashSet<>()).add(pos.pack());
    }
}
