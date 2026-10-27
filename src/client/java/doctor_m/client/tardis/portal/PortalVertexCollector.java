package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截 VertexConsumer 调用，捕获世界坐标顶点。
 *
 * 注意：26.3 的 VertexConsumer 可能新增抽象方法；若编译失败，
 * 按报错补全对应 setter 即可（逻辑都是记录字段）。
 * putBlockBakedQuad 走 default 实现，会依次调用 addVertex/setColor/setUv/setLight/setNormal。
 */
public final class PortalVertexCollector implements VertexConsumer {

    private final List<PortalVertex> captured = new ArrayList<>(1024);

    private float x, y, z;
    private int color = 0xFFFFFFFF;
    private float u, v;
    private int light = 0;
    private float nx = 0, ny = 1, nz = 0;
    private boolean hasVertex = false;

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        flush();
        this.x = x; this.y = y; this.z = z;
        this.hasVertex = true;
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int color,
                          float u, float v, int overlay, int light,
                          float nx, float ny, float nz) {
        flush();
        captured.add(new PortalVertex(x, y, z, color, u, v, light, nx, ny, nz));
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) { this.color = (a << 24) | (r << 16) | (g << 8) | b; return this; }
    @Override public VertexConsumer setColor(int color) { this.color = color; return this; }
    @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setOverlay(int packedOverlay) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { this.light = (u & 0xFFFF) | ((v & 0xFFFF) << 16); return this; }
    @Override public VertexConsumer setUv3(float u, float v) { return this; }
    @Override public VertexConsumer setLight(int packedLight) { this.light = packedLight; return this; }
    @Override public VertexConsumer setNormal(float nx, float ny, float nz) { this.nx = nx; this.ny = ny; this.nz = nz; return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }

    private void flush() {
        if (hasVertex) {
            captured.add(new PortalVertex(x, y, z, color, u, v, light, nx, ny, nz));
            hasVertex = false;
            color = 0xFFFFFFFF; u = 0; v = 0; light = 0; nx = 0; ny = 1; nz = 0;
        }
    }

    /** 取走已捕获顶点并清空，每 4 个一组视为一个 quad。 */
    public List<PortalQuad> drainQuads() {
        flush();
        List<PortalQuad> quads = new ArrayList<>(captured.size() / 4);
        for (int i = 0; i + 3 < captured.size(); i += 4) {
            quads.add(new PortalQuad(captured.get(i), captured.get(i + 1),
                    captured.get(i + 2), captured.get(i + 3)));
        }
        captured.clear();
        return quads;
    }
}