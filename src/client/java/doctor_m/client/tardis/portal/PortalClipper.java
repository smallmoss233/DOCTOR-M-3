package doctor_m.client.tardis.portal;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class PortalClipper {

    private PortalClipper() {}

    public record DoorHole(Vec3 tl, Vec3 tr, Vec3 bl, Vec3 br) {
        public Vec3 center() {
            return new Vec3(
                    (tl.x + tr.x + bl.x + br.x) / 4.0,
                    (tl.y + tr.y + bl.y + br.y) / 4.0,
                    (tl.z + tr.z + bl.z + br.z) / 4.0
            );
        }
        public Vec3 normal() {
            // 通过 tl, tr, bl 计算平面的朝外法线
            return tr.subtract(tl).cross(bl.subtract(tl)).normalize();
        }
    }

    public static List<PortalQuad> clip(PortalMesh mesh, Frustum frustum,
                                        Vec3 planePoint, Vec3 planeNormal,
                                        DoorHole hole) {
        if (mesh.isEmpty() || !frustum.isVisible(mesh.bounds())) return List.of();
        if (hole == null) return mesh.quads();

        // 计算门洞的 4 个向内的裁剪面
        Vec3[] sidePoints = new Vec3[]{
                hole.tl(), hole.tr(), hole.bl(), hole.br()
        };
        Vec3[] sideNormals = new Vec3[]{
                hole.tr().subtract(hole.tl()), // 左边界法线（指向右）
                hole.tl().subtract(hole.tr()), // 右边界法线（指向左）
                hole.bl().subtract(hole.tl()), // 上边界法线（指向下）
                hole.tl().subtract(hole.bl())  // 下边界法线（指向上）
        };

        List<PortalQuad> out = new ArrayList<>(mesh.quads().size());
        for (PortalQuad quad : mesh.quads()) {
            List<PortalVertex> poly = new ArrayList<>(List.of(quad.vertices()));

            // 1. 门平面裁剪（切掉门前的内容）
            poly = clipPolygon(poly, planePoint, planeNormal);

            // 2. 四面刀裁剪（切掉门框外的东西）
            for (int i = 0; i < 4 && poly.size() >= 3; i++) {
                poly = clipPolygon(poly, sidePoints[i], sideNormals[i]);
            }

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

            // 法线朝内，保留 >= 0 的顶点
            boolean curIn = dCur >= 0, prevIn = dPrev >= 0;
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