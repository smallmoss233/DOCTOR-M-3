package doctor_m.register;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class DMCreativeTabs {

    private DMCreativeTabs() {}

    public static final ResourceKey<CreativeModeTab> MAIN =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.fromNamespaceAndPath("doctor_m", "main"));

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN,
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                        .title(Component.translatable("itemGroup.doctor_m.main"))
                        .icon(() -> new ItemStack(DMItems.TARDIS_SPAWNER))
                        .displayItems((params, output) -> {
                            output.accept(DMItems.TARDIS_SPAWNER);
                            output.accept(DMBlocks.TARDIS_INTERIOR_DOOR.asItem());
                        })
                        .build()
        );
    }
}