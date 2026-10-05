package doctor_m.item;

import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.register.DMBlocks;
import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * TARDIS 生成器。
 * <p>右键点击一个方块面：
 * <ol>
 *   <li>在点击面的相邻格生成一台全新 TARDIS（随机 UUID）</li>
 *   <li>创建对应维度</li>
 *   <li>在维度内放置内门</li>
 *   <li>在外部放置外门</li>
 * </ol>
 * 物品在创造模式不消耗，生存模式一次性消耗。
 */
public class TardisSpawnerItem extends Item {

    public TardisSpawnerItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        MinecraftServer server = level.getServer();
        if (server == null) return InteractionResult.FAIL;

        // 目标位置 = 点击方块的相邻面
        BlockPos target = ctx.getClickedPos().relative(ctx.getClickedFace());

        // 位置校验：两格都要能放
        if (!level.getBlockState(target).canBeReplaced()) return InteractionResult.FAIL;
        if (!level.getBlockState(target.above()).canBeReplaced()) return InteractionResult.FAIL;

        // 这个位置已经绑定过 TARDIS？
        if (TardisManager.findByExterior(server, level.dimension(), target) != null) {
            player.sendOverlayMessage(Component.translatable("item.doctor_m.tardis_spawner.occupied"));
            return InteractionResult.FAIL;
        }

        Direction facing = ctx.getHorizontalDirection().getOpposite();

        // 1) 创建 TARDIS（UUID + 数据 + 维度 + 内门）
        TardisData data = TardisManager.createNewTardis(server, player.getUUID(),
                level.dimension(), target, facing);
        if (data == null) return InteractionResult.FAIL;

        // 2) 放置外门
        BlockState lower = DMBlocks.TARDIS_EXTERIOR.defaultBlockState()
                .setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(AbstractTardisDoorBlock.FACING, facing)
                .setValue(AbstractTardisDoorBlock.OPEN, true);

        level.setBlock(target, lower, 3);
        level.setBlock(target.above(),
                lower.setValue(AbstractTardisDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        // ★ 外门 BE 刚创建，写入外观 + TARDIS ID
        TardisManager.syncDoorAppearance(server, data);

        // 3) 音效
        level.playSound(null, target, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.BLOCKS, 1.0f, 0.8f);

        // 4) 消耗物品
        if (!player.isCreative()) {
            ctx.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}