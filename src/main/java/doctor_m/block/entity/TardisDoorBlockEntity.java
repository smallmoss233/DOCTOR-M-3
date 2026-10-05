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

public class TardisDoorBlockEntity extends BlockEntity {

    /** 淡入 / 淡出时长（tick）。 */
    public static final int FADE_DURATION = 60;

    private Identifier appearanceId = null;
    private UUID tardisId = null;
    private boolean open = false;
    private boolean exterior = false;

    // 淡入淡出状态
    private int fadeTicks = 0;
    private float fadeFrom = 1f;
    private float fadeTo = 1f;

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

    // ---- 淡入淡出 ----

    public int getFadeTicks() { return fadeTicks; }

    /** 0~1，1 = 完全不透明，0 = 完全透明。 */
    public float getFadeAlpha() {
        if (fadeTicks <= 0) return fadeTo;
        float t = 1f - (fadeTicks / (float) FADE_DURATION);
        return fadeFrom + (fadeTo - fadeFrom) * t;
    }

    /** 淡出：1 → 0。 */
    public void startFadeOut() {
        this.fadeFrom = 1f;
        this.fadeTo = 0f;
        this.fadeTicks = FADE_DURATION;
        setChanged();
        syncToClient();
    }

    /** 淡入：0 → 1。 */
    public void startFadeIn() {
        this.fadeFrom = 0f;
        this.fadeTo = 1f;
        this.fadeTicks = FADE_DURATION;
        setChanged();
        syncToClient();
    }

    /** 取消过渡，停在完全不透明。 */
    public void clearFade() {
        this.fadeFrom = 1f;
        this.fadeTo = 1f;
        this.fadeTicks = 0;
        setChanged();
        syncToClient();
    }

    /** 客户端每 tick 调用一次。 */
    public void clientTick() {
        if (fadeTicks > 0) {
            fadeTicks--;
        }
    }

    // ---- NBT（存盘） ----

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (appearanceId != null) output.putString("appearance", appearanceId.toString());
        if (tardisId != null)      output.putString("tardis_id", tardisId.toString());
        output.putBoolean("open", open);
        output.putBoolean("exterior", exterior);
        // fade 状态不存盘 —— 临时状态
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

        // ★ 客户端从 update tag 读到 fade 状态；磁盘加载时这些 key 不存在，用默认值
        fadeTicks = input.getIntOr("fade_ticks", 0);
        fadeFrom  = input.getFloatOr("fade_from", 1f);
        fadeTo    = input.getFloatOr("fade_to", 1f);
    }

    // ---- 同步（网络） ----

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (appearanceId != null) tag.putString("appearance", appearanceId.toString());
        if (tardisId != null)      tag.putString("tardis_id", tardisId.toString());
        tag.putBoolean("open", open);
        tag.putBoolean("exterior", exterior);
        tag.putInt("fade_ticks", fadeTicks);
        tag.putFloat("fade_from", fadeFrom);
        tag.putFloat("fade_to", fadeTo);
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