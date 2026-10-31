package doctor_m.tardis.interior;

import doctor_m.tardis.TardisManager;
import doctor_m.tardis.console.control.ConsoleControlEntity;
import doctor_m.tardis.data.TardisData;
import doctor_m.tardis.data.TardisState;
import doctor_m.tardis.door.TardisDoorManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.*;

public final class TardisInteriorManager {
    private static final int CLEAR_FALLBACK = 64;
    private static final int SWAP_LOCK_DELAY_TICKS = 40;
    /** 每 tick 最多清除多少个方块，避免更换内饰时单帧卡顿。 */
    private static final int CLEAR_PER_TICK = 48;

    /** 正在分批清空的内饰区域：TARDIS id -> 剩余方块位置。 */
    private static final Map<UUID, Deque<BlockPos>> CLEAR_QUEUE = new HashMap<>();

    private TardisInteriorManager() {}

    public static void ensureInterior(MinecraftServer server, TardisData data) {
        ServerLevel level = TardisManager.getOrCreateInteriorLevel(server, data);
        if (level == null) return;

        if (data.interiorBuilt()) {
            if (data.isInteriorChanging()) {
                startInteriorSwap(server, data, level);
                return;
            }
            // (healAtStartup 已被移至 DoorManager，这里直接调用即可)
            TardisDoorManager.addSpareDoor(server, data, data.interiorPos());
            return;
        }
        TardisInteriorLoader.build(server, level, data);
    }

    public static boolean requestInteriorChange(MinecraftServer server, TardisData data, Identifier newId) {
        if (newId == null) return false;
        if (data.isInteriorChanging()) return false;
        if (!data.interiorBuilt()) return false;
        if (data.state() != TardisState.LANDED) return false;
        if (newId.equals(data.interiorId())) return false;

        Identifier templateId = interiorTemplateId(newId);
        if (server.getStructureTemplateManager().get(templateId).isEmpty()) return false;

        BlockPos[] region = interiorRegion(server, data.interiorId());
        data.beginInteriorChange(newId, region[0], region[1]);
        TardisManager.getRegistry(server).put(data);

        ServerLevel level = TardisManager.getInteriorLevel(server, data);
        if (level != null) {
            for (ServerPlayer p : level.players()) {
                p.sendSystemMessage(Component.translatable("doctor_m.tardis.interior.changing").withStyle(ChatFormatting.GOLD));
            }
        }
        return true;
    }

    public static void tickInteriorChange(MinecraftServer server, TardisData data) {
        ServerLevel level = TardisManager.getInteriorLevel(server, data);
        if (level == null) return;

        if (!level.players().isEmpty()) {
            if (data.interiorSwapDelay() > 0) data.setInteriorSwapDelay(0);
            return;
        }

        if (data.interiorSwapDelay() == 0) {
            TardisDoorManager.setDoorOpen(server, data, false);
            data.setInteriorSwapDelay(SWAP_LOCK_DELAY_TICKS);
            return;
        }

        data.setInteriorSwapDelay(data.interiorSwapDelay() - 1);
        if (data.interiorSwapDelay() > 0) return;

        // 分批清空旧内饰：不再单帧一次性 setBlock 整片区域
        startInteriorSwap(server, data, level);
    }

    /**
     * 启动内饰更换：把旧区域内非空气方块排进清空队列，逐 tick 批处理，
     * 队列空了再继续后续步骤（见 {@link #finishInteriorSwap}）。
     */
    private static void startInteriorSwap(MinecraftServer server, TardisData data, ServerLevel level) {
        Identifier newId = data.pendingInteriorId();
        if (newId == null) return;

        BlockPos min = data.swapClearMin();
        BlockPos max = data.swapClearMax();
        if (min == null || max == null) {
            BlockPos[] region = interiorRegion(server, data.interiorId());
            min = region[0];
            max = region[1];
        }

        Deque<BlockPos> queue = CLEAR_QUEUE.computeIfAbsent(data.id(), k -> new ArrayDeque<>());
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(p).isAir()) {
                queue.addLast(p);
            }
        }

        // 先落定“正在更换”状态，避免被当成已完成的内饰继续用
        data.setInteriorBuilt(false);
        TardisManager.getRegistry(server).put(data);

        if (queue.isEmpty()) {
            finishInteriorSwap(server, data, level);
        }
    }

    /** 每 tick 处理清空队列：每个 TARDIS 最多清 CLEAR_PER_TICK 个方块。 */
    public static void tickClearInterior(MinecraftServer server) {
        if (CLEAR_QUEUE.isEmpty()) return;

        for (var entry : new ArrayList<>(CLEAR_QUEUE.entrySet())) {
            UUID tardisId = entry.getKey();
            Deque<BlockPos> queue = entry.getValue();
            if (queue == null || queue.isEmpty()) {
                CLEAR_QUEUE.remove(tardisId);
                continue;
            }

            TardisData data = TardisManager.get(server, tardisId);
            if (data == null) {
                queue.clear();
                CLEAR_QUEUE.remove(tardisId);
                continue;
            }

            ServerLevel level = TardisManager.getInteriorLevel(server, data);
            if (level == null) {
                // 维度还没加载，稍后再试
                continue;
            }

            int budget = CLEAR_PER_TICK;
            while (budget-- > 0 && !queue.isEmpty()) {
                BlockPos p = queue.pollFirst();
                if (p == null) continue;
                if (!level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }

            if (queue.isEmpty()) {
                CLEAR_QUEUE.remove(tardisId);
                finishInteriorSwap(server, data, level);
            }
        }
    }

    private static void finishInteriorSwap(MinecraftServer server, TardisData data, ServerLevel level) {
        discardControlEntities(level);
        data.clearSpareDoors();

        Identifier newId = data.pendingInteriorId();
        data.setInteriorId(newId);
        data.endInteriorChange();
        TardisManager.getRegistry(server).put(data);

        TardisInteriorLoader.build(server, level, data);

        TardisDoorManager.setDoorOpen(server, data, true);
        TardisManager.getRegistry(server).put(data);

        ServerPlayer owner = data.owner() == null ? null : server.getPlayerList().getPlayer(data.owner());
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("doctor_m.tardis.interior.completed").withStyle(ChatFormatting.GOLD));
        }
    }

    private static BlockPos[] interiorRegion(MinecraftServer server, Identifier interiorId) {
        var template = server.getStructureTemplateManager().get(interiorTemplateId(interiorId)).orElse(null);
        if (template == null) {
            return new BlockPos[]{
                    new BlockPos(-CLEAR_FALLBACK, 0, -CLEAR_FALLBACK),
                    new BlockPos(CLEAR_FALLBACK, TardisInteriorLoader.BASE_Y + CLEAR_FALLBACK, CLEAR_FALLBACK)
            };
        }
        Vec3i size = template.getSize();
        BlockPos origin = new BlockPos(-size.getX() / 2, TardisInteriorLoader.BASE_Y, -size.getZ() / 2);
        return new BlockPos[]{
                origin,
                origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1)
        };
    }

    private static void clearRegion(ServerLevel level, BlockPos min, BlockPos max) {
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(p).isAir()) {
                // 静默清除：只同步客户端，不触发邻块更新，避免大面积更新卡顿
                level.setBlock(p, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    private static void discardControlEntities(ServerLevel level) {
        level.getEntities(EntityTypeTest.forClass(ConsoleControlEntity.class), c -> true).forEach(net.minecraft.world.entity.Entity::discard);
    }

    private static Identifier interiorTemplateId(Identifier interiorId) {
        return Identifier.fromNamespaceAndPath(interiorId.getNamespace(), "interior/" + interiorId.getPath());
    }
}