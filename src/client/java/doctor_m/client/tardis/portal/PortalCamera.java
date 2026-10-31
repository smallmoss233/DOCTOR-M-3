package doctor_m.client.tardis.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class PortalCamera {
    public final Vec3 position;
    public final Matrix4f viewMatrix;
    public final Matrix4f projectionMatrix;

    public PortalCamera(BlockPos localPos, Direction localFacing, BlockPos remotePos, Direction remoteFacing,
                        Vec3 playerPos, float yRot, float xRot, Matrix4f playerProjection) {

        Vec3 localCenter = new Vec3(localPos.getX() + 0.5, localPos.getY() + 1.0, localPos.getZ() + 0.5);
        Vec3 remoteCenter = new Vec3(remotePos.getX() + 0.5, remotePos.getY() + 1.0, remotePos.getZ() + 0.5);

        Vec3 rel = playerPos.subtract(localCenter);
        float deltaYaw = localFacing.toYRot() - remoteFacing.getOpposite().toYRot();

        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        Vec3 relRotated = new Vec3(rel.x * cos + rel.z * sin, rel.y, -rel.x * sin + rel.z * cos);

        double parallaxScale = 0.2;

        double camX = relRotated.x * parallaxScale;
        double camY = relRotated.y * parallaxScale;
        double camZ = relRotated.z * parallaxScale;

        this.position = remoteCenter.add(camX, camY, camZ);

        float portalYaw = yRot + deltaYaw;
        float portalPitch = xRot;

        Matrix4f viewRot = new Matrix4f()
                .rotateX((float) Math.toRadians(portalPitch))
                .rotateY((float) Math.toRadians(180.0f - portalYaw));

        this.viewMatrix = new Matrix4f(viewRot)
                .translate((float) -position.x, (float) -position.y, (float) -position.z);

        this.projectionMatrix = new Matrix4f(playerProjection);
    }
}