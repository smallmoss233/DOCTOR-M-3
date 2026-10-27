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
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (PortalQuad q : quads) {
            for (PortalVertex v : q.vertices()) {
                minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public List<PortalQuad> quads() { return quads; }
    public AABB bounds() { return bounds; }
    public boolean isEmpty() { return quads.isEmpty(); }
}