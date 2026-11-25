package doctor_m.client.tardis.portal;

import doctor_m.client.tardis.portal.PortalDoorHole.DoorHole;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 由本侧/对侧门洞真实几何构造的穿门虚拟相机。
 *
 * <p>映射约定 —— 由 TardisTeleporter 推导（终审依据）：
 * interiorFacing == exteriorFacing == F（TardisManager.createNewTardis），
 * computeTeleportYRot 给出 yaw' = yaw + 180°，
 * 即穿门映射 = 绕竖直轴 180° 旋转：(u,v,w) → (-u, +v, -w)。
 * 两门正面同向（都朝玩家使用侧），rB 与 rA 为同一世界方向向量，
 * 故相机位置 = remoteCenter - rA·u + up·v - F·w。
 * forward 恒为 remoteFacing：视锥钉洞方案下图像只取决于相机位置，
 * 朝向不影响合成正确性（PortalFrustum 与 viewMatrix 用同一 basis，自洽）。
 */
public final class PortalCamera {

    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    public final Vec3 position;
    public final Vec3 forward;
    public final Vec3 right;
    public final Vec3 up;
    /** 世界 → 视图空间的变换矩阵（相机朝 -Z 看），供 DynamicTransforms 使用。 */
    public final Matrix4f viewMatrix;

    public PortalCamera(DoorHole localHole, Direction localFacing,
                        DoorHole remoteHole, Direction remoteFacing,
                        Vec3 playerPos) {
        Vec3 localCenter  = centerOf(localHole);
        Vec3 remoteCenter = centerOf(remoteHole);

        Vec3 fA = dirVec(localFacing);
        Vec3 fB = dirVec(remoteFacing);
        Vec3 rA = dirVec(localFacing.getClockWise());
        Vec3 rB = dirVec(remoteFacing.getClockWise());

        Vec3 rel = playerPos.subtract(localCenter);
        double w = rel.dot(fA);
        double u = rel.dot(rA);
        double v = rel.dot(WORLD_UP);

        this.position = remoteCenter
                .add(rB.scale(-u))
                .add(WORLD_UP.scale(v))
                .add(fB.scale(-w));

        this.forward = fB;
        this.right   = rB.scale(-1);
        this.up      = WORLD_UP;

        this.viewMatrix = new Matrix4f().lookAt(
                (float) position.x, (float) position.y, (float) position.z,
                (float) (position.x + forward.x),
                (float) (position.y + forward.y),
                (float) (position.z + forward.z),
                (float) up.x, (float) up.y, (float) up.z);
    }

    private static Vec3 centerOf(DoorHole hole) {
        return hole.tl().add(hole.tr()).add(hole.br()).add(hole.bl()).scale(0.25);
    }

    private static Vec3 dirVec(Direction dir) {
        return new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ());
    }
}