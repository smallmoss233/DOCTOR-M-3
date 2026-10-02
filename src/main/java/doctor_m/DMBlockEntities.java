package doctor_m;

import doctor_m.block.entity.TardisDoorBlockEntity;
import mosslib.api.AutoRegister;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

public final class DMBlockEntities {

    private DMBlockEntities() {}

    public static final BlockEntityType<TardisDoorBlockEntity> TARDIS_DOOR =
            AutoRegister.blockEntity("doctor_m", "tardis_door",
                    new BlockEntityType<>(
                            TardisDoorBlockEntity::new,
                            Set.of(DMBlocks.TARDIS_EXTERIOR, DMBlocks.TARDIS_INTERIOR_DOOR)
                    ));

    public static void register() {
        AutoRegister.blockEntities(DMBlockEntities.class);
    }
}