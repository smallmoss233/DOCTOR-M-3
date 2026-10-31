package doctor_m.block.entity;

import doctor_m.block.TardisConsoleBlock;
import doctor_m.register.DMBlockEntities;
import doctor_m.tardis.console.ConsoleControlSync;
import doctor_m.tardis.console.control.ConsoleControlEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 控制台方块实体。
 *
 * <h2>控件同步：客户端算、服务端做</h2>
 * 几何数据（.geo.json）只在客户端资源包里，服务端拿不到。所以控件位置的
 * 唯一可靠来源是客户端：
 * <ol>
 *   <li><b>客户端</b>：读几何、遍历骨骼、算世界 AABB，通过
 *       {@code SyncConsoleControlsPayload} 发给服务端</li>
 *   <li><b>服务端</b>：收到后调 {@link #applyClientControls}，
 *       spawn / 更新 / 删除 {@link ConsoleControlEntity}</li>
 * </ol>
 *
 * <h2>为什么 BE 不能直接调客户端代码</h2>
 * 本类是双端类。服务端的 classpath 上没有 {@code BedrockCache} /
 * {@code ClientPlayNetworking} 等客户端类，直接引用会导致
 * {@code NoClassDefFoundError}。所以发送逻辑通过 {@link ClientSender}
 * 接口注入 —— 客户端在初始化时注册实现，服务端永远不注册。
 */
public class TardisConsoleBlockEntity extends BlockEntity {

    public static final Identifier DEFAULT_APPEARANCE =
            Identifier.fromNamespaceAndPath("doctor_m", "classic_console");

    private Identifier appearanceId = DEFAULT_APPEARANCE;
    private UUID tardisId;

    // =========================================================
    //                     状态（内存态，不存盘）
    // =========================================================

    /** 服务端：属于本 BE 的控件实体。 */
    private final List<ConsoleControlEntity> controls = new ArrayList<>();

    /** 客户端：上次发送的 FACING + appearanceId，用来判断是否需要重发。 */
    private Direction lastSentFacing = null;
    private Identifier lastSentAppearance = null;

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

    // =========================================================
    //           客户端发送钩子（服务端永远为 null）
    // =========================================================

    /**
     * 客户端发送实现。客户端在初始化时通过
     * {@link #setClientSender} 注入，服务端从不注入。
     */
    @FunctionalInterface
    public interface ClientSender {
        void send(TardisConsoleBlockEntity be);
    }

    private static ClientSender clientSender = null;

    /** 客户端初始化时调一次。 */
    public static void setClientSender(ClientSender sender) {
        clientSender = sender;
    }

    /** 客户端用：读上次发送的状态，决定是否需要重发。 */
    public Direction getLastSentFacing() { return lastSentFacing; }
    public Identifier getLastSentAppearance() { return lastSentAppearance; }

    /** 客户端用：更新发送状态。 */
    public void markSent(Direction facing, Identifier appearance) {
        this.lastSentFacing = facing;
        this.lastSentAppearance = appearance;
    }

    /** 客户端用：读当前 FACING。 */
    public Direction currentFacingPublic() { return currentFacing(); }

    // =========================================================
    //                      客户端 tick
    // =========================================================

    /**
     * 客户端每 tick。开销极低 —— 只比两个字段。
     *
     * <p>服务端不注册 {@link ClientSender}，这里直接返回，不触发任何
     * 客户端类加载。
     */
    public void clientTick() {
        if (level == null || !level.isClientSide()) return;
        if (clientSender == null) return;

        Direction current = currentFacing();
        boolean facingChanged = current != lastSentFacing;
        boolean appearanceChanged = !Objects.equals(appearanceId, lastSentAppearance);

        if (!facingChanged && !appearanceChanged) return;

        lastSentFacing = current;
        lastSentAppearance = appearanceId;

        clientSender.send(this);
    }

    // =========================================================
    //                      服务端 tick
    // =========================================================

    /** 服务端每 tick：清理已 removed 的引用。 */
    public void serverTick() {
        if (level == null || level.isClientSide()) return;
        controls.removeIf(ConsoleControlEntity::isRemoved);
    }

    // =========================================================
    //              服务端：应用客户端发来的 AABB
    // =========================================================

    /**
     * 服务端：处理客户端报告。
     *
     * <p>对账逻辑本身在 {@link ConsoleControlSync#reconcile} —— 本方法只做
     * 前置校验（必须是已放置的控制台、外观可用）后转发。
     */
    public void applyClientControls(List<doctor_m.network.SyncConsoleControlsPayload.Entry> entries) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!(getBlockState().getBlock() instanceof TardisConsoleBlock)) return;

        // 不需要外观注册表：客户端把每个控件的配置一起发过来了（专用服务器也适用）。
        ConsoleControlSync.reconcile(sl, getBlockPos(), controls, entries);
    }

    private Direction currentFacing() {
        BlockState s = getBlockState();
        if (s.hasProperty(TardisConsoleBlock.FACING)) {
            return s.getValue(TardisConsoleBlock.FACING);
        }
        return Direction.NORTH;
    }

    // =========================================================
    //                      NBT
    // =========================================================

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

    // =========================================================
    //                      同步
    // =========================================================

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
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 2);
        }
    }
}