package doctor_m.client.tardis.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class PortalTransform {

    private final Matrix4f matrix;   // remote -> local
    private final Vec3 centerLocal;
    private final Vec3 centerRemote;
    private final Direction localFacing;
    private final Direction remoteFacing;
    private final Vec3 planePoint;
    private final Vec3 planeNormal;

    private PortalTransform(Matrix4f matrix, Vec3 centerLocal, Vec3 centerRemote,
                            Direction localFacing, Direction remoteFacing) {
        this.matrix = matrix;
        this.centerLocal = centerLocal;
        this.centerRemote = centerRemote;
        this.localFacing = localFacing;
        this.remoteFacing = remoteFacing;

        // 门后方向就是 localFacing 的反方向（比如朝南的门，后面是北）
        Vec3 inward = stepVec(localFacing.getOpposite());

        // 裁剪平面点：稍微推到门后一点点
        this.planePoint = centerLocal.add(inward.scale(0.02));

        // 裁剪法线：指向门外，这样 PortalClipper 会剔除掉门外的一切，保留门后
        this.planeNormal = stepVec(localFacing);
    }

    private static Vec3 stepVec(Direction dir) {
        return new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ());
    }

    public static PortalTransform fromDoorToDoor(BlockPos localPos, Direction localFacing,
                                                 BlockPos remotePos, Direction remoteFacing,
                                                 double depthOffset) {
        Vec3 centerLocal = Vec3.atCenterOf(localPos);
        Vec3 centerRemote = Vec3.atCenterOf(remotePos);

        // 因为是“外门正面=内门后面”，所以 remoteFacing 旋转后应该和 localFacing 的反方向对齐。
        // 如果两门相对，这里算出来刚好是 0°。
        float rotRad = (float) Math.toRadians(localFacing.getOpposite().toYRot() - remoteFacing.toYRot());

        Vec3 inward = stepVec(localFacing.getOpposite());

        Matrix4f m = new Matrix4f()
                .translate((float) centerLocal.x, (float) centerLocal.y, (float) centerLocal.z)
                // 往后推（推到外门后方）
                .translate((float) (inward.x * depthOffset),
                        (float) (inward.y * depthOffset),
                        (float) (inward.z * depthOffset))
                // 旋转（通常为0）
                .rotateY(rotRad)
                .translate((float) -centerRemote.x, (float) -centerRemote.y, (float) -centerRemote.z);

        return new PortalTransform(m, centerLocal, centerRemote, localFacing, remoteFacing);
    }

    public Vec3 transform(Vec3 src) {
        Vector3f v = new Vector3f((float) src.x, (float) src.y, (float) src.z);
        matrix.transformPosition(v);
        return new Vec3(v.x, v.y, v.z);
    }

    public Vec3 planePoint()  { return planePoint; }
    public Vec3 planeNormal() { return planeNormal; }
    public Vec3 centerLocal() { return centerLocal; }
    public Vec3 centerRemote() { return centerRemote; }
    public Direction localFacing()  { return localFacing; }
    public Direction remoteFacing() { return remoteFacing; }
    public org.joml.Matrix4f matrix() { return new org.joml.Matrix4f(this.matrix); }
}