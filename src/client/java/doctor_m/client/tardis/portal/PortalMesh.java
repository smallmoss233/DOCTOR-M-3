package doctor_m.client.tardis.portal;

import net.minecraft.world.phys.AABB;

import java.util.List;

/** 缓存网格：一组 quad + 粗包围盒（用于快速视锥剔除）。 */
public final class PortalMesh {

    private final List<PortalQuad> quads;
    private final AABB bounds;

    public PortalMesh(List<PortalQuad> quads) {
        this.quads = List.copyOf(quads);
        this.bounds = computeBounds(this.quads);
    }

    private static AABB computeBounds(List<PortalQuad> quads) {
        if (quads.isEmpty()) return new AABB(0, 0, 0, 0, 0, 0);
        // b = {minX, minY, minZ, maxX, maxY, maxZ}
        double[] b = {
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE
        };
        for (PortalQuad q : quads) {
            include(b, q.a());
            include(b, q.b());
            include(b, q.c());
            include(b, q.d());
        }
        return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static void include(double[] b, PortalVertex v) {
        if (v.x() < b[0]) b[0] = v.x();
        if (v.y() < b[1]) b[1] = v.y();
        if (v.z() < b[2]) b[2] = v.z();
        if (v.x() > b[3]) b[3] = v.x();
        if (v.y() > b[4]) b[4] = v.y();
        if (v.z() > b[5]) b[5] = v.z();
    }

    public List<PortalQuad> quads() { return quads; }
    public AABB bounds() { return bounds; }
    public boolean isEmpty() { return quads.isEmpty(); }
}