package doctor_m.block.entity;

import doctor_m.register.DMBlockEntities;
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

/**
 * 控制台方块实体。当前只存 appearanceId + tardisId。
 * 之后会加 controlEntityIds: List<UUID>。
 */
public class TardisConsoleBlockEntity extends BlockEntity {

    public static final Identifier DEFAULT_APPEARANCE =
            Identifier.fromNamespaceAndPath("doctor_m", "classic_console");

    private Identifier appearanceId = DEFAULT_APPEARANCE;
    private UUID tardisId;

    public TardisConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(DMBlockEntities.TARDIS_CONSOLE, pos, state);
    }

    // ---- getter / setter ----

    public Identifier getAppearanceId() { return appearanceId; }

    public void setAppearanceId(Identifier id) {
        Identifier next = id == null ? DEFAULT_APPEARANCE : id;
        if (next.equals(appearanceId)) return;
        this.appearanceId = next;
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

    // ---- NBT ----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (appearanceId != null) output.putString("appearance", appearanceId.toString());
        if (tardisId != null)      output.putString("tardis_id", tardisId.toString());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        String app = input.getStringOr("appearance", "");
        if (!app.isEmpty()) {
            Identifier parsed = Identifier.tryParse(app);
            if (parsed != null) appearanceId = parsed;
        }

        String tid = input.getStringOr("tardis_id", "");
        if (!tid.isEmpty()) {
            try { tardisId = UUID.fromString(tid); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    // ---- 同步 ----

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (appearanceId != null) tag.putString("appearance", appearanceId.toString());
        if (tardisId != null)      tag.putString("tardis_id", tardisId.toString());
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