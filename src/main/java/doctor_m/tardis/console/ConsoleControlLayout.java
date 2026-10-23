package doctor_m.tardis.console;

import doctor_m.tardis.console.control.ControlConfig;
import mosslib.core.bedrock.BedrockGeometryModel;
import mosslib.core.bedrock.BedrockPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从控制台模型 + 方块位姿算出每个控件的世界坐标 AABB。
 *
 * <h2>一次性计算</h2>
 * 只在方块被放置 / 旋转 / 外观切换时调用。日常 tick 不重算 —— 控制台的
 * 几何是静态的，位置不会变。
 *
 * <h2>骨骼分组</h2>
 * 一个控件骨骼的触发范围 = <b>它自身及所有后代骨骼的所有 cube</b> 的 AABB
 * 并集。这样模型作者可以把 cube 挂在子骨骼上，不影响识别。
 *
 * <h2>变换来源</h2>
 * 模型空间部分完全委托给 {@link BedrockPose} —— 与渲染器、{@link
 * mosslib.core.bedrock.BoundingBox} 共用同一份实现，因此碰撞箱不会与
 * 画面错位。本类只负责"分组"和"模型空间 → 世界空间"这两件事。
 */
public final class ConsoleControlLayout {

    private ConsoleControlLayout() {}

    /** 一个控件的世界坐标边界。 */
    public record ControlBounds(String boneName, ControlConfig config, AABB worldBox) {}

    /**
     * 计算控制台所有控件的世界 AABB。
     *
     * @param geo          已解析的几何模型
     * @param appearance   外观配置（含 scale / offset / modelFacing / controls）
     * @param consolePos   控制台方块位置
     * @param blockFacing  方块当前的 FACING
     * @return 按骨骼遍历顺序排列的控件列表；无控件时返回空列表
     */
    public static List<ControlBounds> compute(BedrockGeometryModel geo,
                                              TardisConsoleAppearance appearance,
                                              BlockPos consolePos,
                                              Direction blockFacing) {
        if (geo == null || appearance == null) return List.of();

        Map<String, Group> groups = new LinkedHashMap<>();
        Matrix4f parentPose = new Matrix4f();
        Matrix4f scratch = new Matrix4f();
        Vector3f temp = new Vector3f();

        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            walk(root, null, parentPose, appearance, groups, null, scratch, temp);
        }

        List<ControlBounds> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            if (g.acc.isEmpty()) continue;
            out.add(new ControlBounds(e.getKey(), g.config,
                    toWorldAABB(g.acc, appearance, consolePos, blockFacing)));
        }
        return out;
    }

    // =========================================================
    //                      骨骼遍历
    // =========================================================

    private static void walk(BedrockGeometryModel.Bone bone,
                             Vector3f parentPivot,
                             Matrix4f parentPose,
                             TardisConsoleAppearance app,
                             Map<String, Group> groups,
                             Group inheritedGroup,
                             Matrix4f scratch,
                             Vector3f temp) {

        // 1. 骨骼位姿（静态姿态：布局不跟 animation 变）
        BedrockPose.BoneState state = BedrockPose.resolve(bone, null, 0f);
        Matrix4f bonePose = new Matrix4f();
        BedrockPose.applyBone(bonePose, parentPose, state, parentPivot);

        // 2. 这个骨骼是不是控件骨骼？
        ControlConfig config = ConsoleBoneMatcher.resolve(bone.name(), app);
        Group group;
        if (config != null) {
            // 新建分组；不再向父级累加
            group = groups.computeIfAbsent(bone.name(), k -> new Group(config));
        } else {
            // 继承父级的控件分组（父级是控件时，子级的 cube 也算给父级）
            group = inheritedGroup;
        }

        // 3. 累加本骨骼的 cube
        if (group != null) {
            Vector3f bonePivot = state.pivot();
            for (BedrockGeometryModel.Cube cube : bone.cubes()) {
                group.acc.includeCube(bonePose, cube, bonePivot, scratch, temp);
            }
        }

        // 4. 递归子骨骼
        for (BedrockGeometryModel.Bone child : bone.children()) {
            walk(child, state.pivot(), bonePose, app, groups, group, scratch, temp);
        }
    }

    // =========================================================
    //                模型空间 → 世界空间
    // =========================================================

    /**
     * 与 {@code BedrockRenderPipeline.render} 一致的变换链。
     *
     * <p>PoseStack 的右乘顺序意味着对顶点实际是
     * "先缩放 → 再偏移 → 再旋转 → 最后平移到方块中心"，
     * 即世界坐标 {@code v' = T(pos) · R(yaw) · T(offset) · S(scale) · v}。
     */
    private static AABB toWorldAABB(BedrockPose.AabbAccumulator acc,
                                    TardisConsoleAppearance app,
                                    BlockPos pos,
                                    Direction blockFacing) {
        float s = app.scale() / 16f;
        float rotDeg = app.modelFacing().toYRot() - blockFacing.toYRot();

        Matrix4f m = new Matrix4f()
                .translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f)
                .rotateY((float) Math.toRadians(rotDeg))
                .translate(app.offsetX() / 16f, app.offsetY() / 16f, app.offsetZ() / 16f)
                .scale(s);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        Vector3f v = new Vector3f();
        float localMinX = acc.minX(), localMinY = acc.minY(), localMinZ = acc.minZ();
        float localMaxX = acc.maxX(), localMaxY = acc.maxY(), localMaxZ = acc.maxZ();

        for (int i = 0; i < 8; i++) {
            v.set((i & 1) == 0 ? localMinX : localMaxX,
                  (i & 2) == 0 ? localMinY : localMaxY,
                  (i & 4) == 0 ? localMinZ : localMaxZ);
            m.transformPosition(v);

            if (v.x < minX) minX = v.x;
            if (v.y < minY) minY = v.y;
            if (v.z < minZ) minZ = v.z;
            if (v.x > maxX) maxX = v.x;
            if (v.y > maxY) maxY = v.y;
            if (v.z > maxZ) maxZ = v.z;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // =========================================================
    //                      分组
    // =========================================================

    private static final class Group {
        final ControlConfig config;
        final BedrockPose.AabbAccumulator acc = new BedrockPose.AabbAccumulator();

        Group(ControlConfig config) { this.config = config; }
    }
}
