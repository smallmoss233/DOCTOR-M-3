package mosslib.core.bedrock;

import mosslib.core.bedrock.BedrockGeometryModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public record BedrockModelRef(
        BedrockGeometryModel geometry,
        TextureAtlasSprite sprite
) {}