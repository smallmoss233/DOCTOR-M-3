package doctor_m.client.tardis.portal;

/** 单个顶点：世界坐标 + 颜色 + UV + packed light + 法线。 */
public record PortalVertex(float x, float y, float z,
                           int color, float u, float v,
                           int light, float nx, float ny, float nz) {

    public PortalVertex lerp(PortalVertex other, float t) {
        return new PortalVertex(
                x + (other.x - x) * t,
                y + (other.y - y) * t,
                z + (other.z - z) * t,
                color, // 颜色不插值，取起点
                u + (other.u - u) * t,
                v + (other.v - v) * t,
                light,
                nx + (other.nx - nx) * t,
                ny + (other.ny - ny) * t,
                nz + (other.nz - nz) * t);
    }
}