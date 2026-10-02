package doctor_m.block;

import doctor_m.tardis.TardisData;
import doctor_m.tardis.TardisManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class TardisInteriorDoorBlock extends AbstractTardisDoorBlock {

    public TardisInteriorDoorBlock(Properties props) {
        super(props);
    }

    @Override
    protected TardisData findTardis(MinecraftServer server, ServerLevel level, BlockPos pos) {
        // findByInterior 只匹配真门位置，备用门返回 null → 不响应右键
        return TardisManager.findByInterior(server, level.dimension(), pos);
    }

    @Override
    protected void handlePassThrough(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        TardisData data = TardisManager.findByInterior(server, level.dimension(), pos);
        if (data == null) return;
        TardisManager.teleportOut(player, data);
    }

    @Override
    protected void onPlacedByPlayer(Level level, BlockPos pos, BlockState state,
                                    LivingEntity placer, ItemStack stack) {
        if (!(level instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;

        UUID id = TardisManager.tardisIdFromDimension(sl.dimension());
        if (id == null) {
            // 不在 TARDIS 维度 → 拆掉
            sl.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            sl.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
            if (placer instanceof ServerPlayer sp) {
                sp.sendOverlayMessage(
                        Component.translatable("block.doctor_m.tardis_interior_door.not_in_tardis"));
            }
            return;
        }

        TardisData data = TardisManager.get(server, id);
        if (data == null) return;

        TardisManager.addSpareDoor(server, data, pos);
    }

    @Override
    protected void onLowerRemoved(ServerLevel level, BlockPos pos) {
        UUID id = TardisManager.tardisIdFromDimension(level.dimension());
        if (id == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;

        TardisData data = TardisManager.get(server, id);
        if (data == null) return;

        if (pos.equals(data.interiorPos())) {
            TardisManager.promoteSpareDoor(level, data);
        } else {
            TardisManager.removeSpareDoor(server, data, pos);
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}