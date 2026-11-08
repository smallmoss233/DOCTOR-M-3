package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
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
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
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

    private record DoorKey(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final class CacheEntry {
        ClientLevel sourceLevel;
        BlockPos anchor;
        Identifier appearanceId;
        Direction facing;
        PortalMesh mesh;
        PortalFramebuffer framebuffer;

        /**
         * 当前门的门洞（当前维度，主 pass quad 用）。
         * 玩家看这扇门，FBO 就贴在这个门洞的世界坐标矩形上。
         */
        PortalDoorHole.DoorHole hole;

        /**
         * 对面门的门洞（预构建维度，FBO 视锥用）。
         *
         * <p><b>为什么必须有这个字段</b>：虚拟相机的位置由 {@link PortalCamera}
         * 算出，物理上位于<b>对面维度</b>（对面门中心附近）。FBO 要渲染的是
         * "虚拟相机透过对面门洞看到对面维度内部"的画面，视锥必须钉在
         * <b>对面门洞</b>上。用当前门的门洞会让视锥对准当前维度的坐标——
         * 跨维度坐标差导致相机看到"对面维度某个虚空方向"，画面完全错位。
         */
        PortalDoorHole.DoorHole remoteHole;

        int chunkRevision;
        long lastMeshRebuildMs;

        GpuBuffer meshBuffer;
        int meshQuadCount;

        GpuBuffer quadBuffer;
        PortalDoorHole.DoorHole quadHole;
        int quadWidth;
        int quadHeight;
    }

    private static final float PORTAL_RENDER_SCALE = 0.5f;
    private static final long MESH_REBUILD_INTERVAL_MS = 200L;
    private static final int MAX_CACHED_PORTALS = 8;

    private final LinkedHashMap<DoorKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<DoorKey, CacheEntry> eldest) {
            if (size() > MAX_CACHED_PORTALS) {
                dispose(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private final Set<TardisDoorBlockEntity> activeDoors = ConcurrentHashMap.newKeySet();

    private volatile boolean invalidateRequested;
    private long holeRevision;

    public long holeRevision() {
        return holeRevision;
    }

    private ProjectionMatrixBuffer portalProjectionBuffer;

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

        if (portalProjectionBuffer == null) {
            portalProjectionBuffer = new ProjectionMatrixBuffer("Tardis Portal Projection");
        }

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

            long nowMs = System.currentTimeMillis();
            int revision = StpClientState.chunkRevision();

            boolean inputChanged = entry.sourceLevel != prepared
                    || !counterpart.equals(entry.anchor)
                    || entry.facing != facing
                    || !Objects.equals(entry.appearanceId, appearanceId);

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

                // 当前门的门洞（主 pass quad 用）
                TardisAppearance app = TardisAppearanceRegistry.get(appearanceId);
                TardisAsset.Bedrock asset = null;
                if (app != null) {
                    TardisAsset a = door.isExterior() ? app.exterior() : app.interior();
                    if (a instanceof TardisAsset.Bedrock b) asset = b;
                }
                entry.hole = PortalDoorHole.extract(door.getBlockPos(), facing, asset);

                // ★ 对面门的门洞（FBO 视锥用）：以对面 asset + 对面 pos/facing 提取
                TardisAsset.Bedrock remoteAsset = null;
                if (app != null) {
                    TardisAsset a = door.isExterior() ? app.interior() : app.exterior();
                    if (a instanceof TardisAsset.Bedrock b) remoteAsset = b;
                }
                entry.remoteHole = PortalDoorHole.extract(
                        counterpart, door.getCounterpartFacing(), remoteAsset);

                holeRevision++;
            }

            // 主 pass 用 hole；FBO 视锥用 remoteHole。两者都非空才渲染。
            if (entry.hole == null || entry.remoteHole == null || entry.mesh.isEmpty()) continue;

            if (entry.framebuffer == null) {
                entry.framebuffer = new PortalFramebuffer();
            }
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
            }
        }
    }

    private void disposeAll() {
        cache.values().forEach(this::dispose);
        cache.clear();
    }

    public void invalidateAll() {
        invalidateRequested = false;
        disposeAll();
        activeDoors.clear();
    }

    public void requestInvalidate() {
        invalidateRequested = true;
    }

    private void doRenderToTarget(CacheEntry entry, TardisDoorBlockEntity door, CameraRenderState camera) {
        var target = entry.framebuffer.getTarget();
        var mc = Minecraft.getInstance();

        PortalCamera portalCam = new PortalCamera(
                door.getBlockPos(), door.getBlockState().getValue(AbstractTardisDoorBlock.FACING),
                door.getCounterpartPos(), door.getCounterpartFacing(),
                camera.pos, camera.yRot, camera.xRot
        );

        // 只用 remoteHole
        PortalFrustum frustum = new PortalFrustum(portalCam, entry.remoteHole, camera.depthFar);

        // ★ 新增：如果当前视角太偏，导致视锥畸变，直接跳过这一帧
        if (!frustum.valid) {
            return;
        }

        GpuBufferSlice projectionSlice = portalProjectionBuffer.getBuffer(frustum.projectionMatrix);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.builder(() -> "Tardis Portal Offscreen")
                        .withColorAttachment(target.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 1)))
                        .withDepthAttachment(target.getDepthTextureView(), OptionalDouble.of(0.0))
                        .build())) {

            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projectionSlice);

            var transform = RenderSystem.getDynamicUniforms().writeTransform(
                    portalCam.viewMatrix, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            pass.setUniform("DynamicTransforms", transform);

            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("Sampler0", mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(), sampler);
            pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), sampler);

            var pipeline = RenderSystem.getCompiledPipeline(RenderPipelines.CUTOUT_BLOCK);
            pass.setPipeline(pipeline);

            submitMeshToPass(entry, pass);
        }
    }

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
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(v.light())
                .setNormal(v.nx(), v.ny(), v.nz());
    }

    public void renderPortalQuad(RenderPass pass) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        var projection = RenderSystem.getProjectionMatrixBuffer();
        if (projection != null) pass.setUniform("Projection", projection);

        Matrix4f camMat = new Matrix4f(camera.viewRotationMatrix);
        camMat.translate((float) -camera.pos.x, (float) -camera.pos.y, (float) -camera.pos.z);
        var transform = RenderSystem.getDynamicUniforms().writeTransform(
                camMat, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        pass.setUniform("DynamicTransforms", transform);

        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();

        for (CacheEntry entry : cache.values()) {
            if (entry.framebuffer == null || entry.hole == null) continue;

            if (entry.quadBuffer == null
                    || entry.quadHole != entry.hole
                    || entry.quadWidth != winW
                    || entry.quadHeight != winH) {
                closeQuietly(entry.quadBuffer);
                entry.quadBuffer = buildQuadBuffer(entry.hole);
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

    private static GpuBuffer buildQuadBuffer(PortalDoorHole.DoorHole hole) {
        ByteBufferBuilder bb = new ByteBufferBuilder(256);
        try {
            BufferBuilder builder = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

            putQuadVertex(builder, hole.tl(), 0f, 1f);
            putQuadVertex(builder, hole.bl(), 0f, 0f);
            putQuadVertex(builder, hole.br(), 1f, 0f);
            putQuadVertex(builder, hole.tr(), 1f, 1f);

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

    public Collection<PortalDoorHole.DoorHole> getActiveHoles() {
        List<PortalDoorHole.DoorHole> holes = new ArrayList<>(cache.size());
        for (CacheEntry entry : cache.values()) {
            if (entry.hole != null) holes.add(entry.hole);
        }
        return holes;
    }
}