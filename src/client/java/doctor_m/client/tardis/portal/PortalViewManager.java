package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.stp.StpClientState;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
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

    private static final class CacheEntry {
        ClientLevel sourceLevel;
        PortalMesh mesh;
        PortalFramebuffer framebuffer;
        PortalDoorHole.DoorHole hole;
        long builtAtMs;
    }

    private final Map<BlockPos, CacheEntry> cache = new HashMap<>();
    private final Set<TardisDoorBlockEntity> activeDoors = ConcurrentHashMap.newKeySet();

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

    public void trackActiveDoor(TardisDoorBlockEntity door) {
        activeDoors.add(door);
    }

    public void renderOffscreenPass() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        ClientLevel prepared = StpClientState.portalLevel();

        if (prepared == null) {
            activeDoors.clear();
            cache.values().forEach(e -> {
                if (e.framebuffer != null) e.framebuffer.destroy();
            });
            cache.clear();
            return;
        }

        cache.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            CacheEntry ce = entry.getValue();
            BlockEntity be = mc.level.getBlockEntity(pos);

            boolean shouldRemove = !(be instanceof TardisDoorBlockEntity door)
                    || door.isRemoved()
                    || !door.isOpen()
                    || ce.hole == null;

            if (shouldRemove) {
                if (ce.framebuffer != null) {
                    ce.framebuffer.destroy();
                }
                return true;
            }
            return false;
        });

        for (var door : activeDoors) {
            if (door.isRemoved() || !door.isOpen()) continue;

            CacheEntry entry = cache.computeIfAbsent(door.getBlockPos(), k -> new CacheEntry());
            entry.framebuffer = entry.framebuffer == null ? new PortalFramebuffer() : entry.framebuffer;
            entry.framebuffer.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());

            if (entry.mesh == null || Util.getMillis() - entry.builtAtMs > 250) {
                entry.mesh = PortalMesher.build(prepared, door.getCounterpartPos());

                TardisAppearance app = TardisAppearanceRegistry.get(door.getAppearance());
                TardisAsset.Bedrock asset = null;
                if (app != null) {
                    TardisAsset a = door.isExterior() ? app.exterior() : app.interior();
                    if (a instanceof TardisAsset.Bedrock b) asset = b;
                }

                entry.hole = PortalDoorHole.extract(door.getBlockPos(),
                        door.getBlockState().getValue(doctor_m.block.AbstractTardisDoorBlock.FACING), asset);
                entry.builtAtMs = Util.getMillis();
            }

            // ★ 如果没有 hole，直接不渲染离屏画面
            if (entry.hole != null && !entry.mesh.isEmpty()) {
                doRenderToTarget(entry, door, camera);
            }
        }
        activeDoors.clear();
    }

    private void doRenderToTarget(CacheEntry entry, TardisDoorBlockEntity door, CameraRenderState camera) {
        var target = entry.framebuffer.getTarget();
        var mc = Minecraft.getInstance();

        PortalCamera portalCam = new PortalCamera(
                door.getBlockPos(), door.getBlockState().getValue(doctor_m.block.AbstractTardisDoorBlock.FACING),
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

            submitMeshToPass(entry.mesh, pass);
        }
    }

    private void submitMeshToPass(PortalMesh mesh, RenderPass pass) {
        ByteBufferBuilder byteBuffer = new ByteBufferBuilder(mesh.quads().size() * 4 * 32);
        BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);

        for (PortalQuad q : mesh.quads()) {
            for (PortalVertex v : q.vertices()) {
                builder.addVertex(v.x(), v.y(), v.z())
                        .setColor(v.color())
                        .setUv(v.u(), v.v())
                        .setLight(v.light());
            }
        }

        MeshData data = builder.build();
        if (data != null) {
            GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "portal_offscreen_vbo", 32 | 8 | 2, data.vertexBuffer().remaining());
            try (var view = buf.map(false, true)) {
                var src = data.vertexBuffer();
                src.position(0);
                view.data().put(src);
            }
            pass.setVertexBuffer(0, buf.slice());
            var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            pass.setIndexBuffer(autoIndices.getBuffer(mesh.quads().size() * 4), autoIndices.type());
            pass.drawIndexed(mesh.quads().size() * 6, 1, 0, 0, 0);
            buf.close();
            data.close();
        }
        byteBuffer.close();
    }

    public void renderPortalQuad(RenderPass pass) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        var projection = RenderSystem.getProjectionMatrixBuffer();
        if (projection != null) pass.setUniform("Projection", projection);

        var transform = RenderSystem.getDynamicUniforms().writeTransform(camera.viewRotationMatrix, new Vector4f(1,1,1,1), new Vector3f(), new Matrix4f());
        pass.setUniform("DynamicTransforms", transform);

        for (CacheEntry entry : cache.values()) {
            if (entry.framebuffer == null || entry.hole == null) continue;

            var target = entry.framebuffer.getTarget();
            pass.setUniform("Sampler0", target.getColorTextureView(), sampler);

            pass.setPipeline(RenderSystem.getCompiledPipeline(PORTAL_PLANE));

            ByteBufferBuilder bb = new ByteBufferBuilder(256);
            BufferBuilder builder = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

            double w = entry.hole.tr().subtract(entry.hole.tl()).horizontalDistance();
            double h = Math.abs(entry.hole.tl().y - entry.hole.bl().y);
            float doorAspect = (float)(w / h);
            float screenAspect = (float)mc.getWindow().getWidth() / mc.getWindow().getHeight();

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

            putQuadVertex(builder, entry.hole.tl(), camera, uMin, vMax);
            putQuadVertex(builder, entry.hole.bl(), camera, uMin, vMin);
            putQuadVertex(builder, entry.hole.br(), camera, uMax, vMin);
            putQuadVertex(builder, entry.hole.tr(), camera, uMax, vMax);

            MeshData meshData = builder.build();
            if (meshData != null) {
                GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "portal_quad_vbo", 32 | 8 | 2, meshData.vertexBuffer().remaining());
                try (var view = buf.map(false, true)) {
                    var src = meshData.vertexBuffer();
                    src.position(0);
                    view.data().put(src);
                }
                pass.setVertexBuffer(0, buf.slice());
                var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                pass.setIndexBuffer(autoIndices.getBuffer(4), autoIndices.type());
                pass.drawIndexed(6, 1, 0, 0, 0);
                buf.close();
                meshData.close();
            }
            bb.close();
        }
    }

    private void putQuadVertex(BufferBuilder builder, Vec3 worldPos, CameraRenderState camera, float u, float v) {
        builder.addVertex((float)(worldPos.x - camera.pos.x), (float)(worldPos.y - camera.pos.y), (float)(worldPos.z - camera.pos.z))
                .setUv(u, v)
                .setColor(0xFFFFFFFF);
    }

    public java.util.Collection<PortalDoorHole.DoorHole> getActiveHoles() {
        java.util.List<PortalDoorHole.DoorHole> holes = new java.util.ArrayList<>();
        for (CacheEntry entry : cache.values()) {
            if (entry.hole != null) holes.add(entry.hole);
        }
        return holes;
    }

    public void invalidateAll() {
        cache.values().forEach(e -> {
            if (e.framebuffer != null) e.framebuffer.destroy();
        });
        cache.clear();
        activeDoors.clear();
    }
}