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

import java.util.Collection;

/**
 * 把当前所有门洞写进主渲染 pass 的深度遮罩。
 *
 * <p>门洞集合是低频变化的，所以整个遮罩合成一个 VBO 缓存；只有
 * {@link PortalViewManager#holeRevision()} 变化（开门 / 关门 / 换维度 /
 * 网格重建）时才重传。旧版每帧都新建并销毁一次 GPU 缓冲。
 */
public class PortalMaskRenderer {

    private static GpuBuffer buffer;
    private static long bufferRevision = Long.MIN_VALUE;
    private static int quadCount;

    public static void renderMask(RenderPass pass, CameraRenderState camera) {
        Collection<PortalDoorHole.DoorHole> holes = PortalViewManager.get().getActiveHoles();
        if (holes.isEmpty()) {
            closeBuffer();
            return;
        }

        long revision = PortalViewManager.get().holeRevision();
        if (buffer == null || revision != bufferRevision) {
            closeBuffer();
            buffer = buildBuffer(holes);
            bufferRevision = revision;
            quadCount = holes.size();
        }
        if (buffer == null) return;

        var pipeline = RenderSystem.getCompiledPipeline(RenderPipelines.WATER_MASK);
        pass.setPipeline(pipeline);

        Matrix4f camMat = new Matrix4f(camera.viewRotationMatrix);
        camMat.translate((float) -camera.pos.x, (float) -camera.pos.y, (float) -camera.pos.z);

        var transform = RenderSystem.getDynamicUniforms().writeTransform(
                camMat,
                new Vector4f(1, 1, 1, 1),
                new Vector3f(0, 0, 0),
                new Matrix4f());
        pass.setUniform("DynamicTransforms", transform);

        pass.setVertexBuffer(0, buffer.slice());
        var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setIndexBuffer(autoIndices.getBuffer(quadCount * 4), autoIndices.type());
        pass.drawIndexed(quadCount * 6, 1, 0, 0, 0);
    }

    private static GpuBuffer buildBuffer(Collection<PortalDoorHole.DoorHole> holes) {
        ByteBufferBuilder byteBuffer = new ByteBufferBuilder(256);
        try {
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);

            for (PortalDoorHole.DoorHole hole : holes) {
                putVertex(builder, hole.tl());
                putVertex(builder, hole.tr());
                putVertex(builder, hole.br());
                putVertex(builder, hole.bl());
            }

            MeshData meshData = builder.build();
            if (meshData == null) return null;
            try {
                GpuBuffer buf = RenderSystem.getDevice().createBuffer(
                        () -> "portal_mask_vbo", 32 | 8 | 2,
                        meshData.vertexBuffer().remaining());
                try (var view = buf.map(false, true)) {
                    var src = meshData.vertexBuffer();
                    src.position(0);
                    view.data().put(src);
                }
                return buf;
            } finally {
                meshData.close();
            }
        } finally {
            byteBuffer.close();
        }
    }

    private static void closeBuffer() {
        if (buffer != null) {
            try {
                buffer.close();
            } catch (Exception ignored) {
                // 设备销毁过程中关闭失败无补救意义
            }
            buffer = null;
        }
        bufferRevision = Long.MIN_VALUE;
        quadCount = 0;
    }

    private static void putVertex(BufferBuilder builder, Vec3 pos) {
        builder.addVertex((float) pos.x, (float) pos.y, (float) pos.z);
    }
}
