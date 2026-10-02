package doctor_m.tardis;

import doctor_m.DMBlocks;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;

public final class TardisManager {

    private TardisManager() {}

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

    /** 玩家断线 / 手动清除时调用。 */
    public static void clearGuard(UUID playerId) {
        GUARD.remove(playerId);
    }

    /** 服务器停止时整体清空，防止跨世界残留。 */
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

    /** 按外门位置查 TARDIS。上半 / 下半位置都接受。 */
    public static TardisData findByExterior(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        for (TardisData d : getRegistry(server).all()) {
            if (!dim.equals(d.exteriorDim())) continue;
            BlockPos base = d.exteriorPos();
            if (pos.equals(base) || pos.equals(base.above())) return d;
        }
        return null;
    }

    /** 按内门位置查 TARDIS。上半 / 下半位置都接受。 */
    public static TardisData findByInterior(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        UUID id = tardisIdFromDimension(dim);
        if (id == null) return null;
        TardisData d = getRegistry(server).get(id);
        if (d == null) return null;
        BlockPos base = d.interiorPos();
        return (pos.equals(base) || pos.equals(base.above())) ? d : null;
    }

    /** 从维度 key 反推 TARDIS UUID（依赖 {@link DimensionTemplate} 的命名约定）。 */
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

    /** 判断某个维度是否是 TARDIS 内部维度。 */
    public static boolean isTardisDimension(ResourceKey<Level> dim) {
        return tardisIdFromDimension(dim) != null;
    }

    // ================================================================
    //                      创建 / 恢复
    // ================================================================

    /** 生成一台全新的 TARDIS。随机 UUID + 新维度 + 内门。 */
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

    /**
     * 确保内部维度与内门方块已存在（幂等）。
     * <p>内门初始 OPEN 状态从当前外门读取——外门还没放（空气）时默认 true，
     * 之后 {@code TardisSpawnerItem} 放外门时应保持这个状态。若已存在内门则不动。
     */
    public static void ensureInterior(MinecraftServer server, TardisData data) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);
        if (level == null) return;

        // 先铺房间（幂等）
        TardisRoomGenerator.generate(level, data);

        BlockPos p = data.interiorPos();

        // 内门已存在 → 不重建，但顺手同步一次 BE（修修复 BE 丢失）
        if (level.getBlockState(p).is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
            syncDoorAppearance(server, data);
            return;
        }

        // 从外门读初始状态（外门不存在时默认 true）
        boolean open = readExteriorOpenOrDefault(server, data, true);

        BlockState lower = DMBlocks.TARDIS_INTERIOR_DOOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, data.interiorFacing())
                .setValue(AbstractTardisDoorBlock.OPEN, open);
        level.setBlock(p, lower, 3);
        level.setBlock(p.above(),
                lower.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        // 统一走 syncDoorAppearance（内外门一起写）
        syncDoorAppearance(server, data);
    }

    /** 服务器启动时调用：恢复所有 TARDIS 的内部维度，并确保内门方块存在。 */
    public static void loadAll(MinecraftServer server) {
        TardisRegistryData reg = getRegistry(server);
        for (TardisData data : reg.all()) {
            ensureInterior(server, data);
            syncDoorAppearance(server, data);

            // 同步内门 = 外门状态（外门存在时才同步）
            ServerLevel ext = server.getLevel(data.exteriorDim());
            if (ext != null && ext.getBlockState(data.exteriorPos())
                    .getBlock() instanceof AbstractTardisDoorBlock) {
                boolean open = readExteriorOpenOrDefault(server, data, true);
                setDoorOpen(server, data, open);
            }
        }
    }

    /**
     * 读取指定 TARDIS 外门当前 OPEN 状态。
     * <p>外门所在维度未加载、外门方块不存在、或位置被别的方块占据时，返回 {@code fallback}。
     */
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

    /** 外门下半天被破坏时调用。 */
    public static void onExteriorBroken(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        TardisData data = findByExterior(server, dim, pos);
        if (data == null) return;
        delete(server, data.id());
    }

    /**
     * 彻底删除一台 TARDIS：清注册表 + 删维度（含磁盘）。
     * <p>这是唯一对外暴露的删除入口，命令和方块破坏都应走这里。
     */
    public static void delete(MinecraftServer server, UUID id) {
        getRegistry(server).remove(id);
        StpServerState.clearTardis(id);                       // ★ STP
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(id));
        DynamicDimensionManager.delete(server, key);
    }

    // ================================================================
    //                      穿门
    // ================================================================

    /**
     * 门方块 entityInside / handlePassThrough 的兼容入口。
     * 会自动判断是外门还是内门，然后调用对应的 teleport。
     * <p>当前无调用点（方块直接调 {@link #teleportInto}/{@link #teleportOut}），
     * 保留作为命令 / 工具类的公共入口。
     */
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

    /** 进 TARDIS：落点 = 内门 + interiorFacing 方向 1 格，面朝 interiorFacing。 */
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

        // ★ 相对门补偿朝向，保留俯仰
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

    /** 出 TARDIS：落点 = 外门 + exteriorFacing 方向 1 格，面朝 exteriorFacing。 */
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

        // ★ 相对门补偿朝向，保留俯仰
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

    /**
     * 计算穿门后的玩家 yRot。
     * <p>玩家撞门时朝向 = 门 FACING 的反方向；传送后朝向 = 新门 FACING 方向 + 保留的偏移角。
     */
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

    /**
     * 切换某个 TARDIS 的内外门开关。外门 / 内门始终同步。
     * <p>内门维度如果没加载，会自动 getOrCreate（方案 A）。
     */
    public static void setDoorOpen(MinecraftServer server, TardisData data, boolean open) {
        // 外门
        ServerLevel ext = server.getLevel(data.exteriorDim());
        if (ext != null) setDoorState(ext, data.exteriorPos(), open);

        // 内门（维度没加载就顺手创建）
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel in = server.getLevel(key);
        if (in == null) in = DynamicDimensionManager.getOrCreate(server, key);
        if (in != null) setDoorState(in, data.interiorPos(), open);

        // ★ 方块状态改了，BE 也要跟着更新
        syncDoorAppearance(server, data);
    }

    /** 把一对上下半方块的状态设为 open。位置可以是上半或下半。 */
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

    /** 玩家手动放置内门时调用。位置若与真门重合则忽略；真门缺失时直接升格。 */
    public static void addSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        if (pos.equals(data.interiorPos())) return;

        // ★ 真门位置当前不是内门方块 → 新门直接升格为真门
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel level = server.getLevel(key);
        if (level == null) level = DynamicDimensionManager.getOrCreate(server, key);

        if (level != null) {
            BlockState cur = level.getBlockState(data.interiorPos());
            if (!cur.is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
                // 升格为真门
                BlockState placed = level.getBlockState(pos);
                Direction facing = placed.getBlock() instanceof AbstractTardisDoorBlock
                        ? placed.getValue(AbstractTardisDoorBlock.FACING)
                        : Direction.NORTH;

                data.setInterior(pos, facing);
                getRegistry(server).setDirty();

                // 新真门 OPEN 状态跟外门对齐
                boolean open = readExteriorOpenOrDefault(server, data, true);
                setDoorOpen(server, data, open);
                return;
            }
        }

        // 真门还在 → 记为备用门
        data.addSpareDoor(pos);
        getRegistry(server).setDirty();
    }

    /** 备用门被破坏时调用。 */
    public static void removeSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        if (data.removeSpareDoor(pos)) {
            getRegistry(server).setDirty();
        }
    }

    /**
     * 真出入口被破坏时调用：提升最早的备用门为真门。
     * <ul>
     *   <li>没有备用门 → 什么都不做（{@link #teleportInto} 会自愈重建）</li>
     *   <li>备用门位置方块已不存在 → 递归尝试下一个</li>
     * </ul>
     */
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
            promoteSpareDoor(level, data);   // 该位置已不是内门 → 递归找下一个
            return;
        }

        Direction facing = st.getValue(AbstractTardisDoorBlock.FACING);
        data.setInterior(newPos, facing);
        getRegistry(server).setDirty();

        // 新真门 OPEN 状态跟外门对齐
        boolean open = readExteriorOpenOrDefault(server, data, true);
        setDoorOpen(server, data, open);
    }

    /** 把外观 / TARDIS ID / 开关状态同步到内外门 BE。 */
    public static void syncDoorAppearance(MinecraftServer server, TardisData data) {
        // ---- 外门 ----
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

        // ---- 内门 ----
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
}