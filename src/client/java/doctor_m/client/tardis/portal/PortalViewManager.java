package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.stp.StpClientState;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
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
            return;
        }

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

            if (!entry.mesh.isEmpty() && entry.hole != null) {
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
                camera.pos, camera.viewRotationMatrix, camera.projectionMatrix
        );

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.builder(() -> "Tardis Portal Offscreen")
                        .withColorAttachment(target.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))
                        .withDepthAttachment(target.getDepthTextureView(), OptionalDouble.of(1.0))
                        .build())) {

            RenderSystem.bindDefaultUniforms(pass);

            var projection = RenderSystem.getProjectionMatrixBuffer();
            if (projection != null) pass.setUniform("Projection", projection);

            // ★ 这里完全使用包含平移的 viewMatrix（表示虚拟相机在对面的位置）
            var transform = RenderSystem.getDynamicUniforms().writeTransform(portalCam.viewMatrix, new Vector4f(1,1,1,1), new Vector3f(), new Matrix4f());
            pass.setUniform("DynamicTransforms", transform);

            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("Sampler0", mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(), sampler);
            pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), sampler);

            var pipeline = RenderSystem.getCompiledPipeline(RenderPipelines.CUTOUT_BLOCK);
            pass.setPipeline(pipeline);

            // ★ 提交绝对坐标的 Mesh
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

        // ★ 主世界相机矩阵：包含平移。这要求顶点必须传绝对世界坐标。
        Matrix4f camMat = new Matrix4f(camera.viewRotationMatrix);
        camMat.translate((float)-camera.pos.x, (float)-camera.pos.y, (float)-camera.pos.z);
        var transform = RenderSystem.getDynamicUniforms().writeTransform(camMat, new Vector4f(1,1,1,1), new Vector3f(), new Matrix4f());
        pass.setUniform("DynamicTransforms", transform);

        for (CacheEntry entry : cache.values()) {
            if (entry.framebuffer == null || entry.hole == null) continue;

            var target = entry.framebuffer.getTarget();
            pass.setUniform("Sampler0", target.getColorTextureView(), sampler);

            // ★ 使用原版最稳的 GUI_TEXTURED 渲染贴图
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.GUI_TEXTURED));

            ByteBufferBuilder bb = new ByteBufferBuilder(256);
            BufferBuilder builder = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

            // ★ 必须传 QUADS，并严格保证逆时针顺序，绝对不出现大三角形
            putQuadVertex(builder, entry.hole.tl(), 0, 0);
            putQuadVertex(builder, entry.hole.bl(), 0, 1);
            putQuadVertex(builder, entry.hole.br(), 1, 1);
            putQuadVertex(builder, entry.hole.tr(), 1, 0);

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

    private void putQuadVertex(BufferBuilder builder, Vec3 worldPos, float u, float v) {
        // ★ 传入绝对坐标
        builder.addVertex((float)worldPos.x, (float)worldPos.y, (float)worldPos.z)
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
        cache.clear();
        activeDoors.clear();
    }
}