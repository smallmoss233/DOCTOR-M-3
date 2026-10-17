package doctor_m.tardis;

import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.register.DMBlocks;
import doctor_m.register.DMSounds;
import doctor_m.stp.StpManager;
import doctor_m.stp.StpServerState;
import doctor_m.tardis.console.ConsoleControlEntity;
import mosslib.core.dimension.DimensionTemplate;
import mosslib.core.dimension.DynamicDimensionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.slf4j.Logger;

import java.util.*;

public final class TardisManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TardisManager() {}

    // ================================================================
    //                      状态系统常量
    // ================================================================

    /** 起飞时长 = DEMAT 音效时长（21 秒）。 */
    private static final int TAKEOFF_TICKS = 420;
    /** 降落时长 = MAT 音效时长（23 秒）。 */
    private static final int LANDING_TICKS = 460;
    /** 坠机时长。比正常降落短得多 —— 强制就地砸下来。 */
    private static final int CRASH_TICKS = 100;
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

    /**
     * 找玩家"当前对应"的 TARDIS：
     * <ol>
     *   <li>玩家在某个 TARDIS 内部维度 → 那台</li>
     *   <li>否则在他所处维度里找 16 格内最近的外门</li>
     *   <li>都没有 → null</li>
     * </ol>
     */
    public static TardisData findContextTardis(MinecraftServer server, ServerPlayer player) {
        // 1. 在 TARDIS 内部？
        UUID interiorId = tardisIdFromDimension(player.level().dimension());
        if (interiorId != null) {
            return get(server, interiorId);
        }

        // 2. 找最近的外门
        TardisData nearest = null;
        double bestDistSq = 16.0 * 16.0;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (TardisData d : getRegistry(server).all()) {
            if (!player.level().dimension().equals(d.exteriorDim())) continue;

            BlockPos dp = d.exteriorPos();
            double dx = px - (dp.getX() + 0.5);
            double dy = py - (dp.getY() + 0.5);
            double dz = pz - (dp.getZ() + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = d;
            }
        }
        return nearest;
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
        // 内饰生成前的占位落点：内门要在模板里找到后才会被真正写入，
        // 找不到则保持这个回退值（0, 64, 0）。
        BlockPos interiorPos = new BlockPos(0, 64, 0);

        TardisData data = new TardisData(
                id, owner,
                exteriorDim, exteriorPos, exteriorFacing,
                interiorPos, exteriorFacing,
                List.of()
        );
        // 门禁：interiorBuilt = false 期间 teleportInto 拒绝进入，
        // 外门也保持关闭，直到内饰生成完毕（见 TardisInteriorLoader）。
        data.setInteriorBuilt(false);
        getRegistry(server).put(data);
        ensureInterior(server, data); // 同步生成；完成后 interiorBuilt = true
        return data;
    }

    /**
     * 确保内饰存在。旧版在这里程序生成房间、缺门时自动补门（自愈机制）
     * ——现已删除：内饰完全由 NBT 模板负责（见 {@link TardisInteriorLoader}），
     * 门不在模板里就不该有门。只有 {@code interiorBuilt == false}
     * （上次生成被中断）才会重新放置模板。
     *
     * <p>另外承接<b>被关服打断的内饰更换</b>：{@code pendingInteriorId} 还挂着、
     * interiorBuilt 为 true 时（玩家还没离开就关服了），说明更换流程卡在
     * "等玩家离开"那一步。重启后走这里时维度刚创建、必定无人，
     * 直接完成更换。
     */
    public static void ensureInterior(MinecraftServer server, TardisData data) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel level = DynamicDimensionManager.getOrCreate(server, key);
        if (level == null) return;

        if (data.interiorBuilt()) {
            // 上一会话没做完的内饰更换 → 现在维度是空的，直接做完
            if (data.isInteriorChanging()) {
                performInteriorSwap(server, data, level);
                return;
            }
            // 自愈：关服期间真门被 Axiom / /fill 之类替换掉 → 启动时从备用门晋升
            healAtStartup(server, data, level);
            return; // 已生成，什么都不补
        }
        TardisInteriorLoader.build(server, level, data);
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
        // 门禁：内饰还没生成完（interiorBuilt == false）不许进，
        // 防止玩家落到模板放置一半的维度里。
        // 更换内饰期间同理（等所有人离开后才会动工）。
        if (!data.interiorBuilt()) return false;
        if (data.isInteriorChanging()) return false;

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
        // 更换内饰期间塔迪斯锁死：开门请求一律忽略（关门仍放行；
        // 更换完成后的解锁开门在 performInteriorSwap 里、清除标记之后调用，不受影响）
        if (open && data.isInteriorChanging()) return;
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
    //                  备用门与真门自愈
    // ================================================================

    /**
     * 登记一把新放置的内门。入口只做一件事：触发 {@link #healInteriorDoor}
     * 的真门存活复查，然后按结果晋升 / 补绑 / 登记为备用门。
     *
     * <p>触发场景：玩家在 TARDIS 维度里手动放内门（方块侧回调）。
     */
    public static void addSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel level = server.getLevel(key);
        if (level == null) level = DynamicDimensionManager.getOrCreate(server, key);

        if (level == null) {
            // 维度起不来：先记为备用门，等下次加载时由 startup 复查收编
            data.addSpareDoor(pos);
            getRegistry(server).setDirty();
            return;
        }
        healInteriorDoor(server, data, level, pos);
    }

    /**
     * 【自愈】真门存活复查。旧版有个早退 bug：新门放在原位
     * （{@code pos == interiorPos}）时直接 return，存活检查根本不跑——
     * 真门被 Axiom / /fill 替换成别的东西后再原位补放，就会出现
     * "数据说有门、方块也是门、但 BE 是空白的"薛定谔真门。
     *
     * <p>复查逻辑：
     * <ol>
     *   <li><b>真门活着</b>（interiorPos 是内门下半块）：
     *       <ul>
     *         <li>新门在原位 → 数据没坏，只是新 BE 空白，补一次
     *             {@link #syncDoorAppearance} 绑定</li>
     *         <li>新门在别处 → 登记为备用门</li>
     *       </ul></li>
     *   <li><b>真门死了</b>：先清掉备用门名单里的死条目（可能也被替换了），
     *       然后把刚放的门晋升为真门（setInterior + BE 绑定 + 开门联动）</li>
     * </ol>
     */
    private static void healInteriorDoor(MinecraftServer server, TardisData data,
                                         ServerLevel level, BlockPos placedPos) {
        if (isTrueDoorAlive(level, data)) {
            if (placedPos.equals(data.interiorPos())) {
                syncDoorAppearance(server, data); // 原位重放：补绑空白 BE
            } else {
                data.addSpareDoor(placedPos);     // 正常登记备用门
            }
            getRegistry(server).setDirty();
            return;
        }

        // 真门已死 → 刚放的门晋升
        purgeDeadSpareDoors(level, data);
        data.removeSpareDoor(placedPos); // 别在晋升后还挂在备用名单里
        data.setInterior(placedPos.immutable(), facingOf(level, placedPos));
        getRegistry(server).put(data); // setDirty

        syncDoorAppearance(server, data);
        boolean open = readExteriorOpenOrDefault(server, data, true);
        setDoorOpen(server, data, open);

        LOGGER.info("[DM3] TARDIS {} 真门缺失，已将新放置的内门 {} 晋升为真门",
                data.id(), placedPos.toShortString());
    }

    /**
     * 【自愈】启动时复查。loadAll 逐台调用：覆盖"关服期间真门被
     * 替换 / 删除"的情况（Axiom、/fill、手滑都可能）。
     *
     * <p>真门活着 → 无事可做（loadAll 随后本就 syncDoorAppearance）。
     * 真门死了 → 从备用门名单里晋升第一把方块仍存在的门；
     * 名单全灭就清空并记警告——门没法凭空变出来，只能等玩家再放一把
     * （走 {@link #healInteriorDoor} 的放置路径晋升）。
     */
    private static void healAtStartup(MinecraftServer server, TardisData data,
                                      ServerLevel level) {
        if (isTrueDoorAlive(level, data)) return;

        purgeDeadSpareDoors(level, data);
        BlockPos candidate = data.spareDoors().stream()
                .filter(p -> level.getBlockState(p).is(DMBlocks.TARDIS_INTERIOR_DOOR))
                .findFirst().orElse(null);
        if (candidate == null) {
            LOGGER.warn("[DM3] TARDIS {} 真门缺失且没有存活的备用门，等待玩家重新放置内门",
                    data.id());
            return;
        }

        data.removeSpareDoor(candidate);
        data.setInterior(candidate, facingOf(level, candidate));
        getRegistry(server).put(data); // setDirty

        syncDoorAppearance(server, data);
        boolean open = readExteriorOpenOrDefault(server, data, true);
        setDoorOpen(server, data, open);

        LOGGER.info("[DM3] TARDIS {} 真门缺失，已将备用门 {} 晋升为真门（启动复查）",
                data.id(), candidate.toShortString());
    }

    /** 真门是否还活着：interiorPos 是内门方块且为下半块。 */
    private static boolean isTrueDoorAlive(ServerLevel level, TardisData data) {
        BlockState s = level.getBlockState(data.interiorPos());
        if (!s.is(DMBlocks.TARDIS_INTERIOR_DOOR)) return false;
        return !s.hasProperty(AbstractTardisDoorBlock.HALF)
                || s.getValue(AbstractTardisDoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    /** 清掉备用门名单里方块已不存在的死条目（被替换 / 破坏都算）。 */
    private static void purgeDeadSpareDoors(ServerLevel level, TardisData data) {
        data.spareDoors().stream()
                .filter(p -> !level.getBlockState(p).is(DMBlocks.TARDIS_INTERIOR_DOOR))
                .forEach(data::removeSpareDoor);
    }

    private static Direction facingOf(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof AbstractTardisDoorBlock
                && s.hasProperty(AbstractTardisDoorBlock.FACING)
                ? s.getValue(AbstractTardisDoorBlock.FACING)
                : Direction.NORTH;
    }

    public static void removeSpareDoor(MinecraftServer server, TardisData data, BlockPos pos) {
        if (data.removeSpareDoor(pos)) {
            getRegistry(server).setDirty();
        }
    }

    /**
     * 真门被玩家破坏时的兜底晋升（方块侧破坏回调）：
     * 从备用门名单晋升第一把方块仍存在的门，全灭则真门悬空、
     * 等待 {@link #healInteriorDoor} 的放置路径收编。
     */
    public static void promoteSpareDoor(ServerLevel level, TardisData data) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        purgeDeadSpareDoors(level, data);
        BlockPos newPos = data.popSpareDoor();
        if (newPos == null) {
            LOGGER.warn("[DM3] TARDIS {} 真门被破坏且没有备用门，等待玩家重新放置内门",
                    data.id());
            getRegistry(server).setDirty();
            return;
        }

        BlockState st = level.getBlockState(newPos);
        if (!st.is(DMBlocks.TARDIS_INTERIOR_DOOR)) {
            promoteSpareDoor(level, data);
            return;
        }

        data.setInterior(newPos, st.getValue(AbstractTardisDoorBlock.FACING));
        getRegistry(server).put(data); // setDirty

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
    //                      更换内饰
    // ================================================================

    /** 旧模板缺失时清场清理的回退半径（以原点为中心，虚空维度多清无害）。 */
    private static final int CLEAR_FALLBACK = 64;

    /**
     * 反锁到动工的最短间隔（40 tick = 2 秒）。门禁时序：
     * 点击更换 → 门保持敞开等玩家出去 → 维度清空那一刻才反锁 →
     * 至少再等这么久才清场重建。
     */
    private static final int SWAP_LOCK_DELAY_TICKS = 40;

    /**
     * 【UI / 指令接入点】请求更换内饰。UI（或指令）在玩家选定新内饰后调用，
     * 其余全部由本类接管。
     *
     * <h2>时序</h2>
     * <ol>
     *   <li><b>校验</b>：LANDED、内饰已生成、不在更换中、新旧 ID 不同、
     *       新模板在数据包里真实存在 —— 任一不满足返回 {@code false}，
     *       <b>由调用方负责向玩家反馈失败原因</b>（本方法不发失败提示）。
     *       另外：调用方<b>不要</b>自行 {@code setInteriorId}——新 ID 由
     *       {@link #performInteriorSwap} 在动工时才提交，提前写入会让
     *       清场阶段拿到错误的"旧 ID"</li>
     *   <li><b>进入更换期（门保持敞开）</b>：当场按<b>旧</b>内饰 ID 冻结清理区域
     *       （存进 TardisData，之后 interiorId 被谁改都不影响清场），写入
     *       {@code pendingInteriorId}。此后起飞被 {@link #startTakeoff} 拒绝、
     *       油门等控件锁死（{@code ThrottleHandler#canInteract}）、
     *       {@link #teleportInto} 拒绝再进入——但门<b>不关</b>，
     *       玩家从敞开的门走出去</li>
     *   <li><b>提示</b>：给内部维度里所有玩家发系统消息，请他们离开塔迪斯</li>
     *   <li><b>等待与动工</b>：{@link #tickInteriorChange} 每 tick 检查——
     *       维度清空的那一刻才反锁（关门 + 开门请求被 {@link #setDoorOpen}
     *       忽略），再等 {@link #SWAP_LOCK_DELAY_TICKS}（2 秒）才执行
     *       {@link #performInteriorSwap}（按冻结区域清空旧内饰 → 按首次生成
     *       流程重放新模板 → 解锁开门）。更换中关服也没关系，
     *       重启时 {@link #ensureInterior} 会接着把更换做完</li>
     * </ol>
     *
     * <h2>调用方还需要的查询</h2>
     * <ul>
     *   <li>{@code TardisData#isInteriorChanging()} —— 更换中（UI 灰掉按钮 /
     *       锁定控件可据此同步）</li>
     *   <li>{@code TardisData#pendingInteriorId()} —— 目标内饰 ID</li>
     *   <li>{@code TardisData#interiorId()} —— 当前内饰 ID（UI 列出可选内饰时
     *       与数据包 {@code structure/interior/} 下的模板自行比对）</li>
     * </ul>
     *
     * @return 是否受理成功；{@code false} = 校验失败（调用方自行反馈）
     */
    public static boolean requestInteriorChange(MinecraftServer server, TardisData data,
                                                Identifier newId) {
        if (newId == null) return false;
        if (data.isInteriorChanging()) return false;      // 已经在换了
        if (!data.interiorBuilt()) return false;           // 首次生成还没完成
        if (data.state() != TardisState.LANDED) return false; // 只允许地面状态换
        if (newId.equals(data.interiorId())) return false;    // 就是要现在的

        // 新模板必须真实存在，否则清完旧的会退化成回退落点
        Identifier templateId = interiorTemplateId(newId);
        if (server.getStructureTemplateManager().get(templateId).isEmpty()) return false;

        // 1) 当场冻结旧内饰的清理区域（按当前=旧 ID 计算）
        BlockPos[] region = interiorRegion(server, data.interiorId());

        // 2) 进入更换期：门保持敞开，只锁起飞 / 控件 / 传送
        data.beginInteriorChange(newId, region[0], region[1]);
        getRegistry(server).put(data); // setDirty

        // 3) 提示塔迪斯内的玩家离开
        for (ServerPlayer p : interiorLevel(server, data).players()) {
            p.sendSystemMessage(Component.translatable("doctor_m.tardis.interior.changing").withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    /**
     * 更换期 tick。门禁时序：玩家离开前门保持敞开；检测到维度清空的
     * 那一刻才反锁并起 {@link #SWAP_LOCK_DELAY_TICKS} 倒计时，归零才动工——
     * "玩家出去后才反锁，反锁至少 2 秒"。
     */
    private static void tickInteriorChange(MinecraftServer server, TardisData data) {
        ServerLevel level = interiorLevel(server, data);
        if (level == null) return; // 维度没加载：等下次

        if (!level.players().isEmpty()) {
            // 还有人在内：门保持敞开，倒计时归零（有人折返就重新等）
            if (data.interiorSwapDelay() > 0) data.setInteriorSwapDelay(0);
            return;
        }

        if (data.interiorSwapDelay() == 0) {
            // 玩家已全部离开 → 此刻才反锁 + 起倒计时
            setDoorOpen(server, data, false);
            data.setInteriorSwapDelay(SWAP_LOCK_DELAY_TICKS);
            return;
        }

        data.setInteriorSwapDelay(data.interiorSwapDelay() - 1);
        if (data.interiorSwapDelay() > 0) return;

        performInteriorSwap(server, data, level);
    }

    /**
     * 执行更换：清空旧内饰 → 提交新 ID → 重放模板 → 解锁。
     * 前提：内部维度无人（由 {@link #tickInteriorChange} /
     * {@link #ensureInterior} 保证）。
     */
    private static void performInteriorSwap(MinecraftServer server, TardisData data,
                                            ServerLevel level) {
        Identifier newId = data.pendingInteriorId();
        if (newId == null) return;

        // 1. 清空旧内饰：用请求时冻结的区域清（不读当前 interiorId——
        //    UI/payload 可能已把它改成新 ID，按它清会把新模板区域清个寂寞、
        //    旧内饰原封不动）。再清掉旧模板带来的控件实体与备用门登记
        //   （控件实体是随模板放置生成的实体，清方块带不走，必须手动清；
        //    新模板放置时会再生成一批，档位从 TardisData.controlTiers 恢复）
        BlockPos min = data.swapClearMin();
        BlockPos max = data.swapClearMax();
        if (min == null || max == null) {
            BlockPos[] region = interiorRegion(server, data.interiorId()); // 旧挂起存档兜底
            min = region[0];
            max = region[1];
        }
        clearRegion(level, min, max);
        discardControlEntities(level);
        data.clearSpareDoors();

        // 2. 提交新 ID，按首次生成流程重放（build 内部完成门绑定 + interiorBuilt）
        data.setInteriorId(newId);
        data.endInteriorChange();
        data.setInteriorBuilt(false);
        getRegistry(server).put(data); // setDirty
        TardisInteriorLoader.build(server, level, data);

        // 3. 解锁：重新开门（此时已不在更换期，门禁放行）
        setDoorOpen(server, data, true);
        getRegistry(server).put(data); // setDirty

        ServerPlayer owner = data.owner() == null ? null
                : server.getPlayerList().getPlayer(data.owner());
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("doctor_m.tardis.interior.completed").withStyle(ChatFormatting.GOLD));
        }
    }

    /**
     * 内饰逻辑 ID → 放置/清理区域（min、max，含两端）。
     * 原点算法与 {@link TardisInteriorLoader} 完全一致：模板以
     * {@code (0, BASE_Y, 0)} 居中放置。模板缺失时退化为围绕原点的
     * 大范围清理（虚空维度里多清无害）。
     */
    private static BlockPos[] interiorRegion(MinecraftServer server, Identifier interiorId) {
        var template = server.getStructureTemplateManager()
                .get(interiorTemplateId(interiorId)).orElse(null);
        if (template == null) {
            return new BlockPos[]{
                    new BlockPos(-CLEAR_FALLBACK, 0, -CLEAR_FALLBACK),
                    new BlockPos(CLEAR_FALLBACK,
                            TardisInteriorLoader.BASE_Y + CLEAR_FALLBACK, CLEAR_FALLBACK)};
        }
        Vec3i size = template.getSize();
        BlockPos origin = new BlockPos(
                -size.getX() / 2, TardisInteriorLoader.BASE_Y, -size.getZ() / 2);
        return new BlockPos[]{
                origin,
                origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1)};
    }

    /** 清空 [min, max] 区域内的所有方块（含两端），跳过空气减少 setBlock。 */
    private static void clearRegion(ServerLevel level, BlockPos min, BlockPos max) {
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }

    /** 清掉内部维度里所有控件实体（更换内饰时随旧内饰一起清）。 */
    private static void discardControlEntities(ServerLevel level) {
        for (ConsoleControlEntity e : level.getEntities(
                EntityTypeTest.forClass(ConsoleControlEntity.class), c -> true)) {
            e.discard();
        }
    }

    private static ServerLevel interiorLevel(MinecraftServer server, TardisData data) {
        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        return server.getLevel(key);
    }

    /** 内饰逻辑 ID → 模板资源 ID（与 {@link TardisInteriorLoader} 同一规则）。 */
    private static Identifier interiorTemplateId(Identifier interiorId) {
        return Identifier.fromNamespaceAndPath(
                interiorId.getNamespace(), "interior/" + interiorId.getPath());
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
        // 更换内饰期间禁止起飞（油门控件已同步锁死，这里是兜底）
        if (data.isInteriorChanging()) return false;
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

    /**
     * 坠机。要求：FLYING（飞行中拉起驻车制动触发，见 {@code ParkingBrakeBehavior}）。
     * <ul>
     *   <li>清空目的地 + 油门归零</li>
     *   <li>进入 LANDING（时长 {@link #CRASH_TICKS}，比正常降落短）</li>
     *   <li>落地时 {@code applyDestination} 是 no-op → 门在当前位置重建，
     *       即"坠毁在哪就落在哪"</li>
     * </ul>
     */
    public static boolean startCrash(MinecraftServer server, TardisData data) {
        if (data.state() != TardisState.FLYING) return false;

        data.clearDestination();
        data.setThrottle(0);
        data.setState(TardisState.LANDING, CRASH_TICKS);
        getRegistry(server).setDirty();

        // MAT 低音调 = 坠毁感；后续可以换成专属 crash 音效
        playTardisSound(server, data, DMSounds.MAT, 1.4f, 0.6f);
        return true;
    }

    // ================================================================
    //                      全局控件档位
    // ================================================================

    /**
     * 写入某类型控件的全局档位，并同步到该 TARDIS 内所有同类型控件实体。
     *
     * <h2>一份档位，全体控制台共用</h2>
     * 档位的唯一权威存在 {@link TardisData#tier(String)}（按类型 id）。
     * 任何一台控制台上的操作都走这里：
     * <ol>
     *   <li>写 {@code TardisData}（随 TARDIS 持久化）</li>
     *   <li>遍历内部维度里所有 {@code control_type} 匹配的控件实体，
     *       逐个 {@link ConsoleControlEntity#applyTier} —— 实体只写同步数据
     *       （DATA_TIER / target_time），客户端据此把骨骼动画播到对应位置</li>
     * </ol>
     * 这样：新放置的控制台生成实体后只需读一次表就能与其它控制台对位；
     * A 台拉手刹，B 台的手刹也会同步动到同一档。
     */
    public static void setControlTier(MinecraftServer server, TardisData data,
                                      String typeId, int tier) {
        data.setTier(typeId, tier);
        getRegistry(server).setDirty();

        ResourceKey<Level> key = ResourceKey.create(
                Registries.DIMENSION, DimensionTemplate.dimensionIdFor(data.id()));
        ServerLevel interior = server.getLevel(key);
        if (interior == null) return;

        for (ConsoleControlEntity e : interior.getEntities(
                EntityTypeTest.forClass(ConsoleControlEntity.class),
                c -> typeId.equals(c.getControlTypeId()))) {
            e.applyTier(tier);
        }
    }

    public static void tickAll(MinecraftServer server) {
        TardisRegistryData reg = getRegistry(server);
        List<TardisData> snapshot = List.copyOf(reg.all());
        for (TardisData data : snapshot) {
            // 更换内饰在 LANDED 时也要 tick（等所有人离开内部维度）
            if (data.isInteriorChanging()) {
                tickInteriorChange(server, data);
            }
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
                // 1. 位移：朝目的地按档位速度前进
                TardisFlight.tick(server, data);

                // 2. 油门归零 → 触发降落
                if (data.throttle() == 0) {
                    startLanding(server, data);
                    getRegistry(server).setDirty();
                    return;
                }

                // 3. 循环音效
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
                    // 落地时油门必定为 0（触发降落的前提），显式清一次保险
                    data.setThrottle(0);
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