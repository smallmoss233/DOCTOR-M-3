package mosslib.dimension;

import mosslib.mixin.MinecraftServerAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态维度的调试工具。
 * <p>只读：查询维度的运行时状态和存档路径。
 * <p>写：强制保存 / 重载 / 删除，用于持久化测试。
 */
public final class DimensionDebug {

    private DimensionDebug() {}

    /** 一条维度的状态快照。 */
    public record Snapshot(
            ResourceKey<Level> key,
            int playerCount,
            int entityCount,
            int loadedChunks,
            Path dimensionPath,
            boolean pathExists,
            long pathSizeBytes
    ) {}

    /** 列出所有动态维度（跳过三大原版维度）。 */
    public static List<Snapshot> listDynamic(MinecraftServer server) {
        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
        var storage = acc.mosslib$getStorageSource();

        List<Snapshot> result = new ArrayList<>();
        for (var entry : acc.mosslib$getLevels().entrySet()) {
            ResourceKey<Level> key = entry.getKey();
            if (isVanillaDimension(key)) continue;

            ServerLevel level = entry.getValue();

            // 实体计数：遍历 Iterable
            int entityCount = 0;
            for (Entity ignored : level.getAllEntities()) entityCount++;

            Path path = storage.getDimensionPath(key);
            boolean exists = Files.isDirectory(path);
            long size = exists ? directorySize(path) : 0L;

            result.add(new Snapshot(
                    key,
                    level.players().size(),
                    entityCount,
                    level.getChunkSource().getLoadedChunksCount(),
                    path,
                    exists,
                    size
            ));
        }
        return result;
    }

    /** 强制保存所有动态维度。 */
    public static void saveAll(MinecraftServer server) {
        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
        for (var entry : acc.mosslib$getLevels().entrySet()) {
            if (isVanillaDimension(entry.getKey())) continue;
            entry.getValue().save(null, true, false);
        }
    }

    /** 卸载所有动态维度。重进时通过 getOrCreate 重新加载。 */
    public static void unloadAll(MinecraftServer server) {
        MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
        List<ResourceKey<Level>> toRemove = new ArrayList<>();
        for (var entry : acc.mosslib$getLevels().entrySet()) {
            if (isVanillaDimension(entry.getKey())) continue;
            toRemove.add(entry.getKey());
        }
        for (ResourceKey<Level> key : toRemove) {
            DynamicDimensionManager.unload(server, key);
        }
    }

    // ================================================================
    //                      内部
    // ================================================================

    private static boolean isVanillaDimension(ResourceKey<Level> key) {
        return key == Level.OVERWORLD || key == Level.NETHER || key == Level.END;
    }

    private static long directorySize(Path path) {
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (Exception e) { return 0L; }
                    })
                    .sum();
        } catch (Exception e) {
            return -1L;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}