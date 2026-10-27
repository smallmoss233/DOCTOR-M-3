package doctor_m.client.tardis.portal;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class PortalClipper {

    private PortalClipper() {}

    // DoorHole 保留，后面 Mixin 挖洞要用
    public record DoorHole(Vec3 tl, Vec3 tr, Vec3 bl, Vec3 br) {}

    public static List<PortalQuad> clip(PortalMesh mesh, Frustum frustum,
                                        Vec3 planePoint, Vec3 planeNormal) {
        if (mesh.isEmpty() || !frustum.isVisible(mesh.bounds())) return List.of();

        List<PortalQuad> out = new ArrayList<>(mesh.quads().size());
        for (PortalQuad quad : mesh.quads()) {
            List<PortalVertex> poly = new ArrayList<>(List.of(quad.vertices()));
            // 只做门平面裁剪（只保留门后方的几何）
            poly = clipPolygon(poly, planePoint, planeNormal);

            if (poly.size() >= 3) {
                toQuads(poly, out);
            }
        }
        return out;
    }

    private static double sd(PortalVertex v, Vec3 p, Vec3 n) {
        return (v.x() - p.x) * n.x + (v.y() - p.y) * n.y + (v.z() - p.z) * n.z;
    }

    private static List<PortalVertex> clipPolygon(List<PortalVertex> input, Vec3 p, Vec3 n) {
        List<PortalVertex> out = new ArrayList<>(input.size() + 2);
        for (int i = 0; i < input.size(); i++) {
            PortalVertex cur = input.get(i);
            PortalVertex prev = input.get((i + input.size() - 1) % input.size());
            double dCur = sd(cur, p, n);
            double dPrev = sd(prev, p, n);
            boolean curIn = dCur <= 0, prevIn = dPrev <= 0;
            if (curIn != prevIn) {
                double t = dPrev / (dPrev - dCur);
                out.add(prev.lerp(cur, (float) t));
            }
            if (curIn) out.add(cur);
        }
        return out;
    }

    private static void toQuads(List<PortalVertex> poly, List<PortalQuad> out) {
        int n = poly.size();
        if (n < 3) return;
        if (n == 3) { out.add(new PortalQuad(poly.get(0), poly.get(1), poly.get(2), poly.get(2))); return; }
        if (n == 4) { out.add(new PortalQuad(poly.get(0), poly.get(1), poly.get(2), poly.get(3))); return; }
        int i = 0;
        for (; i + 3 < n; i += 2) {
            out.add(new PortalQuad(poly.get(i), poly.get(i+1), poly.get(i+2), poly.get(i+3)));
        }
        if (i + 2 < n) {
            out.add(new PortalQuad(poly.get(i), poly.get(i+1), poly.get(i+2), poly.get(i+2)));
        }
    }
}