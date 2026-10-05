package doctor_m.register;

import doctor_m.item.TardisSpawnerItem;
import mosslib.api.AutoRegister;
import net.minecraft.world.item.Item;

public final class DMItems {

    private DMItems() {}

    public static final Item TARDIS_SPAWNER = AutoRegister.item("doctor_m", "tardis_spawner",
            props -> new TardisSpawnerItem(props.stacksTo(1))
    );

    public static void register() {
        AutoRegister.items(DMItems.class);
    }
}