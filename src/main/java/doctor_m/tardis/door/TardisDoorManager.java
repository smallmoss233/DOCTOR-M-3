package doctor_m.tardis.door;

import com.mojang.logging.LogUtils;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.register.DMBlocks;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.slf4j.Logger;

public final class TardisDoorManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private TardisDoorManager() {}

    public static void setDoorOpen(MinecraftServer server, TardisData data, boolean open) {
        if (open && data.isInteriorChanging()) return;

        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext != null) setDoorState(ext, data.exteriorPos(), open);

        ServerLevel in = TardisManager.getOrCreateInteriorLevel(server, data);
        if (in != null) setDoorState(in, data.interiorPos(), open);

        syncDoorAppearance(server, data);
    }

    private static void setDoorState(ServerLevel level, BlockPos pos, boolean open) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof AbstractTardisDoorBlock)) return;

        BlockPos lowerPos = s.getValue(AbstractTardisDoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        BlockState lower = level.getBlockState(lowerPos);
        BlockState upper = level.getBlockState(lowerPos.above());

        if (lower.getBlock() instanceof AbstractTardisDoorBlock && lower.getValue(AbstractTardisDoorBlock.OPEN) != open) {
            level.setBlock(lowerPos, lower.setValue(AbstractTardisDoorBlock.OPEN, open), 3);
        }
        if (upper.getBlock() instanceof AbstractTardisDoorBlock && upper.getValue(AbstractTardisDoorBlock.OPEN) != open) {
            level.setBlock(lowerPos.above(), upper.setValue(AbstractTardisDoorBlock.OPEN, open), 3);
        }
    }

    public static void addSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        ServerLevel level = TardisManager.getOrCreateInteriorLevel(server, data);
        if (level == null) {
            data.addSpareDoor(pos);
            TardisManager.getRegistry(server).setDirty();
            return;
        }
        healInteriorDoor(server, data, level, pos);
    }

    private static void healInteriorDoor(MinecraftServer server, TardisData data, ServerLevel level, BlockPos placedPos) {
        if (isTrueDoorAlive(level, data)) {
            if (placedPos.equals(data.interiorPos())) {
                syncDoorAppearance(server, data);
            } else {
                data.addSpareDoor(placedPos);
            }
            TardisManager.getRegistry(server).setDirty();
            return;
        }

        purgeDeadSpareDoors(level, data);
        data.removeSpareDoor(placedPos);
        data.setInterior(placedPos.immutable(), facingOf(level, placedPos));
        TardisManager.getRegistry(server).put(data);

        syncDoorAppearance(server, data);
        boolean open = readExteriorOpenOrDefault(server, data, true);
        setDoorOpen(server, data, open);
        LOGGER.info("[DM3] TARDIS {} 真门缺失，已将新放置的内门 {} 晋升为真门", data.id(), placedPos.toShortString());
    }

    private static void healAtStartup(MinecraftServer server, TardisData data, ServerLevel level) {
        if (isTrueDoorAlive(level, data)) return;

        purgeDeadSpareDoors(level, data);
        BlockPos candidate = data.spareDoors().stream()
                .filter(p -> level.getBlockState(p).is(DMBlocks.TARDIS_INTERIOR_DOOR))
                .findFirst().orElse(null);
        if (candidate == null) {
            LOGGER.warn("[DM3] TARDIS {} 真门缺失且没有存活的备用门，等待玩家重新放置内门", data.id());
            return;
        }

        data.removeSpareDoor(candidate);
        data.setInterior(candidate, facingOf(level, candidate));
        TardisManager.getRegistry(server).put(data);

        syncDoorAppearance(server, data);
        boolean open = readExteriorOpenOrDefault(server, data, true);
        setDoorOpen(server, data, open);
    }

    public static void restoreDoorState(MinecraftServer server, TardisData data) {
        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext != null && ext.getBlockState(data.exteriorPos()).getBlock() instanceof AbstractTardisDoorBlock) {
            boolean open = readExteriorOpenOrDefault(server, data, true);
            setDoorOpen(server, data, open);
        }
    }

    private static boolean readExteriorOpenOrDefault(MinecraftServer server, TardisData data, boolean fallback) {
        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext == null) return fallback;
        BlockState s = ext.getBlockState(data.exteriorPos());
        if (!(s.getBlock() instanceof AbstractTardisDoorBlock)) return fallback;
        return s.getValue(AbstractTardisDoorBlock.OPEN);
    }

    private static boolean isTrueDoorAlive(ServerLevel level, TardisData data) {
        BlockState s = level.getBlockState(data.interiorPos());
        if (!s.is(DMBlocks.TARDIS_INTERIOR_DOOR)) return false;
        return !s.hasProperty(AbstractTardisDoorBlock.HALF) || s.getValue(AbstractTardisDoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static void purgeDeadSpareDoors(ServerLevel level, TardisData data) {
        data.spareDoors().stream()
                .filter(p -> !level.getBlockState(p).is(DMBlocks.TARDIS_INTERIOR_DOOR))
                .forEach(data::removeSpareDoor);
    }

    private static Direction facingOf(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof AbstractTardisDoorBlock && s.hasProperty(AbstractTardisDoorBlock.FACING)
                ? s.getValue(AbstractTardisDoorBlock.FACING) : Direction.NORTH;
    }

    public static void removeSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        if (data.removeSpareDoor(pos)) {
            TardisManager.getRegistry(server).setDirty();
        }
    }

    public static void promoteSpareDoor(ServerLevel level, TardisData data) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        purgeDeadSpareDoors(level, data);
        BlockPos newPos = data.popSpareDoor();
        if (newPos == null) {
            TardisManager.getRegistry(server).setDirty();
            return;
        }

        BlockState st = level.getBlockState(newPos);
        if (!st.is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
            promoteSpareDoor(level, data);
            return;
        }

        data.setInterior(newPos, st.getValue(AbstractTardisDoorBlock.FACING));
        TardisManager.getRegistry(server).put(data);

        syncDoorAppearance(server, data);
        boolean open = readExteriorOpenOrDefault(server, data, true);
        setDoorOpen(server, data, open);
    }

    public static void syncDoorAppearance(MinecraftServer server, TardisData data) {
        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext != null) {
            BlockPos pos = data.exteriorPos();
            if (ext.getBlockEntity(pos) instanceof TardisDoorBlockEntity be) {
                be.setAppearance(data.appearanceId());
                be.setTardisId(data.id());
                be.setExterior(true);
                BlockState s = ext.getBlockState(pos);
                if (s.getBlock() instanceof AbstractTardisDoorBlock) be.setOpen(s.getValue(AbstractTardisDoorBlock.OPEN));
            }
        }

        ServerLevel in = TardisManager.getInteriorLevel(server, data);
        if (in != null) {
            BlockPos pos = data.interiorPos();
            if (in.getBlockEntity(pos) instanceof TardisDoorBlockEntity be) {
                be.setAppearance(data.appearanceId());
                be.setTardisId(data.id());
                be.setExterior(false);
                BlockState s = in.getBlockState(pos);
                if (s.getBlock() instanceof AbstractTardisDoorBlock) be.setOpen(s.getValue(AbstractTardisDoorBlock.OPEN));
            }
        }
    }
}