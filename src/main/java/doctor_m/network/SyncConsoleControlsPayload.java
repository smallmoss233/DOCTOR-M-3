package doctor_m.network;

import doctor_m.tardis.console.control.ControlConfig;
import doctor_m.tardis.console.control.ControlType;
import doctor_m.tardis.console.control.ControlTypes;
import doctor_m.tardis.console.control.TierMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：报告某个控制台方块的所有控件。
 *
 * <p>客户端有几何（能读资源包 .geo.json），服务端没有。所以由客户端算好
 * 每个控件骨骼的世界坐标包围盒发给服务端，服务端据此生成 / 更新控件实体。
 *
 * <h2>为什么配置也一起发</h2>
 * 专用服务器没有客户端资源包，{@code TardisConsoleRegistry} 在服务端是空的。
 * 但控件点击处理（挡位数、到顶行为、动画时长）全都需要 {@link ControlConfig}。
 * 客户端本来就在同一次计算里拿到了配置，顺手发过去，服务端就不必依赖客户端资产，
 * 挡位与动画时长也不会因为两边解析路径不同而漂移。
 */
public record SyncConsoleControlsPayload(
        BlockPos consolePos,
        List<Entry> entries
) implements CustomPacketPayload {

    /**
     * 单个控件：骨骼名 + 世界坐标 AABB + 完整控件配置。
     *
     * @param animationId 动画片段名，可为 null
     */
    public record Entry(String boneName, AABB box,
                        String typeId, int tiers, float duration,
                        String tierMode, @Nullable String animationId) {

        /** 还原成服务端可用的配置；类型未注册时返回 null。 */
        public @Nullable ControlConfig toConfig() {
            ControlType type = ControlTypes.byId(typeId);
            if (type == null) return null;

            TierMode mode;
            try {
                mode = tierMode == null || tierMode.isBlank()
                        ? type.defaultTierMode()
                        : TierMode.valueOf(tierMode);
            } catch (IllegalArgumentException e) {
                mode = type.defaultTierMode();
            }

            return new ControlConfig(type, tiers, duration, mode,
                    animationId == null || animationId.isBlank() ? null : animationId);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncConsoleControlsPayload> CODEC =
            StreamCodec.of(SyncConsoleControlsPayload::write, SyncConsoleControlsPayload::read);

    // =========================================================
    //                      StreamCodec 实现
    // =========================================================

    private static void write(RegistryFriendlyByteBuf buf, SyncConsoleControlsPayload p) {
        buf.writeBlockPos(p.consolePos());
        buf.writeVarInt(p.entries().size());
        for (Entry e : p.entries()) {
            buf.writeUtf(e.boneName());
            buf.writeUtf(e.typeId() == null ? "" : e.typeId());
            buf.writeVarInt(e.tiers());
            buf.writeFloat(e.duration());
            buf.writeUtf(e.tierMode() == null ? "" : e.tierMode());
            buf.writeUtf(e.animationId() == null ? "" : e.animationId());

            AABB b = e.box();
            buf.writeDouble(b.minX);
            buf.writeDouble(b.minY);
            buf.writeDouble(b.minZ);
            buf.writeDouble(b.maxX);
            buf.writeDouble(b.maxY);
            buf.writeDouble(b.maxZ);
        }
    }

    private static SyncConsoleControlsPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String bone = buf.readUtf();
            String typeId = emptyToNull(buf.readUtf());
            int tiers = buf.readVarInt();
            float duration = buf.readFloat();
            String tierMode = emptyToNull(buf.readUtf());
            String animationId = emptyToNull(buf.readUtf());

            double minX = buf.readDouble();
            double minY = buf.readDouble();
            double minZ = buf.readDouble();
            double maxX = buf.readDouble();
            double maxY = buf.readDouble();
            double maxZ = buf.readDouble();

            entries.add(new Entry(bone, new AABB(minX, minY, minZ, maxX, maxY, maxZ),
                    typeId, tiers, duration, tierMode, animationId));
        }
        return new SyncConsoleControlsPayload(pos, List.copyOf(entries));
    }

    private static @Nullable String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("doctor_m", "sync_console_controls");

    public static final CustomPacketPayload.Type<SyncConsoleControlsPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    // =========================================================
    //                      CustomPacketPayload
    // =========================================================

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
