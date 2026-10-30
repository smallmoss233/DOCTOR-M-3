package doctor_m.client.tardis.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class PortalCamera {
    public final Vec3 position;
    public final Matrix4f viewMatrix;
    public final Matrix4f projectionMatrix;

    public PortalCamera(BlockPos localPos, Direction localFacing, BlockPos remotePos, Direction remoteFacing,
                        Vec3 playerPos, Matrix4f playerView, Matrix4f playerProjection) {

        Vec3 localCenter = Vec3.atCenterOf(localPos);
        Vec3 remoteCenter = Vec3.atCenterOf(remotePos);

        // 角度计算：门朝向对齐
        float angle = localFacing.toYRot() - remoteFacing.getOpposite().toYRot();

        Matrix4f foldMatrix = new Matrix4f()
                .translate((float) remoteCenter.x, (float) remoteCenter.y, (float) remoteCenter.z)
                .rotateY((float) Math.toRadians(angle))
                .translate((float) -localCenter.x, (float) -localCenter.y, (float) -localCenter.z);

        Vector3f tempPos = new Vector3f((float) playerPos.x, (float) playerPos.y, (float) playerPos.z);
        foldMatrix.transformPosition(tempPos);
        this.position = new Vec3(tempPos.x, tempPos.y, tempPos.z);

        // 视图矩阵
        this.viewMatrix = new Matrix4f(foldMatrix).mul(playerView);
        this.projectionMatrix = new Matrix4f(playerProjection);
    }
}