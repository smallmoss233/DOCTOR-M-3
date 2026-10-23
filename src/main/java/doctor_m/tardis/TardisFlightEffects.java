package doctor_m.tardis;

import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.register.DMBlocks;
import doctor_m.tardis.data.TardisData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 飞行过程中对**世界**产生的可见副作用：外壳门的淡出 / 移除 / 放置，以及音效。
 *
 * <h2>为什么单独成类</h2>
 * {@link TardisFlight} 关心的是"什么时候该做什么"（状态机），本类关心的是
 * "做出来看起来/听起来是什么"（方块与声音）。把两者分开之后，
 * 状态推进可以独立阅读，特效也可以单独调整而不碰时序。
 *
 * <h2>静默替换</h2>
 * 拆壳时用 {@link #SILENT_FLAGS}：只把变化同步给客户端，<b>不触发邻块更新</b>。
 * 否则一次拆壳会让周围的方块产生连锁更新，既卡顿又会误触发其他逻辑。
 */
public final class TardisFlightEffects {

    private TardisFlightEffects() {}

    /** 静默替换方块：只同步给客户端，不触发邻块更新。 */
    private static final int SILENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // =========================================================
    //                      外壳门
    // =========================================================

    /** 起飞中：让外壳开始淡出。方块本身还在，只是透明度下降。 */
    public static void startFadeOut(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;
        if (level.getBlockEntity(data.exteriorPos()) instanceof TardisDoorBlockEntity be) {
            be.startFadeOut();
        }
    }

    /** 淡出结束：把外壳方块静默替换为空气。 */
    public static void removeExterior(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        if (level.getBlockState(lower).getBlock() instanceof AbstractTardisDoorBlock) {
            level.setBlock(lower, Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
        }
        if (level.getBlockState(lower.above()).getBlock() instanceof AbstractTardisDoorBlock) {
            level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
        }
    }

    /**
     * 降落/坠机开始时：在（已结算的）落点放置外壳门，并启动淡入。
     *
     * <p>提前放置是为了让门在 20 秒的 MAT 音效期间逐渐显形，而不是音效播完才突然出现。
     */
    public static void placeExterior(MinecraftServer server, TardisData data) {
        ServerLevel level = server.getLevel(data.exteriorDim());
        if (level == null) return;

        BlockPos lower = data.exteriorPos();
        Direction facing = data.exteriorFacing();

        BlockState lowerState = DMBlocks.TARDIS_EXTERIOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, facing)
                .setValue(AbstractTardisDoorBlock.OPEN, false);

        level.setBlock(lower, lowerState, 3);
        level.setBlock(lower.above(),
                lowerState.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        if (level.getBlockEntity(lower) instanceof TardisDoorBlockEntity be) {
            be.setTardisId(data.id());
            be.setAppearance(data.appearanceId());
            be.setExterior(true);
            be.startFadeIn();
        }
    }

    // =========================================================
    //                      音效
    // =========================================================

    /**
     * 在 TARDIS 的内外两侧同时播放音效。
     *
     * <p>用 {@code exteriorPos} 而非"外壳方块是否存在"决定外声位置 —— 起飞拆壳之后
     * 方块已经不在了，但音效还要继续跟着 TARDIS 的实际坐标走。
     */
    public static void playSound(MinecraftServer server, TardisData data,
                                 SoundEvent sound, float volume, float pitch) {
        ServerLevel interior = TardisManager.getInteriorLevel(server, data);
        if (interior != null) {
            BlockPos c = data.interiorPos();
            interior.playSound(null, c.getX() + 0.5, c.getY() + 1.0, c.getZ() + 0.5,
                    sound, SoundSource.BLOCKS, volume, pitch);
        }

        if (data.exteriorDim() != null && data.exteriorPos() != null) {
            ServerLevel exterior = server.getLevel(data.exteriorDim());
            if (exterior != null) {
                BlockPos c = data.exteriorPos();
                exterior.playSound(null, c.getX() + 0.5, c.getY() + 1.0, c.getZ() + 0.5,
                        sound, SoundSource.BLOCKS, volume, pitch);
            }
        }
    }
}
