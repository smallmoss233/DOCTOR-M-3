package doctor_m.client.tardis.portal;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class PortalCamera {
    public final Matrix4f viewMatrix;
    public final Matrix4f projectionMatrix;

    public PortalCamera(BlockPos localPos, Direction localFacing, BlockPos remotePos, Direction remoteFacing,
                        CameraRenderState camera) {

        // 1. 计算门中心点（Y轴+1.0是因为门高两格，中心在中间）
        Vec3 localCenter = new Vec3(localPos.getX() + 0.5, localPos.getY() + 1.0, localPos.getZ() + 0.5);
        Vec3 remoteCenter = new Vec3(remotePos.getX() + 0.5, remotePos.getY() + 1.0, remotePos.getZ() + 0.5);

        // 2. 计算玩家相对于本地门中心的位置
        Vec3 rel = camera.pos.subtract(localCenter);

        // 3. 计算朝向差值 (MC 中顺时针是正，南为 0)
        float localYaw = localFacing.toYRot();
        float remoteYaw = remoteFacing.toYRot();
        float deltaYaw = remoteYaw - localYaw;

        // 4. 将相对位置绕着 Y 轴旋转 deltaYaw 度
        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        // 【核心修正】将 Z 轴取反（相当于穿过了镜子，从门外到了门内）
        // 同时旋转 X 和 Z
        double rx = rel.x * cos - rel.z * sin;
        double rz = -(rel.x * sin + rel.z * cos);

        // 5. 虚拟相机的位置 = 对端门中心 + 旋转并翻转后的相对位置
        Vec3 virtualPos = new Vec3(remoteCenter.x + rx, remoteCenter.y + rel.y, remoteCenter.z + rz);

        // 6. 虚拟相机的朝向
        // 由于 Z 轴翻转了，视线方向也要相应反转，所以 Yaw 加上 180 度
        float virtualYaw = camera.yRot + deltaYaw + 180.0f;
        float virtualPitch = camera.xRot;

        // 7. 构建标准的视图矩阵
        Matrix4f view = new Matrix4f();
        view.rotateX((float) Math.toRadians(virtualPitch));
        view.rotateY((float) Math.toRadians(180.0f - virtualYaw));
        view.translate((float) -virtualPos.x, (float) -virtualPos.y, (float) -virtualPos.z);

        this.viewMatrix = view;
        this.projectionMatrix = new Matrix4f(camera.projectionMatrix);
    }
}