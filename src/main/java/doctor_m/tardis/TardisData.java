package doctor_m.tardis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TardisData {

    private final UUID id;
    /** 创建者；旧数据可能没有，用 null 表示未知。 */
    private final UUID owner;
    private ResourceKey<Level> exteriorDim;
    private BlockPos exteriorPos;
    private Direction exteriorFacing;
    private BlockPos interiorPos;
    private Direction interiorFacing;
    private final List<BlockPos> spareDoors = new ArrayList<>();

    // ============ 兼容用构造器（owner = null） ============
    public TardisData(UUID id,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing) {
        this(id, null, exteriorDim, exteriorPos, exteriorFacing, interiorPos, interiorFacing, List.of());
    }
    public TardisData(UUID id,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing,
                      List<BlockPos> spareDoors) {
        this(id, null, exteriorDim, exteriorPos, exteriorFacing, interiorPos, interiorFacing, spareDoors);
    }

    // ============ 完整构造器 ============
    public TardisData(UUID id, UUID owner,
                      ResourceKey<Level> exteriorDim, BlockPos exteriorPos, Direction exteriorFacing,
                      BlockPos interiorPos, Direction interiorFacing,
                      List<BlockPos> spareDoors) {
        this.id = id;
        this.owner = owner;
        this.exteriorDim = exteriorDim;
        this.exteriorPos = exteriorPos;
        this.exteriorFacing = exteriorFacing;
        this.interiorPos = interiorPos;
        this.interiorFacing = interiorFacing;
        this.spareDoors.addAll(spareDoors);
    }

    public static final Codec<TardisData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(TardisData::id),
            UUIDUtil.CODEC.optionalFieldOf("owner", null).forGetter(TardisData::owner),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("exterior_dim").forGetter(TardisData::exteriorDim),
            BlockPos.CODEC.fieldOf("exterior_pos").forGetter(TardisData::exteriorPos),
            Direction.CODEC.fieldOf("exterior_facing").forGetter(TardisData::exteriorFacing),
            BlockPos.CODEC.fieldOf("interior_pos").forGetter(TardisData::interiorPos),
            Direction.CODEC.fieldOf("interior_facing").forGetter(TardisData::interiorFacing),
            BlockPos.CODEC.listOf().optionalFieldOf("spare_doors", List.of())
                    .forGetter(TardisData::spareDoors)
    ).apply(inst, TardisData::new));

    // ---- getter ----
    public UUID id()                        { return id; }
    public UUID owner()                     { return owner; }
    public ResourceKey<Level> exteriorDim() { return exteriorDim; }
    public BlockPos exteriorPos()           { return exteriorPos; }
    public Direction exteriorFacing()       { return exteriorFacing; }
    public BlockPos interiorPos()           { return interiorPos; }
    public Direction interiorFacing()       { return interiorFacing; }
    public List<BlockPos> spareDoors()      { return List.copyOf(spareDoors); }

    // ---- setter ----
    public void setExterior(ResourceKey<Level> dim, BlockPos pos, Direction facing) {
        this.exteriorDim = dim; this.exteriorPos = pos; this.exteriorFacing = facing;
    }
    public void setInterior(BlockPos pos, Direction facing) {
        this.interiorPos = pos; this.interiorFacing = facing;
    }
    public void addSpareDoor(BlockPos pos) {
        if (!spareDoors.contains(pos)) spareDoors.add(pos);
    }
    public boolean removeSpareDoor(BlockPos pos) {
        return spareDoors.remove(pos);
    }
    public BlockPos popSpareDoor() {
        return spareDoors.isEmpty() ? null : spareDoors.remove(0);
    }
}