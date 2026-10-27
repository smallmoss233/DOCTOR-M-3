package doctor_m.client.tardis.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.block.entity.TardisDoorBlockEntity;
import doctor_m.client.stp.StpClientState;
import doctor_m.client.tardis.render.TardisConsoleRenderState;
import doctor_m.client.tardis.render.TardisConsoleRenderer;
import doctor_m.client.tardis.render.TardisDoorRenderState;
import doctor_m.client.tardis.render.TardisDoorRenderer;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PortalViewManager {

    private static PortalViewManager INSTANCE;
    public static PortalViewManager get() {
        if (INSTANCE == null) INSTANCE = new PortalViewManager();
        return INSTANCE;
    }

    private static final long REBUILD_INTERVAL_MS = 250L;
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0 * 64.0;

    // 把对面的世界往前拉一点，刚好在 Depth Mask 前面，完美显示
    private static final double PORTAL_DEPTH_OFFSET = -0.1;

    private static final class CacheEntry {
        ClientLevel sourceLevel;
        PortalTransform transform;
        PortalMesh mesh;
        PortalClipper.DoorHole hole;
        AABB holeAABB;
        long builtAtMs;
    }

    private final Map<BlockPos, CacheEntry> cache = new HashMap<>();

    private PortalViewManager() {}

    public void submitPortal(TardisDoorRenderState state, PoseStack poseStack,
                             SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.blockPos == null) return;
        if (camera.pos.distanceToSqr(Vec3.atCenterOf(state.blockPos)) > MAX_RENDER_DISTANCE_SQ) return;

        ClientLevel prepared = StpClientState.portalLevel();
        if (prepared == null) return;

        StpClientState.portalKeepAlive();

        CacheEntry entry = cache.get(state.blockPos);
        long now = Util.getMillis();
        boolean needRebuild = entry == null || entry.sourceLevel != prepared || now - entry.builtAtMs > REBUILD_INTERVAL_MS;

        if (needRebuild) {
            entry = buildEntry(state, prepared, now);
            if (entry == null) return;
            cache.put(state.blockPos, entry);
        }

        // ★ 核心需求：如果你绕到了门洞背面，直接停止渲染！
        Vec3 holeCenter = entry.hole.center();
        Vec3 holeNormal = entry.hole.normal();
        Vec3 toCamera = camera.pos.subtract(holeCenter);

        if (toCamera.dot(holeNormal) <= 0.0) {
            return; // 在背面，什么都不画
        }

        // 用四面刀把对面世界切得严丝合缝
        List<PortalQuad> visible = PortalClipper.clip(
                entry.mesh, camera.cullFrustum,
                entry.transform.planePoint(), entry.transform.planeNormal(),
                entry.hole);

        if (!visible.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.cutoutMovingBlock(),
                    (pose, vc) -> {
                        for (PortalQuad q : visible) {
                            emitVertex(pose, vc, state.blockPos, q.a());
                            emitVertex(pose, vc, state.blockPos, q.b());
                            emitVertex(pose, vc, state.blockPos, q.c());
                            emitVertex(pose, vc, state.blockPos, q.d());
                        }
                    });
        }

        renderBlockEntities(state, entry, poseStack, collector, camera);
        renderEntities(state, entry, poseStack, collector, camera);
    }

    private CacheEntry buildEntry(TardisDoorRenderState state, ClientLevel prepared, long now) {
        BlockPos counterpartPos = state.counterpartPos;
        if (counterpartPos == null) return null;

        var localFacing = state.facing != null ? state.facing : Direction.NORTH;
        var remoteFacing = state.counterpartFacing != null ? state.counterpartFacing : Direction.NORTH;

        PortalTransform transform = PortalTransform.fromDoorToDoor(
                state.blockPos, localFacing, counterpartPos, remoteFacing, PORTAL_DEPTH_OFFSET);

        PortalMesh remoteMesh = PortalMesher.build(prepared, counterpartPos);
        if (remoteMesh.isEmpty()) return null;

        PortalMesh localMesh = transformMesh(transform, remoteMesh);

        CacheEntry e = new CacheEntry();
        e.sourceLevel = prepared;
        e.transform = transform;
        e.mesh = localMesh;
        e.builtAtMs = now;

        TardisAsset.Bedrock bedrockAsset = resolveBedrockAsset(state);
        e.hole = PortalDoorHole.extract(state.blockPos, localFacing, bedrockAsset);

        // 计算门洞四个角点的极值
        double minX = Math.min(Math.min(e.hole.tl().x, e.hole.tr().x), Math.min(e.hole.bl().x, e.hole.br().x));
        double maxX = Math.max(Math.max(e.hole.tl().x, e.hole.tr().x), Math.max(e.hole.bl().x, e.hole.br().x));

        double minY = Math.min(Math.min(e.hole.tl().y, e.hole.tr().y), Math.min(e.hole.bl().y, e.hole.br().y));
        double maxY = Math.max(Math.max(e.hole.tl().y, e.hole.tr().y), Math.max(e.hole.bl().y, e.hole.br().y));

        double minZ = Math.min(Math.min(e.hole.tl().z, e.hole.tr().z), Math.min(e.hole.bl().z, e.hole.br().z));
        double maxZ = Math.max(Math.max(e.hole.tl().z, e.hole.tr().z), Math.max(e.hole.bl().z, e.hole.br().z));

        // 往门内延伸 32 格，用于覆盖可能出现在门后的实体
        Vec3 n = e.hole.normal().scale(-32);

        // 组合成最终的 AABB
        double boxMinX = Math.min(minX, minX + n.x);
        double boxMaxX = Math.max(maxX, maxX + n.x);
        double boxMinY = Math.min(minY, minY + n.y);
        double boxMaxY = Math.max(maxY, maxY + n.y);
        double boxMinZ = Math.min(minZ, minZ + n.z);
        double boxMaxZ = Math.max(maxZ, maxZ + n.z);

        // 稍微膨胀 1 格，防止实体在边缘闪烁
        e.holeAABB = new AABB(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ).inflate(1.0);

        return e;
    }

    private static PortalMesh transformMesh(PortalTransform t, PortalMesh remote) {
        List<PortalQuad> local = new ArrayList<>(remote.quads().size());
        for (PortalQuad q : remote.quads()) {
            local.add(new PortalQuad(
                    transformVertex(t, q.a()),
                    transformVertex(t, q.b()),
                    transformVertex(t, q.c()),
                    transformVertex(t, q.d())));
        }
        return new PortalMesh(local);
    }

    private static PortalVertex transformVertex(PortalTransform t, PortalVertex v) {
        Vec3 p = t.transform(new Vec3(v.x(), v.y(), v.z()));
        return new PortalVertex((float) p.x, (float) p.y, (float) p.z,
                v.color(), v.u(), v.v(), v.light(), v.nx(), v.ny(), v.nz());
    }

    private static void emitVertex(PoseStack.Pose pose, VertexConsumer vc, BlockPos doorPos, PortalVertex v) {
        vc.addVertex(pose, v.x() - doorPos.getX(), v.y() - doorPos.getY(), v.z() - doorPos.getZ());
        vc.setColor(v.color());
        vc.setUv(v.u(), v.v());
        vc.setLight(v.light());
        vc.setNormal(pose, v.nx(), v.ny(), v.nz());
    }

    public java.util.Collection<PortalClipper.DoorHole> getActiveHoles() {
        java.util.List<PortalClipper.DoorHole> holes = new java.util.ArrayList<>();
        for (CacheEntry entry : cache.values()) {
            if (entry.hole != null) holes.add(entry.hole);
        }
        return holes;
    }

    private void renderBlockEntities(TardisDoorRenderState localState, CacheEntry entry,
                                     PoseStack poseStack, SubmitNodeCollector collector,
                                     CameraRenderState camera) {
        ClientLevel level = entry.sourceLevel;
        BlockPos center = localState.counterpartPos;
        if (center == null) return;

        int r = 16;
        BlockPos min = center.offset(-r, -r, -r);
        BlockPos max = center.offset(r, r, r);
        Vec3 camPos = camera.pos;
        org.joml.Matrix4f transformMatrix = entry.transform.matrix();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || be.isRemoved()) continue;

            Vec3 localCenter = entry.transform.transform(Vec3.atCenterOf(pos));
            if (!entry.holeAABB.contains(localCenter)) continue;

            int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
            int skyLight = level.getBrightness(LightLayer.SKY, pos);
            int packedLight = (blockLight << 4) | (skyLight << 20);

            poseStack.pushPose();
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
            poseStack.mulPose(transformMatrix);
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());

            try {
                if (be instanceof TardisDoorBlockEntity door) {
                    Object renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(door);
                    if (renderer instanceof TardisDoorRenderer doorRenderer) {
                        TardisDoorRenderState state = doorRenderer.createRenderState();
                        doorRenderer.extractRenderState(door, state, 1.0f, camera.pos, null);
                        state.counterpartPos = null;
                        state.lightCoords = packedLight;
                        doorRenderer.submit(state, poseStack, collector, camera);
                    }
                }
                if (be instanceof TardisConsoleBlockEntity console) {
                    Object renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(console);
                    if (renderer instanceof TardisConsoleRenderer consoleRenderer) {
                        TardisConsoleRenderState state = consoleRenderer.createRenderState();
                        consoleRenderer.extractRenderState(console, state, 1.0f, camera.pos, null);
                        state.lightCoords = packedLight;
                        consoleRenderer.submit(state, poseStack, collector, camera);
                    }
                }
            } catch (Throwable ignored) {} finally {
                poseStack.popPose();
            }
        }
    }

    private void renderEntities(TardisDoorRenderState localState, CacheEntry entry,
                                PoseStack poseStack, SubmitNodeCollector collector,
                                CameraRenderState camera) {
        ClientLevel level = entry.sourceLevel;
        BlockPos center = localState.counterpartPos;
        if (center == null) return;

        Vec3 camPos = camera.pos;
        org.joml.Matrix4f transformMatrix = entry.transform.matrix();
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || entity.isRemoved() || entity.blockPosition().distSqr(center) > 32 * 32) continue;

            Vec3 localCenter = entry.transform.transform(entity.position());
            if (!entry.holeAABB.contains(localCenter)) continue;

            float partialTicks = 1.0f;
            double x = Mth.lerp(partialTicks, entity.xOld, entity.getX());
            double y = Mth.lerp(partialTicks, entity.yOld, entity.getY());
            double z = Mth.lerp(partialTicks, entity.zOld, entity.getZ());

            try {
                poseStack.pushPose();
                poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
                poseStack.mulPose(transformMatrix);
                poseStack.translate(x, y, z);

                var renderState = dispatcher.extractEntity(entity, partialTicks);
                if (renderState != null) {
                    dispatcher.submit(renderState, camera, 0, 0, 0, poseStack, collector);
                }
                poseStack.popPose();
            } catch (Throwable ignored) {}
        }
    }

    private static TardisAsset.Bedrock resolveBedrockAsset(TardisDoorRenderState state) {
        if (state.appearanceId == null) return null;
        TardisAppearance app = TardisAppearanceRegistry.get(state.appearanceId);
        if (app == null) return null;
        TardisAsset a = state.exterior ? app.exterior() : app.interior();
        return a instanceof TardisAsset.Bedrock b ? b : null;
    }

    public void invalidateAll() {
        cache.clear();
    }
}