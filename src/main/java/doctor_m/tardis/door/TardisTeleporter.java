package doctor_m.tardis.door;

import doctor_m.stp.StpManager;
import doctor_m.stp.StpServerState;
import doctor_m.tardis.TardisManager;
import doctor_m.tardis.data.TardisData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TardisTeleporter {
    private static final Map<UUID, Long> GUARD = new HashMap<>();
    private static final int GUARD_TICKS = 20;

    private TardisTeleporter() {}

    private static boolean isGuarded(MinecraftServer server, UUID playerId) {
        Long until = GUARD.get(playerId);
        return until != null && server.getTickCount() < until;
    }

    private static void setGuard(MinecraftServer server, UUID playerId) {
        GUARD.put(playerId, (long) (server.getTickCount() + GUARD_TICKS));
    }

    public static boolean teleportInto(ServerPlayer player, TardisData data) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;
        if (isGuarded(server, player.getUUID())) return false;
        if (!data.interiorBuilt() || data.isInteriorChanging()) return false;

        ServerLevel interior = TardisManager.getOrCreateInteriorLevel(server, data);
        if (interior == null) return false;

        StpManager.notifyEntering(player, data.id(), interior.dimension());

        BlockPos p = data.interiorPos();
        Direction f = data.interiorFacing();
        float newYRot = computeTeleportYRot(player, data.exteriorFacing(), f);
        float newXRot = player.getXRot();

        player.teleportTo(interior, p.getX() + 0.5 + f.getStepX(), p.getY(), p.getZ() + 0.5 + f.getStepZ(), Set.of(), newYRot, newXRot, false);

        setGuard(server, player.getUUID());
        StpServerState.removePreloaded(player.getUUID(), data.id());
        return true;
    }

    public static boolean teleportOut(ServerPlayer player, TardisData data) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;
        if (isGuarded(server, player.getUUID())) return false;
        if (data.exteriorDim() == null || data.exteriorPos() == null) return false;

        ServerLevel exterior = server.getLevel(data.exteriorDim());
        if (exterior == null) return false;

        StpManager.notifyEnteringWorld(player, data.exteriorDim());

        BlockPos p = data.exteriorPos();
        Direction f = data.exteriorFacing();
        float newYRot = computeTeleportYRot(player, data.interiorFacing(), f);
        float newXRot = player.getXRot();

        player.teleportTo(exterior, p.getX() + 0.5 + f.getStepX(), p.getY(), p.getZ() + 0.5 + f.getStepZ(), Set.of(), newYRot, newXRot, false);

        setGuard(server, player.getUUID());
        StpServerState.removePreloaded(player.getUUID(), data.id());
        return true;
    }

    private static float computeTeleportYRot(ServerPlayer player, Direction fromDoorFacing, Direction toDoorFacing) {
        float forwardYaw = fromDoorFacing.getOpposite().toYRot();
        float relativeYaw = Mth.wrapDegrees(player.getYRot() - forwardYaw);
        return Mth.wrapDegrees(toDoorFacing.toYRot() + relativeYaw);
    }
}