package doctor_m.client.tardis.bedrock;

import org.joml.Vector3f;
import java.util.*;

public record BedrockAnimationModel(Map<String, Animation> byName) {
    public record Animation(float length, boolean loop, Map<String, BoneTracks> bones) {}
    public record BoneTracks(List<Keyframe> rotation, List<Keyframe> position, List<Keyframe> scale) {}
    public record Keyframe(float time, Vector3f value) {}

    /** 与 BedrockGeometryModel.mirrorX() 配套：把动画关键帧一并镜像。 */
    public static Map<String, Animation> mirrorX(Map<String, Animation> src) {
        Map<String, Animation> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            out.put(e.getKey(), mirrorAnimation(e.getValue()));
        }
        return out;
    }

    private static Animation mirrorAnimation(Animation a) {
        Map<String, BoneTracks> newBones = new LinkedHashMap<>();
        for (var e : a.bones().entrySet()) {
            BoneTracks t = e.getValue();
            newBones.put(e.getKey(), new BoneTracks(
                    mirrorRotation(t.rotation()),
                    mirrorPosition(t.position()),
                    t.scale()   // 缩放不受 X 镜像影响
            ));
        }
        return new Animation(a.length(), a.loop(), newBones);
    }

    private static List<Keyframe> mirrorRotation(List<Keyframe> keys) {
        if (keys == null) return null;
        List<Keyframe> out = new ArrayList<>(keys.size());
        for (Keyframe k : keys) {
            Vector3f v = k.value();
            out.add(new Keyframe(k.time(), new Vector3f(v.x(), -v.y(), -v.z())));
        }
        return out;
    }

    private static List<Keyframe> mirrorPosition(List<Keyframe> keys) {
        if (keys == null) return null;
        List<Keyframe> out = new ArrayList<>(keys.size());
        for (Keyframe k : keys) {
            Vector3f v = k.value();
            out.add(new Keyframe(k.time(), new Vector3f(-v.x(), v.y(), v.z())));
        }
        return out;
    }
}