package mosslib.dimension;

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
        Set<ResourceKey<Level>> keys = registry.all();
        LOGGER.info("[DM] Loading {} dynamic dimension(s)", keys.size());
        for (ResourceKey<Level> key : keys) {
            ServerLevel level = getOrCreate(server, key);
            if (level == null) {
                LOGGER.warn("[DM] Failed to restore dimension {}", key.identifier());
            }
        }
    }

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