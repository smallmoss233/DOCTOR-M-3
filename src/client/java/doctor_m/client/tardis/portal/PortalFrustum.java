package doctor_m.client.tardis.portal;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class PortalFrustum {

    public final Matrix4f projectionMatrix;
    /** false 表示视锥无效，当前视角已超出安全范围，应跳过渲染 */
    public final boolean valid;

    private static final float NEAR = 0.05f;

    public PortalFrustum(PortalCamera cam, PortalDoorHole.DoorHole remoteHole, float far) {
        this(cam.position, cam.forward, cam.right, cam.up, remoteHole, far);
    }

    public PortalFrustum(Vec3 camPos, Vec3 camForward, Vec3 camRight, Vec3 camUp,
                         PortalDoorHole.DoorHole remoteHole, float far) {
        Vec3 tl = remoteHole.tl();
        Vec3 tr = remoteHole.tr();
        Vec3 br = remoteHole.br();
        Vec3 bl = remoteHole.bl();

        Matrix4f view = new Matrix4f().setLookAt(
                (float) camPos.x, (float) camPos.y, (float) camPos.z,
                (float) (camPos.x + camForward.x),
                (float) (camPos.y + camForward.y),
                (float) (camPos.z + camForward.z),
                (float) camUp.x, (float) camUp.y, (float) camUp.z
        );

        float farClamped = Math.max(far, NEAR + 0.01f);
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;

        Vec3[] corners = { tl, tr, br, bl };
        for (Vec3 corner : corners) {
            Vector3f v = view.transformPosition(corner.toVector3f());
            // 只要有一个角点在相机后方或贴得太近，直接作废，防止光栅化出现极端的黑色剪切面
            if (-v.z <= 0.01f) {
                this.projectionMatrix = new Matrix4f();
                this.valid = false;
                return;
            }
            float px = v.x * NEAR / -v.z;
            float py = v.y * NEAR / -v.z;
            if (px < left) left = px;
            if (px > right) right = px;
            if (py < bottom) bottom = py;
            if (py > top) top = py;
        }

        this.projectionMatrix = frustumReversedZ(left, right, bottom, top, NEAR, farClamped);
        this.valid = true;
    }

    private static Matrix4f frustumReversedZ(float l, float r, float b, float t, float n, float f) {
        Matrix4f m = new Matrix4f();
        m.m00(2f * n / (r - l));
        m.m02((r + l) / (r - l));
        m.m11(2f * n / (t - b));
        m.m12((t + b) / (t - b));
        m.m22(n / (f - n));
        m.m32(f * n / (f - n));
        m.m23(-1f);
        m.m33(0f);
        return m;
    }
}