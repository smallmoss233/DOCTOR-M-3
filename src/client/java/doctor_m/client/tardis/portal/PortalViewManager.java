package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import doctor_m.block.AbstractTardisDoorBlock;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.stp.StpClientState;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PortalViewManager {

    private static PortalViewManager INSTANCE;
    public static PortalViewManager get() {
        if (INSTANCE == null) INSTANCE = new PortalViewManager();
        return INSTANCE;
    }

    /**
     * 门户缓存键：门所在维度 + 方块坐标。
     *
     * <p>只拿 {@link BlockPos} 当键时，外门与内门只要恰好落在同一坐标就会互相
     * 覆盖 —— 门户画面、FBO 全部串台。维度必须进键。
     */
    private record DoorKey(ResourceKey<Level> dimension, BlockPos pos) {}

    /**
     * 一格门的离屏渲染缓存。
     *
     * <p>这里记录的 {@code sourceLevel / anchor / appearanceId / facing} 是
     * <b>上次烘焙网格时的输入</b>。任何一个变了才需要重扫世界重建网格。
     */
    private static final class CacheEntry {
        ClientLevel sourceLevel;
        BlockPos anchor;
        Identifier appearanceId;
        Direction facing;
        PortalMesh mesh;
        PortalFramebuffer framebuffer;
        PortalDoorHole.DoorHole hole;
        /** 烘焙该网格时预构建 level 的区块版本号。 */
        int chunkRevision;
        long lastMeshRebuildMs;

        // 网格不常变，对应的 VBO 也不需要每帧重建
        GpuBuffer meshBuffer;
        int meshQuadCount;

        // 门户平面的顶点是世界坐标，只有 hole / 窗口尺寸变化时才需要重传
        GpuBuffer quadBuffer;
        PortalDoorHole.DoorHole quadHole;
        int quadWidth;
        int quadHeight;
    }

    /**
     * 门户离屏渲染的缩放比例。
     *
     * <p>每个开着的门各需要一张颜色 + 深度纹理。全分辨率时门一多，显存和
     * 填充率都会线性增长；门户画面本来就会被门框裁掉大半，0.5 倍几乎看不出差别。
     */
    private static final float PORTAL_RENDER_SCALE = 0.5f;

    /**
     * 预构建 level 还在灌区块时，网格最多每 200ms 重烘焙一次。
     *
     * <p>区块是分帧写入的（见 {@code StpClientState#chunkRevision}），所以网格
     * 需要跟着更新；但每帧都重扫一遍 11 万个方块同样不可接受。灌完之后
     * 版本号不再变化，这里就彻底安静了 —— 不会像旧版那样每 250ms 无条件重扫。
     */
    private static final long MESH_REBUILD_INTERVAL_MS = 200L;

    private final Map<DoorKey, CacheEntry> cache = new HashMap<>();
    private final Set<TardisDoorBlockEntity> activeDoors = ConcurrentHashMap.newKeySet();

    /** 资源重载线程只置位；真正的 GPU 资源销毁留给渲染线程。 */
    private volatile boolean invalidateRequested;

    /**
     * 门洞集合的版本号。
     *
     * <p>遮罩渲染器把所有门洞合进一个 VBO，它需要知道"门洞变了没有"。
     * 网格重建、门被移除、缓存整体作废都会自增。
     */
    private long holeRevision;

    /** 门洞集合版本号；变化时遮罩 VBO 需要重建。 */
    public long holeRevision() {
        return holeRevision;
    }

    public static final RenderPipeline PORTAL_PLANE = RenderPipeline.builder()
            .withLocation("pipeline/portal_plane")
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build();

    static {
        RenderPipelines.register(PORTAL_PLANE);
    }

    private PortalViewManager() {}

    /**
     * 注册资源重载监听器。
     *
     * <p>方块模型换了而网格不换，门户里会一直显示旧贴图 / 旧几何。
     * 监听器本身不碰 GPU 资源，只把"需要重建"的意图交给渲染线程。
     */
    public static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath("doctor_m", "tardis_portal_meshes");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        PortalViewManager.get().requestInvalidate();
                    }
                });
    }

    public void trackActiveDoor(TardisDoorBlockEntity door) {
        activeDoors.add(door);
    }

    public void renderOffscreenPass() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (invalidateRequested) {
            invalidateRequested = false;
            disposeAll();
        }

        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        ClientLevel prepared = StpClientState.portalLevel();

        if (prepared == null) {
            activeDoors.clear();
            disposeAll();
            return;
        }

        // 门户还在画面里 → 续期预构建 level。否则挂满 30 秒后它会被判过期，
        // 玩家真正穿门时又退回原版加载界面（StpClientState.portalKeepAlive 的用途）。
        if (!activeDoors.isEmpty()) {
            StpClientState.portalKeepAlive();
        }

        cache.entrySet().removeIf(entry -> {
            DoorKey key = entry.getKey();
            if (!mc.level.dimension().equals(key.dimension())) {
                return dispose(entry.getValue());
            }
            BlockEntity be = mc.level.getBlockEntity(key.pos());
            boolean gone = !(be instanceof TardisDoorBlockEntity door)
                    || door.isRemoved()
                    || !door.isOpen();
            return gone && dispose(entry.getValue());
        });

        for (TardisDoorBlockEntity door : activeDoors) {
            if (door.isRemoved() || !door.isOpen()) continue;

            BlockPos counterpart = door.getCounterpartPos();
            if (counterpart == null) continue;
            if (!(door.getBlockState().getBlock() instanceof AbstractTardisDoorBlock)) continue;

            Direction facing = door.getBlockState().getValue(AbstractTardisDoorBlock.FACING);
            Identifier appearanceId = door.getAppearance();

            Level doorLevel = door.getLevel();
            if (doorLevel == null) continue;
            DoorKey key = new DoorKey(doorLevel.dimension(), door.getBlockPos());
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                entry = new CacheEntry();
                cache.put(key, entry);
            }

            // 只在这些输入变化时重扫世界。旧版每 250ms 无条件重建一次：
            // 一次半径 24 的扫描是 11 万多次 getBlockState + tesselate，
            // 而预构建 level 的方块内容根本不会变 —— 纯烧 CPU。
            long nowMs = System.currentTimeMillis();
            int revision = StpClientState.chunkRevision();

            boolean inputChanged = entry.sourceLevel != prepared
                    || !counterpart.equals(entry.anchor)
                    || entry.facing != facing
                    || !Objects.equals(entry.appearanceId, appearanceId);

            // 流式灌区块期间内容会变：版本号变了才允许重烘焙，且限频。
            boolean contentChanged = revision != entry.chunkRevision
                    && nowMs - entry.lastMeshRebuildMs >= MESH_REBUILD_INTERVAL_MS;

            if (entry.mesh == null || inputChanged || contentChanged) {
                entry.sourceLevel = prepared;
                entry.anchor = counterpart;
                entry.facing = facing;
                entry.appearanceId = appearanceId;
                entry.chunkRevision = revision;
                entry.lastMeshRebuildMs = nowMs;

                if (entry.meshBuffer != null) {
                    entry.meshBuffer.close();
                    entry.meshBuffer = null;
                }
                entry.meshQuadCount = 0;
                entry.mesh = PortalMesher.build(prepared, counterpart);

                TardisAppearance app = TardisAppearanceRegistry.get(appearanceId);
                TardisAsset.Bedrock asset = null;
                if (app != null) {
                    TardisAsset a = door.isExterior() ? app.exterior() : app.interior();
                    if (a instanceof TardisAsset.Bedrock b) asset = b;
                }
                entry.hole = PortalDoorHole.extract(door.getBlockPos(), facing, asset);
                holeRevision++;
            }

            // 没有 boti_portal 骨骼 / 没有网格 → 这个门不显示门户。
            // 注意：缓存必须保留，否则每帧都会被当成"缺 hole"重新烘焙一次。
            if (entry.hole == null || entry.mesh.isEmpty()) continue;

            if (entry.framebuffer == null) {
                entry.framebuffer = new PortalFramebuffer();
            }
            // 半分辨率：门户画面被门框裁掉大半，全分辨率画布纯属浪费
            entry.framebuffer.resize(
                    Math.max(1, (int) (mc.getWindow().getWidth() * PORTAL_RENDER_SCALE)),
                    Math.max(1, (int) (mc.getWindow().getHeight() * PORTAL_RENDER_SCALE)));

            doRenderToTarget(entry, door, camera);
        }
        activeDoors.clear();
    }

    private boolean dispose(CacheEntry entry) {
        closeQuietly(entry.meshBuffer);
        entry.meshBuffer = null;
        closeQuietly(entry.quadBuffer);
        entry.quadBuffer = null;
        if (entry.framebuffer != null) {
            entry.framebuffer.destroy();
            entry.framebuffer = null;
        }
        holeRevision++;
        return true;
    }

    private static void closeQuietly(GpuBuffer buffer) {
        if (buffer != null) {
            try {
                buffer.close();
            } catch (Exception ignored) {
                // 设备已经在销毁过程中时，关闭失败没有补救意义
            }
        }
    }

    private void disposeAll() {
        cache.values().forEach(this::dispose);
        cache.clear();
    }

    /** 渲染线程调用：立即销毁全部门户缓存。 */
    public void invalidateAll() {
        invalidateRequested = false;
        disposeAll();
        activeDoors.clear();
    }

    /** 任意线程调用：请求在下一帧销毁并重建门户缓存。 */
    public void requestInvalidate() {
        invalidateRequested = true;
    }

    private void doRenderToTarget(CacheEntry entry, TardisDoorBlockEntity door, CameraRenderState camera) {
        var target = entry.framebuffer.getTarget();
        var mc = Minecraft.getInstance();

        PortalCamera portalCam = new PortalCamera(
                door.getBlockPos(), door.getBlockState().getValue(AbstractTardisDoorBlock.FACING),
                door.getCounterpartPos(), door.getCounterpartFacing(),
                camera.pos, camera.yRot, camera.xRot, camera.projectionMatrix
        );

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.builder(() -> "Tardis Portal Offscreen")
                        .withColorAttachment(target.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 1)))
                        .withDepthAttachment(target.getDepthTextureView(), OptionalDouble.of(0.0))
                        .build())) {

            RenderSystem.bindDefaultUniforms(pass);

            var projection = RenderSystem.getProjectionMatrixBuffer();
            if (projection != null) pass.setUniform("Projection", projection);

            var transform = RenderSystem.getDynamicUniforms().writeTransform(portalCam.viewMatrix, new Vector4f(1,1,1,1), new Vector3f(), new Matrix4f());
            pass.setUniform("DynamicTransforms", transform);

            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("Sampler0", mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(), sampler);
            pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), sampler);

            var pipeline = RenderSystem.getCompiledPipeline(RenderPipelines.CUTOUT_BLOCK);
            pass.setPipeline(pipeline);

            submitMeshToPass(entry, pass);
        }
    }

    /**
     * 提交离屏网格。
     *
     * <p>网格本身只在门打开 / 换维度 / 灌区块时变，VBO 没必要每帧重建 ——
     * 旧版每帧都 `createBuffer` + `map` + `close`，是纯 GPU 驱动开销。
     */
    private void submitMeshToPass(CacheEntry entry, RenderPass pass) {
        if (entry.meshBuffer == null) {
            entry.meshQuadCount = entry.mesh.quads().size();
            entry.meshBuffer = buildMeshBuffer(entry.mesh);
        }
        if (entry.meshBuffer == null || entry.meshQuadCount == 0) return;

        pass.setVertexBuffer(0, entry.meshBuffer.slice());
        var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setIndexBuffer(autoIndices.getBuffer(entry.meshQuadCount * 4), autoIndices.type());
        pass.drawIndexed(entry.meshQuadCount * 6, 1, 0, 0, 0);
    }

    private static GpuBuffer buildMeshBuffer(PortalMesh mesh) {
        if (mesh.isEmpty()) return null;

        ByteBufferBuilder byteBuffer = new ByteBufferBuilder(mesh.quads().size() * 4 * 32);
        try {
            BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            for (PortalQuad q : mesh.quads()) {
                // 不要走 PortalQuad#vertices()：它每次调用都新建一个 4 元素数组
                addVertex(builder, q.a());
                addVertex(builder, q.b());
                addVertex(builder, q.c());
                addVertex(builder, q.d());
            }

            MeshData data = builder.build();
            if (data == null) return null;
            try {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                        () -> "portal_offscreen_vbo", 32 | 8 | 2, data.vertexBuffer().remaining());
                try (var view = buffer.map(false, true)) {
                    var src = data.vertexBuffer();
                    src.position(0);
                    view.data().put(src);
                }
                return buffer;
            } finally {
                data.close();
            }
        } finally {
            byteBuffer.close();
        }
    }

    private static void addVertex(BufferBuilder builder, PortalVertex v) {
        builder.addVertex(v.x(), v.y(), v.z())
                .setColor(v.color())
                .setUv(v.u(), v.v())
                .setLight(v.light());
    }

    public void renderPortalQuad(RenderPass pass) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        var projection = RenderSystem.getProjectionMatrixBuffer();
        if (projection != null) pass.setUniform("Projection", projection);

        // 顶点直接用世界坐标，相机位移放进 DynamicTransforms —— 这样顶点数据
        // 不会因为玩家走动每帧变化，VBO 可以一直复用。
        Matrix4f camMat = new Matrix4f(camera.viewRotationMatrix);
        camMat.translate((float) -camera.pos.x, (float) -camera.pos.y, (float) -camera.pos.z);
        var transform = RenderSystem.getDynamicUniforms().writeTransform(
                camMat, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        pass.setUniform("DynamicTransforms", transform);

        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();

        for (CacheEntry entry : cache.values()) {
            if (entry.framebuffer == null || entry.hole == null) continue;

            // hole 变了、窗口尺寸变了才需要重算 UV 并重传
            if (entry.quadBuffer == null
                    || entry.quadHole != entry.hole
                    || entry.quadWidth != winW
                    || entry.quadHeight != winH) {
                closeQuietly(entry.quadBuffer);
                entry.quadBuffer = buildQuadBuffer(entry.hole, winW, winH);
                entry.quadHole = entry.hole;
                entry.quadWidth = winW;
                entry.quadHeight = winH;
            }
            if (entry.quadBuffer == null) continue;

            var target = entry.framebuffer.getTarget();
            pass.setUniform("Sampler0", target.getColorTextureView(), sampler);
            pass.setPipeline(RenderSystem.getCompiledPipeline(PORTAL_PLANE));

            pass.setVertexBuffer(0, entry.quadBuffer.slice());
            var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            pass.setIndexBuffer(autoIndices.getBuffer(4), autoIndices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    private static GpuBuffer buildQuadBuffer(PortalDoorHole.DoorHole hole, int screenW, int screenH) {
        ByteBufferBuilder bb = new ByteBufferBuilder(256);
        try {
            BufferBuilder builder = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

            double w = hole.tr().subtract(hole.tl()).horizontalDistance();
            double h = Math.abs(hole.tl().y - hole.bl().y);
            float doorAspect = (float) (w / h);
            float screenAspect = (float) screenW / screenH;

            float uMin = 0f, uMax = 1f;
            float vMin = 0f, vMax = 1f;

            if (doorAspect < screenAspect) {
                float ratio = doorAspect / screenAspect;
                uMin = 0.5f - ratio / 2f;
                uMax = 0.5f + ratio / 2f;
            } else {
                float ratio = screenAspect / doorAspect;
                vMin = 0.5f - ratio / 2f;
                vMax = 0.5f + ratio / 2f;
            }

            putQuadVertex(builder, hole.tl(), uMin, vMax);
            putQuadVertex(builder, hole.bl(), uMin, vMin);
            putQuadVertex(builder, hole.br(), uMax, vMin);
            putQuadVertex(builder, hole.tr(), uMax, vMax);

            MeshData meshData = builder.build();
            if (meshData == null) return null;
            try {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                        () -> "portal_quad_vbo", 32 | 8 | 2, meshData.vertexBuffer().remaining());
                try (var view = buffer.map(false, true)) {
                    var src = meshData.vertexBuffer();
                    src.position(0);
                    view.data().put(src);
                }
                return buffer;
            } finally {
                meshData.close();
            }
        } finally {
            bb.close();
        }
    }

    private static void putQuadVertex(BufferBuilder builder, Vec3 worldPos, float u, float v) {
        builder.addVertex((float) worldPos.x, (float) worldPos.y, (float) worldPos.z)
                .setUv(u, v)
                .setColor(0xFFFFFFFF);
    }

    public java.util.Collection<PortalDoorHole.DoorHole> getActiveHoles() {
        java.util.List<PortalDoorHole.DoorHole> holes = new java.util.ArrayList<>(cache.size());
        for (CacheEntry entry : cache.values()) {
            if (entry.hole != null) holes.add(entry.hole);
        }
        return holes;
    }
}
