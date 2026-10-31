package doctor_m.client.tardis.portal;

/**
 * 一个四边形（4 顶点，允许退化）。
 *
 * <p>不要提供 {@code vertices()} 这类"每次调用新建 4 元素数组"的便捷方法 ——
 * 渲染循环里一帧会遍历成百上千个 quad，那种写法是纯粹的 GC 压力。
 */
public record PortalQuad(PortalVertex a, PortalVertex b, PortalVertex c, PortalVertex d) {
}
