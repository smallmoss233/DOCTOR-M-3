package doctor_m.register;

import doctor_m.block.TardisConsoleBlock;
import doctor_m.block.TardisExteriorBlock;
import doctor_m.block.TardisInteriorDoorBlock;
import mosslib.api.AutoRegister;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

public final class DMBlocks {

    private DMBlocks() {}

    /** 外门：不生成 BlockItem，由 TardisSpawnerItem 统一生成。 */
    @AutoRegister.NoItem
    public static final Block TARDIS_EXTERIOR = AutoRegister.block("doctor_m", "tardis_exterior",
            props -> new TardisExteriorBlock(
                    props.mapColor(MapColor.METAL)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .lightLevel(s -> s.getValue(TardisExteriorBlock.OPEN) ? 7 : 0)
            )
    );

    /** 内门：允许玩家手动放置（在 TARDIS 维度内自动成为备用门）。 */
    public static final Block TARDIS_INTERIOR_DOOR = AutoRegister.block("doctor_m", "tardis_interior_door",
            props -> new TardisInteriorDoorBlock(
                    props.mapColor(MapColor.METAL)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .lightLevel(s -> s.getValue(TardisInteriorDoorBlock.OPEN) ? 7 : 0)
            )
    );

    /** 控制台：单方块，走 AutoRegister 工厂。 */
    public static final TardisConsoleBlock TARDIS_CONSOLE = (TardisConsoleBlock)
            AutoRegister.block("doctor_m", "tardis_console",
                    props -> new TardisConsoleBlock(
                            props.mapColor(MapColor.METAL)
                                    .strength(2.0f, 6.0f)
                                    .sound(SoundType.METAL)
                                    .noOcclusion()
                    )
            );

    public static void register() {
        AutoRegister.blocksWithItems(DMBlocks.class);
    }
}