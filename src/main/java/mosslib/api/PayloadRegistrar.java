package mosslib.api;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络包注册器。
 * <p>构造时指定命名空间，{@link #c2s}/{@link #s2c} 用相对路径。
 */
public final class PayloadRegistrar {

    private final String namespace;
    private final List<Runnable> tasks = new ArrayList<>();

    public PayloadRegistrar(String namespace) {
        this.namespace = namespace;
    }

    /** 声明一个 C2S payload 类型。 */
    public <T extends CustomPacketPayload> Type<T> c2s(
            String path, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        Type<T> type = new Type<>(Identifier.fromNamespaceAndPath(namespace, path));
        tasks.add(() -> PayloadTypeRegistry.serverboundPlay().register(type, codec));
        return type;
    }

    /** 声明一个 S2C payload 类型。 */
    public <T extends CustomPacketPayload> Type<T> s2c(
            String path, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        Type<T> type = new Type<>(Identifier.fromNamespaceAndPath(namespace, path));
        tasks.add(() -> PayloadTypeRegistry.clientboundPlay().register(type, codec));
        return type;
    }

    /** 执行所有注册。 */
    public void commit() {
        for (Runnable task : tasks) task.run();
        tasks.clear();
    }
}