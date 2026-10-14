package doctor_m.network;

import doctor_m.network.DMNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：报告某个控制台方块的所有控件世界 AABB。
 *
 * <p>客户端有几何（能读资源包 .geo.json），服务端没有。所以由客户端算好
 * 每个控件骨骼的世界坐标包围盒发给服务端，服务端据此生成 / 更新控件实体。
 *
 * <p>类型注册与 handler 挂在 {@code DMNetwork} 里 —— 这个类只定义数据与 codec。
 */
public record SyncConsoleControlsPayload(
        BlockPos consolePos,
        List<Entry> entries
) implements CustomPacketPayload {

    /** 单个控件：骨骼名 + 世界坐标 AABB。 */
    public record Entry(String boneName, AABB box) {}

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
            AABB b = e.box();
            buf.writeDouble(b.minX);
            buf.writeDouble(b.minY);
            buf.writeDouble(b.minZ);
            buf.writeDouble(b.maxX);
            buf.writeDouble(b.maxY);
            buf.writeDouble(b.maxZ);
        }
    }

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("doctor_m", "sync_console_controls");

    public static final CustomPacketPayload.Type<SyncConsoleControlsPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private static SyncConsoleControlsPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String bone = buf.readUtf();
            double minX = buf.readDouble();
            double minY = buf.readDouble();
            double minZ = buf.readDouble();
            double maxX = buf.readDouble();
            double maxY = buf.readDouble();
            double maxZ = buf.readDouble();
            entries.add(new Entry(bone, new AABB(minX, minY, minZ, maxX, maxY, maxZ)));
        }
        return new SyncConsoleControlsPayload(pos, List.copyOf(entries));
    }

    // =========================================================
    //                      CustomPacketPayload
    // =========================================================

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}