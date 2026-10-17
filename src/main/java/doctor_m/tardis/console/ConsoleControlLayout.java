package doctor_m.tardis.console;

import mosslib.core.bedrock.BedrockGeometryModel;
import mosslib.core.bedrock.BedrockRenderMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
 * <h2>变换链（与渲染器一致）</h2>
 * 模型空间坐标 → 缩放 {@code scale/16} → 平移 {@code offset/16}
 * → 绕 Y 旋转 {@code modelFacing.toYRot - facing.toYRot}
 * → 平移到方块中心 {@code (pos + 0.5, pos.y, pos + 0.5)}。
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

        // 骨骼名 → 局部空间 AABB 累加器
        Map<String, GroupAccumulator> groups = new LinkedHashMap<>();

        Matrix4f identity = new Matrix4f();
        for (BedrockGeometryModel.Bone root : geo.rootBones()) {
            walk(root, null, identity, appearance, groups, null);
        }

        List<ControlBounds> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, GroupAccumulator> e : groups.entrySet()) {
            GroupAccumulator acc = e.getValue();
            if (acc.isEmpty()) continue;
            AABB local = acc.toAABB();
            AABB world = toWorldAABB(local, appearance, consolePos, blockFacing);
            out.add(new ControlBounds(e.getKey(), acc.config, world));
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
                             Map<String, GroupAccumulator> groups,
                             GroupAccumulator inheritedGroup) {

        // 1. 计算该骨骼的累计变换（与渲染器 / BoundingBox 保持一致）
        Matrix4f pose = new Matrix4f(parentPose);
        Vector3f pivot = bone.pivot();
        if (parentPivot == null) {
            pose.translate(pivot.x(), pivot.y(), pivot.z());
        } else {
            pose.translate(pivot.x() - parentPivot.x(),
                    pivot.y() - parentPivot.y(),
                    pivot.z() - parentPivot.z());
        }
        Vector3f rot = bone.rotation();
        if (rot.x() != 0f || rot.y() != 0f || rot.z() != 0f) {
            // 骨骼旋转走 mosslib 的定序（与本项目其它地方一致）
            pose.rotate(BedrockRenderMath.boneRotationEuler(rot));
        }

        // 2. 这个骨骼是不是控件骨骼？
        ControlConfig config = ConsoleBoneMatcher.resolve(bone.name(), app);
        GroupAccumulator group;
        if (config != null) {
            // 新建一个分组；不再向父级累加
            group = groups.computeIfAbsent(bone.name(), k -> new GroupAccumulator(config));
        } else {
            // 继承父级的控件分组（父级是控件时，子级的 cube 也算给父级）
            group = inheritedGroup;
        }

        // 3. 累加这个骨骼的所有 cube
        if (group != null) {
            for (BedrockGeometryModel.Cube cube : bone.cubes()) {
                includeCube(group, cube, pose, pivot);
            }
        }

        // 4. 递归子骨骼
        for (BedrockGeometryModel.Bone child : bone.children()) {
            walk(child, pivot, pose, app, groups, group);
        }
    }

    /**
     * 把单个 cube 的 8 个角变换到模型空间，扩展 group 的 AABB。
     *
     * <p>变换链与 {@code BedrockModelRenderer.renderCube} 一致：
     * {@code (origin - cubePivot) → translate(cubePivot - bonePivot)
     * → rotate(cubeRotation) → 骨骼 pose}。
     */
    private static void includeCube(GroupAccumulator group,
                                    BedrockGeometryModel.Cube cube,
                                    Matrix4f bonePose,
                                    Vector3f bonePivot) {
        Vector3f size = cube.size();
        if (size.x() == 0f && size.y() == 0f && size.z() == 0f) return;

        Vector3f cubePivot = cube.pivot() != null ? cube.pivot() : bonePivot;

        Matrix4f m = new Matrix4f(bonePose);

        float dpx = cubePivot.x() - bonePivot.x();
        float dpy = cubePivot.y() - bonePivot.y();
        float dpz = cubePivot.z() - bonePivot.z();
        if (dpx != 0f || dpy != 0f || dpz != 0f) {
            m.translate(dpx, dpy, dpz);
        }
        Vector3f cr = cube.rotation();
        if (cr.x() != 0f || cr.y() != 0f || cr.z() != 0f) {
            // 与渲染器 / BoundingBox 一致：走 BedrockRenderMath.cubeRotationEuler
            m.rotate(BedrockRenderMath.cubeRotationEuler(cr));
        }

        Vector3f tmp = new Vector3f();
        forEachCorner(
                cube.origin().x() - cubePivot.x(),
                cube.origin().y() - cubePivot.y(),
                cube.origin().z() - cubePivot.z(),
                cube.origin().x() - cubePivot.x() + size.x(),
                cube.origin().y() - cubePivot.y() + size.y(),
                cube.origin().z() - cubePivot.z() + size.z(),
                (x, y, z) -> {
                    tmp.set((float) x, (float) y, (float) z);
                    m.transformPosition(tmp);
                    group.include(tmp.x, tmp.y, tmp.z);
                });
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
    private static AABB toWorldAABB(AABB local,
                                    TardisConsoleAppearance app,
                                    BlockPos pos,
                                    Direction blockFacing) {
        float s = app.scale() / 16f;
        float rotDeg = app.modelFacing().toYRot() - blockFacing.toYRot();

        // JOML 方法依次右乘，构造出 T(pos) · R · T(offset) · S
        Matrix4f m = new Matrix4f()
                .translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f)
                .rotateY((float) Math.toRadians(rotDeg))
                .translate(app.offsetX() / 16f, app.offsetY() / 16f, app.offsetZ() / 16f)
                .scale(s);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        Vector3f v = new Vector3f();
        for (int i = 0; i < 8; i++) {
            v.set((i & 1) == 0 ? (float) local.minX : (float) local.maxX,
                  (i & 2) == 0 ? (float) local.minY : (float) local.maxY,
                  (i & 4) == 0 ? (float) local.minZ : (float) local.maxZ);
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
    //                      工具
    // =========================================================

    /** 遍历盒子的 8 个角。骨骼 cube 累加与世界变换共用这一份角落枚举逻辑。 */
    private static void forEachCorner(double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ,
                                      CornerConsumer consumer) {
        for (int i = 0; i < 8; i++) {
            consumer.accept(
                    (i & 1) == 0 ? minX : maxX,
                    (i & 2) == 0 ? minY : maxY,
                    (i & 4) == 0 ? minZ : maxZ);
        }
    }

    @FunctionalInterface
    private interface CornerConsumer {
        void accept(double x, double y, double z);
    }

    // =========================================================
    //                      累加器
    // =========================================================

    private static final class GroupAccumulator {
        final ControlConfig config;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        GroupAccumulator(ControlConfig config) { this.config = config; }

        void include(float x, float y, float z) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        boolean isEmpty() { return maxX < minX; }

        AABB toAABB() { return new AABB(minX, minY, minZ, maxX, maxY, maxZ); }
    }
}
