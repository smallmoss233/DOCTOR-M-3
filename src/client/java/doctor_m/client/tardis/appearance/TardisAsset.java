package doctor_m.client.tardis.appearance;

import net.minecraft.resources.Identifier;

public sealed interface TardisAsset {

    /** 基岩版：geometry + animation + texture，开关是动画名 */
    record Bedrock(
            Identifier geometry,
            Identifier animation,
            Identifier texture,
            String openAnimation,
            String closeAnimation,
            float offsetX, float offsetY, float offsetZ,  // 格
            float scale
    ) implements TardisAsset {}
}