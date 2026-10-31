package mosslib.core.dimension;

import com.mojang.logging.LogUtils;
import mosslib.mixin.MinecraftServerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class DynamicDimensionManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 在没有任何玩家时可以被清理的维度：维度 -> 最后玩家离开的 tick。 */
    private static final Map<ResourceKey<Level>, Long> IDLE_SINCE = new HashMap<>();
    /** 维度第一次出现在 Tardis 注册表时记录的 tick；0 表示未知（保守起见不清理）。 */
    private static final Map<ResourceKey<Level>, Long> CREATED_AT = new HashMap<>();

    private DynamicDimensionManager() {}

// ================================================================
//                      持久化清单
// ================================================================

    /** 获取维度清单。主世界的 data 目录下自动持久化。 */
    private static DimensionRegistryData getRegistryData(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(DimensionRegistryData.TYPE);
    }

    /**
     * 服务器启动时调用。读取清单，逐个恢复维度实例。
     */
    public static void loadAll(MinecraftServer server) {
        DimensionRegistryData registry = getRegistryData(server);
        // 清掉历史脏数据（如 "tardis/null"），否则下面会真的去创建这些维度。
        registry.cleanup();
        Set<ResourceKey<Level>> keys = registry.all();
        long now = server.getTickCount();
        for (ResourceKey<Level> key : keys) {
            CREATED_AT.putIfAbsent(key, now);
            ServerLevel level = getOrCreate(server, key);
            if (level == null) {
                LOGGER.warn("[DM] Failed to restore dimension {}", key.identifier());
            }
        }
    }

    /**
     * 每 tick 调用：清理长时间无玩家的闲置维度。
     *
     * <p>正在被玩家占用的维度（{@code level.players()} 非空）永远不会被卸载，
     * 所以正常游玩时感知为零。只有完全没人在里面且超过 5 分钟才会释放。
     *
     * <p>建议在 {@code ServerTickEvents#END_SERVER_TICK} 调一次，比如：
     * <pre>{@code ServerTickEvents.END_SERVER_TICK.register(DynamicDimensionManager::tick);}</pre>
     */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();

        for (Map.Entry<ResourceKey<Level>, Long> e : new ArrayList<>(IDLE_SINCE.entrySet())) {
            ResourceKey<Level> key = e.getKey();
            if (now - e.getValue() < IDLE_TICKS) continue;

            // 已有玩家在里面 → 不是闲置
            ServerLevel level = server.getLevel(key);
            if (level != null && !level.players().isEmpty()) {
                IDLE_SINCE.remove(key);
                continue;
            }

            unload(server, key);
            IDLE_SINCE.remove(key);
        }
    }

    /**
     * 把维度标记为闲置开始计时。
     *
     * <p>调用时机：维度上最后一个玩家离开时。如果下次玩家进入前 {@link
     * #IDLE_TICKS} tick 内回来，完全不产生重建开销；否则会在下次进入时
     * 从存档恢复。
     */
    public static void noteIdleStart(ResourceKey<Level> key, long now) {
        IDLE_SINCE.putIfAbsent(key, now);
    }

    /** 把维度从闲置计时里移出（玩家进入时调用）。 */
    public static void noteIdleEnd(ResourceKey<Level> key) {
        IDLE_SINCE.remove(key);
    }

    private static final long IDLE_TICKS = 20L * 60L * 5L; // 5 分钟

// ================================================================
//                      公开 API（修改）
// ================================================================

    public static ServerLevel getOrCreate(MinecraftServer server, ResourceKey<Level> key) {
        // 记录到清单（即使已有实例）
        getRegistryData(server).add(key);

        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
        ServerLevel existing = acc.mosslib$getLevels().get(key);
        if (existing != null) return existing;

        ServerLevel level = create(server, key);
        if (level == null) return null;

        acc.mosslib$getLevels().put(key, level);
        CREATED_AT.putIfAbsent(key, (long) server.getTickCount());
        LOGGER.info("[DM] Created dynamic dimension {}", key.identifier());
        return level;
    }

    public static void unload(MinecraftServer server, ResourceKey<Level> key) {
        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
        ServerLevel level = acc.mosslib$getLevels().get(key);
        if (level == null) return;

        // 玩家传回主世界
        ServerLevel overworld = server.overworld();
        BlockPos spawn = server.getRespawnData().pos();
        for (ServerPlayer player : new ArrayList<>(level.players())) {
            player.teleportTo(overworld,
                    spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    Set.of(),
                    player.getYRot(), player.getXRot(),
                    false);
        }

        // ★ 从 map 移除，但保留清单记录（下次进入时自动恢复）
        acc.mosslib$getLevels().remove(key);

        try {
            level.close();
        } catch (IOException e) {
            LOGGER.error("[DM] Failed to close dynamic dimension {}", key.identifier(), e);
        }
        LOGGER.info("[DM] Unloaded dynamic dimension {}", key.identifier());
    }

    // ================================================================
    //                      内部实现
    // ================================================================

    private static ServerLevel create(MinecraftServer server, ResourceKey<Level> key) {
        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;

        LevelStem stem = buildStem(server);

        ServerLevelData levelData = new DerivedLevelData(
                server.getWorldData(),
                server.getWorldData().overworldData()
        );

        long seed = server.getWorldGenSettings().options().seed();
        long biomeZoomSeed = BiomeManager.obfuscateSeed(seed);

        try {
            return new ServerLevel(
                    server,
                    acc.mosslib$getExecutor(),
                    acc.mosslib$getStorageSource(),
                    levelData,
                    key,
                    stem,
                    false,
                    biomeZoomSeed,
                    List.of(),
                    false
            );
        } catch (Exception e) {
            LOGGER.error("[DM] Failed to create dynamic dimension {}", key.identifier(), e);
            return null;
        }
    }

    private static LevelStem buildStem(MinecraftServer server) {
        var registries = server.registryAccess();

        // 维度类型
        Holder<DimensionType> dimType = registries
                .lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(DimensionTemplate.TARDIS_TYPE);

        // ★ 改成用自定义群系
        Holder<Biome> tardisBiome = registries
                .lookupOrThrow(Registries.BIOME)
                .getOrThrow(DimensionTemplate.TARDIS_BIOME);

        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(
                Optional.empty(),
                tardisBiome,
                List.of()
        );

        FlatLevelSource generator = new FlatLevelSource(settings);
        return new LevelStem(dimType, generator);
    }

    /**
     * 彻底删除维度——卸载 + 清持久化清单 + 删存档目录。
     * <p>与 {@link #unload} 的区别：unload 只从内存移除，存档保留；
     * delete 会连带磁盘数据一起删除。
     *
     * @return true 表示成功（或磁盘本来就没有数据）
     */
    public static boolean delete(MinecraftServer server, ResourceKey<Level> key) {
        // 1. 卸载：玩家传回主世界 + close（幂等，map 里没有就跳过）
        unload(server, key);

        // 2. 从持久化清单移除
        getRegistryData(server).remove(key);

        // 3. 删存档目录
        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
        Path path = acc.mosslib$getStorageSource().getDimensionPath(key);

        if (!Files.exists(path)) {
            LOGGER.info("[DM] No disk data for {}", key.identifier());
            return true;
        }

        try {
            deleteRecursive(path);
            LOGGER.info("[DM] Deleted dimension data at {}", path);
            return true;
        } catch (IOException e) {
            LOGGER.error("[DM] Failed to delete dimension data at {}", path, e);
            return false;
        }
    }

    /** 递归删除目录。所有删除尝试完再报告错误。 */
    private static void deleteRecursive(Path path) throws IOException {
        List<Path> paths;
        try (var stream = Files.walk(path)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }

        IOException lastError = null;
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                lastError = e;
                LOGGER.warn("[DM] Failed to delete {}", p, e);
            }
        }
        if (lastError != null) throw lastError;
    }
}