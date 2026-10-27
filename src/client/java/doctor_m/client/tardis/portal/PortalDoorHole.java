package doctor_m.client.tardis.portal;

import doctor_m.tardis.appearance.TardisAsset;
import mosslib.core.bedrock.BedrockGeometryModel;
import mosslib.core.bedrock.BedrockModelRef;
import mosslib.core.render.BedrockRenderPipeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class PortalDoorHole {

    public static final String PORTAL_BONE = "boti_portal";
    private PortalDoorHole() {}

    public static PortalClipper.DoorHole extract(BlockPos pos, Direction facing, TardisAsset.Bedrock asset) {
        if (asset == null) return fallback(pos, facing);

        BedrockModelRef ref = BedrockRenderPipeline.resolve(asset.geometry(), asset.texture());
        if (ref == null) return fallback(pos, facing);

        BedrockGeometryModel.Bone bone = ref.geometry().byName().get(PORTAL_BONE);
        if (bone == null || bone.cubes().isEmpty()) return fallback(pos, facing);

        float scale = asset.scale() / 16f;
        float rotDeg = Direction.NORTH.toYRot() - facing.toYRot();

        // 1. 收集骨骼内所有 cube 的 8 个顶点，映射到世界坐标
        double minWX = Double.MAX_VALUE, minWY = Double.MAX_VALUE, minWZ = Double.MAX_VALUE;
        double maxWX = -Double.MAX_VALUE, maxWY = -Double.MAX_VALUE, maxWZ = -Double.MAX_VALUE;

        for (BedrockGeometryModel.Cube c : bone.cubes()) {
            Vector3f min = c.origin();
            Vector3f size = c.size();
            for (int i = 0; i < 8; i++) {
                Vector3f v = new Vector3f(
                        min.x + ((i & 1) == 0 ? 0 : size.x),
                        min.y + ((i & 2) == 0 ? 0 : size.y),
                        min.z + ((i & 4) == 0 ? 0 : size.z)
                );

                // 应用 cube 自身的 pivot 和 rotation (如果存在)
                Matrix4f cMat = new Matrix4f();
                Vector3f pivot = c.pivot();
                if (pivot != null) {
                    cMat.translate(pivot.x, pivot.y, pivot.z);
                }
                if (c.rotation() != null) {
                    cMat.rotateXYZ(
                            (float) Math.toRadians(c.rotation().x),
                            (float) Math.toRadians(c.rotation().y),
                            (float) Math.toRadians(c.rotation().z)
                    );
                }
                if (pivot != null) {
                    cMat.translate(-pivot.x, -pivot.y, -pivot.z);
                }
                cMat.transformPosition(v);

                // 应用模型的全局变换
                v.mul(scale);
                v.add(new Vector3f(
                        asset.offset().x / 16f,
                        asset.offset().y / 16f,
                        asset.offset().z / 16f
                ));

                // 旋转并平移到方块所在的世界坐标
                Matrix4f worldMat = new Matrix4f()
                        .translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f)
                        .rotateY((float) Math.toRadians(rotDeg));
                worldMat.transformPosition(v);

                minWX = Math.min(minWX, v.x);
                minWY = Math.min(minWY, v.y);
                minWZ = Math.min(minWZ, v.z);
                maxWX = Math.max(maxWX, v.x);
                maxWY = Math.max(maxWY, v.y);
                maxWZ = Math.max(maxWZ, v.z);
            }
        }

        if (maxWX < minWX) return fallback(pos, facing);

        // 2. 世界包围盒中心点
        Vec3 center = new Vec3(
                (minWX + maxWX) / 2.0,
                (minWY + maxWY) / 2.0,
                (minWZ + maxWZ) / 2.0
        );

        // 3. 计算宽高 (自动根据 world AABB 判定)
        double width, height = maxWY - minWY;
        if (facing.getAxis() == Direction.Axis.Z) {
            width = maxWX - minWX;
        } else {
            width = maxWZ - minWZ;
        }

        if (width < 0.1 || height < 0.1) return fallback(pos, facing);

        // 4. 朝外偏移一点点（防止和外壳 Z-Fighting）
        Vec3 n = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        center = center.add(n.scale(0.01));

        // 5. 强制生成绝对垂直的矩形
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = up.cross(n).normalize();
        if (right.lengthSqr() < 0.1) right = new Vec3(1, 0, 0);

        Vec3 halfW = right.scale(width / 2.0);
        Vec3 halfH = up.scale(height / 2.0);

        return new PortalClipper.DoorHole(
                center.add(halfH).subtract(halfW), // tl
                center.add(halfH).add(halfW),      // tr
                center.subtract(halfH).subtract(halfW), // bl
                center.subtract(halfH).add(halfW)  // br
        );
    }

    private static PortalClipper.DoorHole fallback(BlockPos pos, Direction facing) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return switch (facing) {
            case NORTH -> new PortalClipper.DoorHole(
                    new Vec3(x + 1, y + 2, z), new Vec3(x, y + 2, z),
                    new Vec3(x + 1, y, z), new Vec3(x, y, z));
            case SOUTH -> new PortalClipper.DoorHole(
                    new Vec3(x, y + 2, z + 1), new Vec3(x + 1, y + 2, z + 1),
                    new Vec3(x, y, z + 1), new Vec3(x + 1, y, z + 1));
            case EAST -> new PortalClipper.DoorHole(
                    new Vec3(x + 1, y + 2, z), new Vec3(x + 1, y + 2, z + 1),
                    new Vec3(x + 1, y, z), new Vec3(x + 1, y, z + 1));
            case WEST -> new PortalClipper.DoorHole(
                    new Vec3(x, y + 2, z + 1), new Vec3(x, y + 2, z),
                    new Vec3(x, y, z + 1), new Vec3(x, y, z));
            default -> new PortalClipper.DoorHole(
                    Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
        };
    }
}