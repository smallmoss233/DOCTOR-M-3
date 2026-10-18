package mosslib.core.bedrock;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public record BedrockModelRef(
        BedrockGeometryModel geometry,
        TextureAtlasSprite sprite
) {}