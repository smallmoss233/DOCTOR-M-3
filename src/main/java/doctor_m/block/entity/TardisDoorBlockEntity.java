package doctor_m.block.entity;

import doctor_m.DMBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

public class TardisDoorBlockEntity extends BlockEntity {

    private Identifier appearanceId = null;
    private UUID tardisId = null;
    private boolean open = false;
    private boolean exterior = false;

    public TardisDoorBlockEntity(BlockPos pos, BlockState state) {
        super(DMBlockEntities.TARDIS_DOOR, pos, state);
    }

    // ---- getter / setter ----

    public Identifier getAppearance() { return appearanceId; }

    public void setAppearance(Identifier id) {
        if (id == null ? appearanceId == null : id.equals(appearanceId)) return;
        this.appearanceId = id;
        setChanged();
        syncToClient();
    }

    public UUID getTardisId() { return tardisId; }

    public void setTardisId(UUID id) {
        if (id == null ? tardisId == null : id.equals(tardisId)) return;
        this.tardisId = id;
        setChanged();
        syncToClient();
    }

    public boolean isOpen() { return open; }

    public void setOpen(boolean v) {
        if (this.open == v) return;
        this.open = v;
        setChanged();
        syncToClient();
    }

    public boolean isExterior() { return exterior; }

    public void setExterior(boolean v) {
        if (this.exterior == v) return;
        this.exterior = v;
        setChanged();
        syncToClient();
    }

    // ---- NBT ----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (appearanceId != null) output.putString("appearance", appearanceId.toString());
        if (tardisId != null)      output.putString("tardis_id", tardisId.toString());
        output.putBoolean("open", open);
        output.putBoolean("exterior", exterior);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        String app = input.getStringOr("appearance", "");
        if (!app.isEmpty()) appearanceId = Identifier.tryParse(app);

        String tid = input.getStringOr("tardis_id", "");
        if (!tid.isEmpty()) {
            try { tardisId = UUID.fromString(tid); }
            catch (IllegalArgumentException ignored) {}
        }

        open = input.getBooleanOr("open", false);
        exterior = input.getBooleanOr("exterior", false);
    }

    // ---- 同步 ----

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (appearanceId != null) tag.putString("appearance", appearanceId.toString());
        if (tardisId != null)      tag.putString("tardis_id", tardisId.toString());
        tag.putBoolean("open", open);
        tag.putBoolean("exterior", exterior);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }
}