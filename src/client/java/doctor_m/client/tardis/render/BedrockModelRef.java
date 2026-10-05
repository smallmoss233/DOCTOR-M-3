package doctor_m.client.tardis.render;

import doctor_m.client.tardis.bedrock.BedrockGeometryModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public record BedrockModelRef(
        BedrockGeometryModel geometry,
        TextureAtlasSprite sprite
) {}