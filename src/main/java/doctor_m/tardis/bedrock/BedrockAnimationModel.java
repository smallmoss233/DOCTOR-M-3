package doctor_m.tardis.bedrock;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

public record BedrockAnimationModel(Map<String, Animation> byName) {
    public record Animation(float length, boolean loop, Map<String, BoneTracks> bones) {}
    public record BoneTracks(List<Keyframe> rotation,
                             List<Keyframe> position,
                             List<Keyframe> scale) {}
    public record Keyframe(float time, Vector3f value) {}
}