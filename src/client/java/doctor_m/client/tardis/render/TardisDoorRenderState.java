package doctor_m.client.tardis.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

public class TardisDoorRenderState extends BlockEntityRenderState {
    public Identifier appearanceId;
    public boolean open;
    public boolean exterior;
    public Direction facing = Direction.NORTH;
    public float openProgress = 0.0f;
    public float animElapsedSec;
    public boolean animTarget;
    public float fadeAlpha = 1.0f;
    public boolean renderThis = true;   // ← 新增
}