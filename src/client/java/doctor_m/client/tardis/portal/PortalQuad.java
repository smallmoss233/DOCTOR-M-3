package doctor_m.client.tardis.portal;

/** 一个四边形（4 顶点，允许退化）。 */
public record PortalQuad(PortalVertex a, PortalVertex b, PortalVertex c, PortalVertex d) {

    public PortalVertex[] vertices() { return new PortalVertex[]{a, b, c, d}; }
}