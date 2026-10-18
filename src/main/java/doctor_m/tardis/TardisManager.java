package doctor_m.tardis;

import doctor_m.stp.StpServerState;
import doctor_m.tardis.console.ConsoleControlEntity;
import doctor_m.tardis.console.ControlTypes;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisRegistryData;
import doctor_m.tardis.data.TardisState;
import doctor_m.tardis.door.TardisDoorManager;
import doctor_m.tardis.door.TardisTeleporter;
import doctor_m.tardis.interior.TardisInteriorManager;
import mosslib.core.dimension.DimensionTemplate;
import mosslib.core.dimension.DynamicDimensionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.List;
import java.util.UUID;

public final class TardisManager {

    private TardisManager() {}

    // ================================================================
    //                      清单 / 查询
    // ================================================================
    public static TardisRegistryData getRegistry(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TardisRegistryData.TYPE);
    }

    public static TardisData get(MinecraftServer server, UUID id) {
        return getRegistry(server).get(id);
    }

    public static TardisData findByExterior(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        for (TardisData d : getRegistry(server).all()) {
            if (!dim.equals(d.exteriorDim())) continue;
            BlockPos base = d.exteriorPos();
            if (pos.equals(base) || pos.equals(base.above())) return d;
        }
        return null;
    }

    public static TardisData findByInterior(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        UUID id = tardisIdFromDimension(dim);
        if (id == null) return null;
        TardisData d = getRegistry(server).get(id);
        if (d == null) return null;
        BlockPos base = d.interiorPos();
        return (pos.equals(base) || pos.equals(base.above())) ? d : null;
    }

    public static UUID tardisIdFromDimension(ResourceKey<Level> dim) {
        Identifier id = dim.identifier();
        if (!"doctor_m".equals(id.getNamespace())) return null;
        String p = id.getPath();
        if (!p.startsWith("tardis/")) return null;
        try {
            return UUID.fromString(p.substring("tardis/".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isTardisDimension(ResourceKey<Level> dim) {
        return tardisIdFromDimension(dim) != null;
    }

    public static TardisData findContextTardis(MinecraftServer server, ServerPlayer player) {
        UUID interiorId = tardisIdFromDimension(player.level().dimension());
        if (interiorId != null) return get(server, interiorId);

        TardisData nearest = null;
        double bestDistSq = 16.0 * 16.0;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (TardisData d : getRegistry(server).all()) {
            if (!player.level().dimension().equals(d.exteriorDim())) continue;
            BlockPos dp = d.exteriorPos();
            double distSq = Math.pow(px - (dp.getX() + 0.5), 2) + Math.pow(py - (dp.getY() + 0.5), 2) + Math.pow(pz - (dp.getZ() + 0.5), 2);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = d;
            }
        }
        return nearest;
    }

    // ================================================================
    //                      维度快捷获取
    // ================================================================
    public static ServerLevel getInteriorLevel(MinecraftServer server, TardisData data) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        return server.getLevel(key);
    }

    public static ServerLevel getOrCreateInteriorLevel(MinecraftServer server, TardisData data) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        return DynamicDimensionManager.getOrCreate(server, key);
    }

    // ================================================================
    //                      创建 / 恢复 / 删除
    // ================================================================
    public static TardisData createNewTardis(MinecraftServer server, UUID owner, ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing) {
        UUID id = UUID.randomUUID();
        BlockPos interiorPos = new BlockPos(0, 64, 0);

        TardisData data = new TardisData(
                id, owner, exteriorDim, exteriorPos, exteriorFacing,
                interiorPos, exteriorFacing, List.of()
        );
        data.setInteriorBuilt(false);
        data.setTier(ControlTypes.LANDING_FACING.id(), doctor_m.tardis.console.handler.LandingFacingBehavior.tierOf(exteriorFacing));

        getRegistry(server).put(data);
        TardisInteriorManager.ensureInterior(server, data);
        return data;
    }

    public static void delete(MinecraftServer server, UUID id) {
        getRegistry(server).remove(id);
        StpServerState.clearTardis(id);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, DimensionTemplate.dimensionIdFor(id));
        DynamicDimensionManager.delete(server, key);
    }

    // ================================================================
    //                      路由转发 (保持旧 API 签名兼容)
    // ================================================================
    public static boolean teleportInto(ServerPlayer player, TardisData data) {
        return TardisTeleporter.teleportInto(player, data);
    }

    public static boolean teleportOut(ServerPlayer player, TardisData data) {
        return TardisTeleporter.teleportOut(player, data);
    }

    public static void setDoorOpen(MinecraftServer server, TardisData data, boolean open) {
        TardisDoorManager.setDoorOpen(server, data, open);
    }

    public static void addSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        TardisDoorManager.addSpareDoor(server, data, pos);
    }

    public static void removeSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        TardisDoorManager.removeSpareDoor(server, data, pos);
    }

    public static void promoteSpareDoor(ServerLevel level, TardisData data) {
        TardisDoorManager.promoteSpareDoor(level, data);
    }

    public static void syncDoorAppearance(MinecraftServer server, TardisData data) {
        TardisDoorManager.syncDoorAppearance(server, data);
    }

    public static boolean requestInteriorChange(MinecraftServer server, TardisData data, Identifier newId) {
        return TardisInteriorManager.requestInteriorChange(server, data, newId);
    }

    public static void setDestination(MinecraftServer server, TardisData data, ResourceKey<Level> dim, BlockPos pos) {
        data.setDestination(dim, pos);
        getRegistry(server).setDirty();
    }

    public static boolean startTakeoff(MinecraftServer server, TardisData data) {
        return TardisFlight.startTakeoff(server, data);
    }

    public static boolean startLanding(MinecraftServer server, TardisData data) {
        return TardisFlight.startLanding(server, data);
    }

    public static boolean startCrash(MinecraftServer server, TardisData data) {
        return TardisFlight.startCrash(server, data);
    }

    public static void tickAll(MinecraftServer server) {
        for (TardisData data : List.copyOf(getRegistry(server).all())) {
            if (data.isInteriorChanging()) {
                TardisInteriorManager.tickInteriorChange(server, data);
            }
            if (data.state() != TardisState.LANDED) {
                TardisFlight.tick(server, data);
            }
        }
    }

    public static void setControlTier(MinecraftServer server, TardisData data, String typeId, int tier) {
        data.setTier(typeId, tier);
        getRegistry(server).setDirty();

        ServerLevel interior = getInteriorLevel(server, data);
        if (interior == null) return;

        for (ConsoleControlEntity e : interior.getEntities(EntityTypeTest.forClass(ConsoleControlEntity.class), c -> typeId.equals(c.getControlTypeId()))) {
            e.applyTier(tier);
        }
    }
}