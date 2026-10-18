package doctor_m.tardis.appearance;

import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

public sealed interface TardisAsset {

    /** 基岩版：geometry + animation + texture，开关是动画名 */
    record Bedrock(
            Identifier geometry,
            Identifier animation,
            Identifier texture,
            String openAnimation,
            String closeAnimation,
            Vector3f offset,
            float scale
    ) implements TardisAsset {
        public Bedrock(Identifier geometry, Identifier animation, Identifier texture,
                       String openAnimation, String closeAnimation,
                       float x, float y, float z, float scale) {
            this(geometry, animation, texture, openAnimation, closeAnimation, new Vector3f(x, y, z), scale);
        }
    }
}