package doctor_m.client.tardis.render;

import mosslib.core.render.BedrockModelRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.Map;

public class TardisConsoleRenderState extends BlockEntityRenderState {
    public Identifier appearanceId;
    public Direction facing = Direction.NORTH;
    public float idleElapsedSec;
    public Map<String, BedrockModelRenderer.BoneOverride> controlOverrides = Map.of();
}