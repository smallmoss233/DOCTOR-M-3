package doctor_m.tardis;

import doctor_m.register.DMBlocks;
import doctor_m.register.DMSounds;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.stp.StpManager;
import doctor_m.stp.StpServerState;
import mosslib.dimension.DimensionTemplate;
import mosslib.dimension.DynamicDimensionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;

public final class TardisManager {

    private TardisManager() {}

    // ================================================================
    //                      状态系统常量
    // ================================================================

    /** 起飞时长 = DEMAT 音效时长（21 秒）。 */
    private static final int TAKEOFF_TICKS = 420;
    /** 降落时长 = MAT 音效时长（23 秒）。 */
    private static final int LANDING_TICKS = 460;
    /** 飞行循环音效间隔 = FLY 音效时长。 */
    private static final int FLYING_SOUND_INTERVAL = 50;
    /** 起飞后多久开始淡出门（tick）。前 30 tick 用于关门动画。 */
    private static final int FADE_START_DELAY = 30;

    // ================================================================
    //                      Guard（传送后短暂免疫）
    // ================================================================

    private static final Map<UUID, Long> GUARD = new HashMap<>();
    private static final int GUARD_TICKS = 20;

    private static boolean isGuarded(MinecraftServer server, UUID playerId) {
        Long until = GUARD.get(playerId);
        return until != null && server.getTickCount() < until;
    }

    private static void setGuard(MinecraftServer server, UUID playerId) {
        GUARD.put(playerId, (long) (server.getTickCount() + GUARD_TICKS));
    }

    public static void clearGuard(UUID playerId) {
        GUARD.remove(playerId);
    }

    public static void clearAllGuards() {
        GUARD.clear();
    }

    // ================================================================
    //                      清单 / 查询
    // ================================================================

    public static TardisRegistryData getRegistry(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TardisRegistryData.TYPE);
    }

    public static TardisData get(MinecraftServer server, UUID id) {
        return getRegistry(server).get(id);
    }

    public static TardisData findByOwner(MinecraftServer server, UUID owner) {
        if (owner == null) return null;
        for (TardisData d : getRegistry(server).all()) {
            if (owner.equals(d.owner())) return d;
        }
        return null;
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

    // ================================================================
    //                      创建 / 恢复
    // ================================================================

    public static TardisData createNewTardis(MinecraftServer server,
                                             UUID owner,
                                             ResourceKey<Level> exteriorDim,
                                             BlockPos exteriorPos,
                                             Direction exteriorFacing) {
        UUID id = UUID.randomUUID();
        BlockPos interiorPos = new BlockPos(0, 64, 0);

        TardisData data = new TardisData(
                id, owner,
                exteriorDim, exteriorPos, exteriorFacing,
                interiorPos, exteriorFacing,
                List.of()
        );
        getRegistry(server).put(data);
        ensureInterior(server, data);
        return data;
    }

    public static void ensureInterior(MinecraftServer server, TardisData data) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);
        if (level == null) return;

        TardisRoomGenerator.generate(level, data);

        BlockPos p = data.interiorPos();

        if (level.getBlockState(p).is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
            syncDoorAppearance(server, data);
            return;
        }

        boolean open = readExteriorOpenOrDefault(server, data, true);

        BlockState lower = DMBlocks.TARDIS_INTERIOR_DOOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, data.interiorFacing())
                .setValue(AbstractTardisDoorBlock.OPEN, open);
        level.setBlock(p, lower, 3);
        level.setBlock(p.above(),
                lower.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        syncDoorAppearance(server, data);
    }

    public static void loadAll(MinecraftServer server) {
        TardisRegistryData reg = getRegistry(server);
        for (TardisData data : reg.all()) {
            ensureInterior(server, data);
            syncDoorAppearance(server, data);

            ServerLevel ext = server.getLevel(data.exteriorDim());
            if (ext != null && ext.getBlockState(data.exteriorPos())
                    .getBlock() instanceof AbstractTardisDoorBlock) {
                boolean open = readExteriorOpenOrDefault(server, data, true);
                setDoorOpen(server, data, open);
            }
        }
    }

    private static boolean readExteriorOpenOrDefault(MinecraftServer server, TardisData data,
                                                     boolean fallback) {
        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext == null) return fallback;
        BlockState s = ext.getBlockState(data.exteriorPos());
        if (!(s.getBlock() instanceof AbstractTardisDoorBlock)) return fallback;
        return s.getValue(AbstractTardisDoorBlock.OPEN);
    }

    // ================================================================
    //                      删除
    // ================================================================

    public static void onExteriorBroken(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        // 已废弃：外门被破坏不删 TARDIS
    }

    public static void delete(MinecraftServer server, UUID id) {
        getRegistry(server).remove(id);
        StpServerState.clearTardis(id);
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(id));
        DynamicDimensionManager.delete(server, key);
    }

    // ================================================================
    //                      穿门
    // ================================================================

    public static boolean tryPassThrough(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;

        ResourceKey<Level> dim = level.dimension();

        TardisData data = findByExterior(server, dim, pos);
        if (data != null) return teleportInto(player, data);

        data = findByInterior(server, dim, pos);
        if (data != null) return teleportOut(player, data);

        return false;
    }

    public static boolean teleportInto(ServerPlayer player, TardisData data) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;
        if (isGuarded(server, player.getUUID())) return false;

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel interior = DynamicDimensionManager.getOrCreate(server, key);
        if (interior == null) return false;

        StpManager.notifyEntering(player, data.id(), key);

        BlockPos p = data.interiorPos();
        Direction f = data.interiorFacing();

        float newYRot = computeTeleportYRot(player, data.exteriorFacing(), f);
        float newXRot = player.getXRot();

        player.teleportTo(interior,
                p.getX() + 0.5 + f.getStepX(),
                p.getY(),
                p.getZ() + 0.5 + f.getStepZ(),
                Set.of(), newYRot, newXRot, false);

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

        player.teleportTo(exterior,
                p.getX() + 0.5 + f.getStepX(),
                p.getY(),
                p.getZ() + 0.5 + f.getStepZ(),
                Set.of(), newYRot, newXRot, false);

        setGuard(server, player.getUUID());
        StpServerState.removePreloaded(player.getUUID(), data.id());
        return true;
    }

    private static float computeTeleportYRot(ServerPlayer player,
                                             Direction fromDoorFacing,
                                             Direction toDoorFacing) {
        float forwardYaw = fromDoorFacing.getOpposite().toYRot();
        float relativeYaw = Mth.wrapDegrees(player.getYRot() - forwardYaw);
        return Mth.wrapDegrees(toDoorFacing.toYRot() + relativeYaw);
    }

    // ================================================================
    //                      门状态联动
    // ================================================================

    public static void setDoorOpen(MinecraftServer server, TardisData data, boolean open) {
        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext != null) setDoorState(ext, data.exteriorPos(), open);

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel in = server.getLevel(key);
        if (in == null) in = DynamicDimensionManager.getOrCreate(server, key);
        if (in != null) setDoorState(in, data.interiorPos(), open);

        syncDoorAppearance(server, data);
    }

    private static void setDoorState(ServerLevel level, BlockPos pos, boolean open) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof AbstractTardisDoorBlock)) return;

        BlockPos lowerPos = s.getValue(AbstractTardisDoorBlock.HALF) == DoubleBlockHalf.LOWER
                ? pos : pos.below();
        BlockState lower = level.getBlockState(lowerPos);
        BlockState upper = level.getBlockState(lowerPos.above());

        if (lower.getBlock() instanceof AbstractTardisDoorBlock
                && lower.getValue(AbstractTardisDoorBlock.OPEN) != open) {
            level.setBlock(lowerPos, lower.setValue(AbstractTardisDoorBlock.OPEN, open), 3);
        }
        if (upper.getBlock() instanceof AbstractTardisDoorBlock
                && upper.getValue(AbstractTardisDoorBlock.OPEN) != open) {
            level.setBlock(lowerPos.above(), upper.setValue(AbstractTardisDoorBlock.OPEN, open), 3);
        }
    }

    // ================================================================
    //                      备用门
    // ================================================================

    public static void addSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        if (pos.equals(data.interiorPos())) return;

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel level = server.getLevel(key);
        if (level == null) level = DynamicDimensionManager.getOrCreate(server, key);

        if (level != null) {
            BlockState cur = level.getBlockState(data.interiorPos());
            if (!cur.is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
                BlockState placed = level.getBlockState(pos);
                Direction facing = placed.getBlock() instanceof AbstractTardisDoorBlock
                        ? placed.getValue(AbstractTardisDoorBlock.FACING)
                        : Direction.NORTH;

                data.setInterior(pos, facing);
                getRegistry(server).setDirty();

                boolean open = readExteriorOpenOrDefault(server, data, true);
                setDoorOpen(server, data, open);
                return;
            }
        }

        data.addSpareDoor(pos);
        getRegistry(server).setDirty();
    }

    public static void removeSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        if (data.removeSpareDoor(pos)) {
            getRegistry(server).setDirty();
        }
    }

    public static void promoteSpareDoor(ServerLevel level, TardisData data) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        BlockPos newPos = data.popSpareDoor();
        if (newPos == null) {
            getRegistry(server).setDirty();
            return;
        }

        BlockState st = level.getBlockState(newPos);
        if (!st.is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
            promoteSpareDoor(level, data);
            return;
        }

        Direction facing = st.getValue(AbstractTardisDoorBlock.FACING);
        data.setInterior(newPos, facing);
        getRegistry(server).setDirty();

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
                if (s.getBlock() instanceof AbstractTardisDoorBlock) {
                    be.setOpen(s.getValue(AbstractTardisDoorBlock.OPEN));
                }
            }
        }

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel in = server.getLevel(key);
        if (in != null) {
            BlockPos pos = data.interiorPos();
            if (in.getBlockEntity(pos) instanceof TardisDoorBlockEntity be) {
                be.setAppearance(data.appearanceId());
                be.setTardisId(data.id());
                be.setExterior(false);
                BlockState s = in.getBlockState(pos);
                if (s.getBlock() instanceof AbstractTardisDoorBlock) {
                    be.setOpen(s.getValue(AbstractTardisDoorBlock.OPEN));
                }
            }
        }
    }

    // ================================================================
    //                      状态系统
    // ================================================================

    public static void setDestination(MinecraftServer server, TardisData data,
                                      ResourceKey<Level> dim, BlockPos pos) {
        data.setDestination(dim, pos);
        getRegistry(server).setDirty();
    }

    /**
     * 起飞。要求：LANDED。
     * <ul>
     *   <li>关门（播 close 动画）</li>
     *   <li>播 DEMAT 音效（21 秒，塔迪斯内外都能听到）</li>
     *   <li>进入 TAKEOFF，持续 21 秒</li>
     * </ul>
     */
    public static boolean startTakeoff(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.LANDED) return false;

        // 1) 关门
        setDoorOpen(server, data, false);

        // 2) 进入 TAKEOFF
        data.setState(TardisState.TAKEOFF, TAKEOFF_TICKS);
        getRegistry(server).setDirty();

        // 3) 起飞音效（内外都播）
        playTardisSound(server, data, DMSounds.DEMAT, 1.0f, 1.0f);
        return true;
    }

    /**
     * 开始降落。要求：FLYING。
     * <ul>
     *   <li>播 MAT 音效（23 秒，内外都播）</li>
     *   <li>进入 LANDING，持续 23 秒</li>
     *   <li>LANDING 结束时才放门 → LANDED</li>
     * </ul>
     */
    public static boolean startLanding(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        data.setState(TardisState.LANDING, LANDING_TICKS);
        getRegistry(server).setDirty();

        playTardisSound(server, data, DMSounds.MAT, 1.0f, 1.0f);
        return true;
    }

    public static void tickAll(MinecraftServer server) {
        TardisRegistryData reg = getRegistry(server);
        List<TardisData> snapshot = List.copyOf(reg.all());
        for (TardisData data : snapshot) {
            if (data.state() == TardisState.LANDED) continue;
            tickOne(server, data);
        }
    }

    private static void triggerExteriorFade(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        if (level.getBlockEntity(lower) instanceof TardisDoorBlockEntity be) {
            be.startFadeOut();
        }
    }

    // ---- 内部 ----

    private static void tickOne(MinecraftServer server, TardisData data) {
        TardisState s = data.state();
        int t = data.stateTicks() - 1;

        switch (s) {
            case TAKEOFF -> {
                int elapsed = TAKEOFF_TICKS - t;

                // 30 tick：触发淡出
                if (elapsed == FADE_START_DELAY) {
                    triggerExteriorFade(server, data);
                }
                // 30 + 60 = 90 tick：淡出结束，无声拆门
                if (elapsed == FADE_START_DELAY + TardisDoorBlockEntity.FADE_DURATION) {
                    removeExteriorDoor(server, data);
                }

                if (t <= 0) {
                    // 420 tick：DEMAT 播完 → FLYING，开始播 FLY 循环
                    data.setState(TardisState.FLYING, FLYING_SOUND_INTERVAL);
                    getRegistry(server).setDirty();
                } else {
                    data.setStateTicks(t);
                }
            }
            case FLYING -> {
                if (t <= 0) {
                    playTardisSound(server, data, DMSounds.FLY, 0.8f, 1.0f);
                    data.setStateTicks(FLYING_SOUND_INTERVAL);
                } else {
                    data.setStateTicks(t);
                }
            }
            case LANDING -> {
                if (t <= 0) {
                    // 460 tick：MAT 播完 → 放门 + 进入 LANDED
                    applyDestination(server, data);
                    placeExteriorDoor(server, data);
                    data.setState(TardisState.LANDED, 0);
                    getRegistry(server).setDirty();
                } else {
                    data.setStateTicks(t);
                }
            }
            default -> {}
        }
    }

    private static void applyDestination(MinecraftServer server, TardisData data) {
        if (!data.hasDestination()) return;
        data.setExterior(data.targetDim(), data.targetPos(), data.exteriorFacing());
        data.clearDestination();
        getRegistry(server).setDirty();
    }

    private static void removeExteriorDoor(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        BlockPos upper = lower.above();

        BlockState lowerState = level.getBlockState(lower);
        if (lowerState.getBlock() instanceof AbstractTardisDoorBlock) {
            level.setBlock(lower, Blocks.AIR.defaultBlockState(), 3);
        }
        BlockState upperState = level.getBlockState(upper);
        if (upperState.getBlock() instanceof AbstractTardisDoorBlock) {
            level.setBlock(upper, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void placeExteriorDoor(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        Direction facing = data.exteriorFacing();

        BlockState lowerState = DMBlocks.TARDIS_EXTERIOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, facing)
                .setValue(AbstractTardisDoorBlock.OPEN, false);
        BlockState upperState = lowerState.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER);

        level.setBlock(lower, lowerState, 3);
        level.setBlock(lower.above(), upperState, 3);

        // 只给下半 BE 写数据 + 触发淡入
        if (level.getBlockEntity(lower) instanceof TardisDoorBlockEntity be) {
            be.setTardisId(data.id());
            be.setAppearance(data.appearanceId());
            be.setExterior(true);
            be.startFadeIn();
        }
    }

    /**
     * 在 TARDIS 内外各播一次音效。
     * <ul>
     *   <li>内部：围绕内门</li>
     *   <li>外部：围绕外门坐标（即使外门方块已消失，坐标仍然有效）</li>
     * </ul>
     */
    private static void playTardisSound(MinecraftServer server, TardisData data,
                                        SoundEvent sound, float volume, float pitch) {
        // 内部维度
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel interior = server.getLevel(key);
        if (interior != null) {
            BlockPos center = data.interiorPos();
            interior.playSound(null,
                    center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                    sound, SoundSource.BLOCKS, volume, pitch);
        }

        // 外部维度（用 exteriorPos 的坐标，不依赖方块是否存在）
        if (data.exteriorDim() != null && data.exteriorPos() != null) {
            ServerLevel exterior = server.getLevel(data.exteriorDim());
            if (exterior != null) {
                BlockPos center = data.exteriorPos();
                exterior.playSound(null,
                        center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                        sound, SoundSource.BLOCKS, volume, pitch);
            }
        }
    }
}