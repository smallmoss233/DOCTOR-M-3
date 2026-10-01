package doctor_m.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {

    @Accessor("serverChunkRadius")
    int doctor_m$getServerChunkRadius();

    @Accessor("serverSimulationDistance")
    int doctor_m$getServerSimulationDistance();
}