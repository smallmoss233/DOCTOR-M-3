package doctor_m.client.tardis.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

public class TardisConsoleRenderState extends BlockEntityRenderState {
    public Identifier appearanceId;
    public Direction facing = Direction.NORTH;
    public float idleElapsedSec;
}