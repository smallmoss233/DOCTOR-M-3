package doctor_m.client.gui.pip;

import doctor_m.tardis.bedrock.BedrockGeometryModel;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

/**
 * 在 GUI 里渲染 Bedrock 模型的 PiP 状态。
 *
 * <p>yawDegrees 会在渲染时绕模型中心旋转——每帧变一点就是自转。
 */
public record GuiBedrockModelRenderState(
        BedrockGeometryModel geometry,
        TextureAtlasSprite sprite,
        float yawDegrees,
        int x0, int y0, int x1, int y1,
        float scale,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {

    public GuiBedrockModelRenderState(BedrockGeometryModel geometry,
                                      TextureAtlasSprite sprite,
                                      float yawDegrees,
                                      int x0, int y0, int x1, int y1,
                                      float scale,
                                      @Nullable ScreenRectangle scissorArea) {
        this(geometry, sprite, yawDegrees, x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}