package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class PortalMaskRenderer {
    public static void renderMask(RenderPass pass, CameraRenderState camera) {
        var holes = PortalViewManager.get().getActiveHoles();
        if (holes.isEmpty()) return;

        // 直接用原版 WATER_MASK：只写深度，不写颜色
        var pipeline = RenderSystem.getCompiledPipeline(RenderPipelines.WATER_MASK);
        pass.setPipeline(pipeline);

        // ★ 核心修复：正确的视图矩阵是 R * T（先平移，再旋转）
        Matrix4f camMat = new Matrix4f(camera.viewRotationMatrix);
        camMat.translate((float)-camera.pos.x, (float)-camera.pos.y, (float)-camera.pos.z);

        var transform = RenderSystem.getDynamicUniforms().writeTransform(
                camMat,
                new Vector4f(1, 1, 1, 1),
                new Vector3f(0, 0, 0),
                new Matrix4f()
        );
        pass.setUniform("DynamicTransforms", transform);

        ByteBufferBuilder byteBuffer = new ByteBufferBuilder(256);
        BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);

        for (PortalClipper.DoorHole hole : holes) {
            // 推入世界坐标
            putVertex(builder, hole.tl());
            putVertex(builder, hole.bl());
            putVertex(builder, hole.br());
            putVertex(builder, hole.tr());
        }

        MeshData meshData = builder.build();
        if (meshData != null) {
            var device = RenderSystem.getDevice();

            // 32 = USAGE_VERTEX, 8 = USAGE_COPY_DST, 2 = USAGE_MAP_WRITE
            GpuBuffer buf = device.createBuffer(() -> "portal_mask_vbo", 32 | 8 | 2, meshData.vertexBuffer().remaining());

            try (var view = buf.map(false, true)) {
                var src = meshData.vertexBuffer();
                src.position(0);
                view.data().put(src);
            }

            pass.setVertexBuffer(0, buf.slice());
            var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            pass.setIndexBuffer(autoIndices.getBuffer(holes.size() * 4), autoIndices.type());

            pass.drawIndexed(holes.size() * 6, 1, 0, 0, 0);

            buf.close();
            meshData.close();
        }
        byteBuffer.close();
    }

    private static void putVertex(BufferBuilder builder, Vec3 pos) {
        builder.addVertex((float)pos.x, (float)pos.y, (float)pos.z);
    }
}