package doctor_m;

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

    public static void register() {
        AutoRegister.blocksWithItems(DMBlocks.class);   // 两个 @NoItem 会跳过 BlockItem
    }
}