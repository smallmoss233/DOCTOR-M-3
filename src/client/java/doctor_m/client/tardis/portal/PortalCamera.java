package doctor_m.client.tardis.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class PortalCamera {

    public final Vec3 position;
    public final Vec3 forward;
    public final Vec3 up;
    public final Vec3 right;
    public final Matrix4f viewMatrix;

    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    public PortalCamera(BlockPos localPos, Direction localFacing,
                        BlockPos remotePos, Direction remoteFacing,
                        Vec3 playerPos, float yRot, float xRot) {

        Vec3 localCenter  = new Vec3(localPos.getX()  + 0.5, localPos.getY()  + 1.0, localPos.getZ()  + 0.5);
        Vec3 remoteCenter = new Vec3(remotePos.getX() + 0.5, remotePos.getY() + 1.0, remotePos.getZ() + 0.5);

        Vec3 fA = dirVec(localFacing);
        Vec3 rA = dirVec(localFacing.getClockWise());
        Vec3 fB = dirVec(remoteFacing);
        Vec3 rB = dirVec(remoteFacing.getClockWise());

        // 位置：绕 UP 轴 180° 刚体变换
        Vec3 rel = playerPos.subtract(localCenter);
        double u = rel.dot(rA);
        double v = rel.dot(WORLD_UP);
        double w = rel.dot(fA);
        this.position = remoteCenter
                .add(rB.scale(-u))
                .add(WORLD_UP.scale(v))
                .add(fB.scale(-w));

        // 朝向：同一刚体变换
        double yawRad = Math.toRadians(yRot);
        Vec3 playerHLook = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        double lu = playerHLook.dot(rA);
        double lw = playerHLook.dot(fA);
        Vec3 virtualHLook = rB.scale(-lu).add(fB.scale(-lw)).normalize();

        float pitchRad = (float) Math.toRadians(xRot);
        double cosP = Math.cos(pitchRad);
        double sinP = Math.sin(pitchRad);
        Vec3 vForward = new Vec3(
                virtualHLook.x * cosP,
                -sinP,
                virtualHLook.z * cosP
        ).normalize();
        this.forward = vForward;

        // right = forward × WORLD_UP（标准右手系）
        Vec3 vRight = vForward.cross(WORLD_UP);

        // 退化处理：pitch ±90° 时 vForward 与 WORLD_UP 平行
        // 用一个与 vForward 不平行的辅助向量，避免 right 变成水平向量
        if (vRight.lengthSqr() < 1e-6) {
            Vec3 aux = Math.abs(vForward.y) > 0.99
                    ? new Vec3(0, 0, 1)   // 垂直视线用 +z 作辅助
                    : WORLD_UP;
            vRight = vForward.cross(aux);
        }
        vRight = vRight.normalize();
        this.right = vRight;
        this.up = vRight.cross(vForward).normalize();

        this.viewMatrix = new Matrix4f().setLookAt(
                (float) position.x, (float) position.y, (float) position.z,
                (float) (position.x + forward.x),
                (float) (position.y + forward.y),
                (float) (position.z + forward.z),
                (float) up.x, (float) up.y, (float) up.z
        );
    }

    private static Vec3 dirVec(Direction d) {
        return new Vec3(d.getStepX(), 0, d.getStepZ());
    }
}