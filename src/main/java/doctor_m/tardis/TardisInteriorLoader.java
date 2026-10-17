package doctor_m.tardis;

import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.register.DMBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.util.RandomSource;

import org.jetbrains.annotations.Nullable;

/**
 * TARDIS 内饰加载器：从 NBT 结构模板生成内饰，替代旧的程序生成房间。
 *
 * <p>每个塔迪斯持有一个内饰 ID（{@link TardisData#interiorId()}，默认
 * {@code doctor_m:classic_interior}）。生成时把 ID 的路径加上
 * {@code interior/} 前缀交给 {@link StructureTemplateManager}，从数据包
 * 加载对应模板并放进 TARDIS 私人维度——模板文件位于
 * {@code data/<ns>/structure/interior/<path>.nbt}，例如默认内饰对应
 * {@code data/doctor_m/structure/interior/classic_interior.nbt}。
 *
 * <p>{@link #build} 的流程：
 * <ol>
 *   <li>加载模板；缺失则内门落点回退 {@code (0, 64, 0)}、照常解锁
 *       （返回 {@code false}，不让塔迪斯永久锁死）</li>
 *   <li>以 {@code (0, 64, 0)} 为中心整体放置模板（一次同步放置）</li>
 *   <li>扫描放置区域，找到模板里的内门方块（{@code TARDIS_INTERIOR_DOOR}
 *       下半块）——它的坐标/朝向写入 {@link TardisData#setInterior}，门 BE
 *       经 {@link TardisManager#syncDoorAppearance} 重新绑定到当前塔迪斯
 *       （模板里保存的旧 BE 数据会被覆盖）</li>
 *   <li>标记 {@code interiorBuilt = true} 并持久化：外门只有在此之后才
 *       允许打开（物品/指令生成序列的门禁），{@code teleportInto} 同样
 *       以该标记拒绝进入</li>
 * </ol>
 *
 * <p>注意：这里<b>没有</b>自愈机制。模板里没有内门就是设计如此，不会
 * 自动补门（返回 {@code false}，落点用回退值）；旧存档方块持久存在，
 * 只有 {@code interiorBuilt == false}（上次生成被中断）才会重走本流程。
 *
 * <p>做新内饰：结构方块 SAVE 导出 {@code .nbt}，放进数据包
 * {@code data/<ns>/structure/interior/}，模板里放一个内门方块即可；
 * 门 BE 的旧塔迪斯数据不必清理，放置后统一重新绑定。
 */
public final class TardisInteriorLoader {

    private TardisInteriorLoader() {}

    public static final int BASE_Y = 64;
    private static final BlockPos FALLBACK_INTERIOR = new BlockPos(0, BASE_Y, 0);

    public static boolean build(MinecraftServer server, ServerLevel level, TardisData data) {
        Identifier id = data.interiorId();

        Identifier structurePath = Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "interior/" + id.getPath()
        );

        StructureTemplateManager templates = server.getStructureTemplateManager();
        @Nullable StructureTemplate template = templates.get(structurePath).orElse(null);

        if (template == null) {
            data.setInterior(FALLBACK_INTERIOR, Direction.NORTH);
            data.setInteriorBuilt(true);
            TardisManager.getRegistry(server).put(data);
            return false;
        }

        Vec3i size = template.getSize();
        BlockPos origin = new BlockPos(
                -size.getX() / 2, BASE_Y, -size.getZ() / 2);

        StructurePlaceSettings settings = new StructurePlaceSettings();

        RandomSource random = level.getRandom();
        template.placeInWorld(level, origin, origin, settings, random, 2 | 16);

        BlockPos max = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos p : BlockPos.betweenClosed(origin, max)) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() != DMBlocks.TARDIS_INTERIOR_DOOR) continue;
            if (s.getValue(AbstractTardisDoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;

            Direction facing = s.hasProperty(AbstractTardisDoorBlock.FACING)
                    ? s.getValue(AbstractTardisDoorBlock.FACING) : Direction.NORTH;
            data.setInterior(p.immutable(), facing);
            data.setInteriorBuilt(true);
            TardisManager.getRegistry(server).put(data);
            TardisManager.syncDoorAppearance(server, data);
            return true;
        }

        data.setInterior(FALLBACK_INTERIOR, Direction.NORTH);
        data.setInteriorBuilt(true);
        TardisManager.getRegistry(server).put(data);
        return false;
    }
}