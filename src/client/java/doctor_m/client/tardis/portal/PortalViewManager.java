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
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
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
         * <p>虚拟相机物理上位于对面维度（对面门洞附近），视锥必须钉在
         * 对面门洞上；用当前门的门洞会让视锥对准当前维度坐标，画面完全错位。
         */
        PortalDoorHole.DoorHole remoteHole;

        /** 对面门朝向（与 remoteHole 同时提取，PortalCamera 构造用）。 */
        Direction remoteFacing;

        int chunkRevision;

        /** 上次烘焙网格时的 skyFactor；变化超过阈值触发重烘（时间流动也要重烘）。 */
        float lastSkyFactor = 1f;

        long lastMeshRebuildMs;
        GpuBuffer meshBuffer;
        int meshQuadCount;
        GpuBuffer quadBuffer;
        PortalDoorHole.DoorHole quadHole;
        float lastQuadAlpha = -1f;

        /** 上一帧贴回 quad 用的 UV（压平为 [u0,v0,u1,v1,...]），用于判断是否需要重建 quad buffer。 */
        float[] lastQuadUvs;

        /**
         * 本帧 FBO 实际渲染时使用的视锥投影矩阵（offscreen 阶段写入），
         * 主 pass 贴回 quad 时用它对对面门洞角点做投影，算出正确的采样 UV。
         */
        Matrix4f frustumProj;

        /** 本帧 FBO 实际渲染时使用的虚拟相机视图矩阵。 */
        Matrix4f portalView;

        /** 本帧淡入透明度快照（offscreen 阶段从 BE 抓取，quad 与 mask 共用）。 */
        float fadeAlpha = 1.0f;

        /**
         * 本帧是否实际渲染。这是 mask 与 quad 的唯一事实来源：
         * PortalMaskRenderer 用 {@link #getActiveHoles()}，主 pass quad 用
         * {@link #renderPortalQuad}，两边绝不允许各自再做剔除，
         * 否则"mask 了却没画 quad" = 纯色黑洞门。
         */
        boolean activeThisFrame;
    }

    private static final float PORTAL_RENDER_SCALE = 1.0f;
    /** 单帧最多同时渲染的门户数量（按离相机距离截断，与 mask 共用）。 */
    private static final int MAX_ACTIVE_PER_FRAME = 4;
    private static final long MESH_REBUILD_INTERVAL_MS = 200L;
    private static final int MAX_CACHED_PORTALS = 8;

    // ===== 天光压暗（毒二：fixed_time 6000 的 lightmap 时间源错位） =====

    /**
     * 本帧对侧维度的天光压暗因子：1=白天，&lt;1=夜晚。
     *
     * <p>根因：lightmap 是全局唯一纹理、由玩家所在维度（fixed_time 6000 的内饰）
     * 驱动 → 恒为正午曲线，FBO 借用原版 CUTOUT_BLOCK 管线时对侧 sky=15 区域恒亮。
     * 曲线的列内梯度（sky 0..15）是健康的，故把夜晚压暗烘成更小的 sky nibble
     * （{@link #applySkyDimming}），等于在恒亮曲线上取更暗的采样点；清屏色同步乘
     * factor，避免夜晚门外方块暗了、天际线却还是白天色。
     * 每帧在 {@link #renderOffscreenPass()} 开头更新一次。
     */
    private static float skyFactor = 1f;

    /** 深夜压暗下限：15×0.25 → sky nibble 4，约等于月夜的观感亮度级。 */
    private static final float NIGHT_SKY_FACTOR = 0.25f;

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
            skyFactor = 1f;
            activeDoors.clear();
            disposeAll();
            return;
        }

        // 本帧的对侧天光压暗因子：prepare 快照时间 → 昼夜压暗（毒二修复入口）。
        skyFactor = computeSkyFactor(prepared);

        if (!activeDoors.isEmpty()) {
            StpClientState.portalKeepAlive();
        }

        for (CacheEntry e : cache.values()) {
            e.activeThisFrame = false;
        }

        cache.entrySet().removeIf(entry -> {
            DoorKey key = entry.getKey();
            if (!mc.level.dimension().equals(key.dimension())) {
                return dispose(entry.getValue());
            }
            BlockEntity be = mc.level.getBlockEntity(key.pos());
            boolean gone = !(be instanceof TardisDoorBlockEntity door)
                    || door.isRemoved() || !door.isOpen();
            return gone && dispose(entry.getValue());
        });

        List<CacheEntry> candidates = new ArrayList<>(activeDoors.size());
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

            // skyFactor 变化也触发重烘：压暗烘在顶点里，时间流动必须重烘才生效。
            // 不受 200ms 节流——dayTime 只在 prepare 重发时更新，触发频率天然很低。
            boolean contentChanged = (revision != entry.chunkRevision
                    && nowMs - entry.lastMeshRebuildMs >= MESH_REBUILD_INTERVAL_MS)
                    || Math.abs(skyFactor - entry.lastSkyFactor) > 0.005f;

            if (entry.mesh == null || inputChanged || contentChanged) {
                entry.sourceLevel = prepared;
                entry.anchor = counterpart;
                entry.facing = facing;
                entry.appearanceId = appearanceId;
                entry.chunkRevision = revision;
                entry.lastMeshRebuildMs = nowMs;
                entry.lastSkyFactor = skyFactor;
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

                // 对面门的门洞（FBO 视锥用）：对面 asset + 对面 pos/facing
                TardisAsset.Bedrock remoteAsset = null;
                if (app != null) {
                    TardisAsset a = door.isExterior() ? app.interior() : app.exterior();
                    if (a instanceof TardisAsset.Bedrock b) remoteAsset = b;
                }
                entry.remoteFacing = door.getCounterpartFacing();
                entry.remoteHole = PortalDoorHole.extract(
                        counterpart, entry.remoteFacing, remoteAsset);
                holeRevision++;
            }

            if (entry.hole == null || entry.remoteHole == null || entry.mesh.isEmpty()) continue;
            entry.fadeAlpha = Mth.clamp(door.getFadeAlpha(), 0.0f, 1.0f);
            candidates.add(entry);
        }

        activeDoors.clear();

        if (candidates.size() > MAX_ACTIVE_PER_FRAME) {
            Vec3 camPos = camera.pos;
            candidates.sort(Comparator.comparingDouble(e -> {
                Vec3 c = e.hole.tl().add(e.hole.br()).scale(0.5);
                return c.subtract(camPos).lengthSqr();
            }));
            candidates = candidates.subList(0, MAX_ACTIVE_PER_FRAME);
        }

        for (CacheEntry entry : candidates) {
            if (entry.framebuffer == null) {
                entry.framebuffer = new PortalFramebuffer();
            }
            entry.framebuffer.resize(
                    Math.max(1, (int) (mc.getWindow().getWidth() * PORTAL_RENDER_SCALE)),
                    Math.max(1, (int) (mc.getWindow().getHeight() * PORTAL_RENDER_SCALE)));

            // 只有 FBO 真正画了才标记 active —— mask / quad / FBO 三者严格同源。
            // 贴脸时 frustum invalid 会 return false，mask 不打洞，
            // 避免"洞打了但 quad 内容是残影/清屏色"。
            if (doRenderToTarget(entry, camera, prepared)) {
                entry.activeThisFrame = true;
            }
        }
    }

    private boolean dispose(CacheEntry entry) {
        closeQuietly(entry.meshBuffer);
        entry.meshBuffer = null;
        closeQuietly(entry.quadBuffer);
        entry.quadBuffer = null;
        entry.lastQuadUvs = null;
        entry.frustumProj = null;
        entry.portalView = null;
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

    private boolean doRenderToTarget(CacheEntry entry, CameraRenderState camera, ClientLevel prepared) {
        var target = entry.framebuffer.getTarget();

        PortalCamera portalCam = new PortalCamera(
                entry.hole, entry.facing, entry.remoteHole, entry.remoteFacing,
                effectiveEyePos(camera));
        PortalFrustum frustum = new PortalFrustum(portalCam, entry.remoteHole, camera.depthFar);
        if (!frustum.valid) {
            return false;
        }

        entry.frustumProj = new Matrix4f(frustum.projectionMatrix);
        entry.portalView = new Matrix4f(portalCam.viewMatrix);
        GpuBufferSlice projectionSlice = portalProjectionBuffer.getBuffer(frustum.projectionMatrix);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.builder(() -> "Tardis Portal Offscreen")
                        .withColorAttachment(target.getColorTextureView(),
                                Optional.of(oppositeClearColor(prepared,
                                        BlockPos.containing(portalCam.position))))
                        .withDepthAttachment(target.getDepthTextureView(), OptionalDouble.of(0.0))
                        .build())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projectionSlice);
            var transform = RenderSystem.getDynamicUniforms().writeTransform(
                    portalCam.viewMatrix, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            pass.setUniform("DynamicTransforms", transform);

            var terrainSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("Sampler0", Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(), terrainSampler);

            var lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(),
                    lightSampler);

            var pipeline = RenderSystem.getCompiledPipeline(RenderPipelines.CUTOUT_BLOCK);
            pass.setPipeline(pipeline);
            submitMeshToPass(entry, pass);
        }
        return true;
    }

    /**
     * 主 pass 的有效眼位。26.3 中视角摇晃（bob）以视图空间平移形式
     * 乘进投影矩阵（GameRenderer.renderLevel → bobView），等价于相机
     * 实际位于 pos − R⁻¹·t。FBO 视锥必须用这个眼位，否则门内画面
     * 相对门框存在一个 bob 幅度的错位，贴门时被景深放大成剧烈晃动。
     */
    private static Vec3 effectiveEyePos(CameraRenderState camera) {
        var opts = Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState;
        float tX = 0.0F, tY = 0.0F;
        if (opts.bobView && camera.entityRenderState.isPlayer) {
            float walkDist = camera.entityRenderState.backwardsInterpolatedWalkDistance;
            float bob = camera.entityRenderState.bob;
            tX = Mth.sin(walkDist * (float) Math.PI) * bob * 0.5F;
            tY = -Math.abs(Mth.cos(walkDist * (float) Math.PI)) * bob;
        }
        if (tX == 0.0F && tY == 0.0F) {
            return camera.pos;
        }
        Vector3f worldBob = camera.viewRotationMatrix
                .transpose(new Matrix4f())
                .transformPosition(new Vector3f(tX, tY, 0.0F));
        return new Vec3(camera.pos.x - worldBob.x, camera.pos.y - worldBob.y, camera.pos.z - worldBob.z);
    }

    /**
     * FBO 清屏色 = 对侧维度雾色（26.3：环境属性系统）。
     * 烘焙网格 16 格边界渐隐进雾色后硬切边不可见。
     *
     * <p>夜晚乘 {@link #skyFactor}：方块烘暗了，天/雾也得跟着暗，否则夜晚门外
     * 地面暗、天际线仍白天色，穿帮。fallback 暗色不乘（本来就暗）。
     * <b>⚠ 实机校准点</b>：若夜晚雾色明显过暗（双重压暗感），说明 FOG_COLOR
     * 属性本身已随时间变化——那就去掉这里的乘法，只留 {@link #applySkyDimming}。
     */
    private static Vector4f oppositeClearColor(ClientLevel level, BlockPos pos) {
        Vector3fc fog = level.environmentAttributes()
                .getValue(EnvironmentAttributes.FOG_COLOR, pos);
        if (fog.x() <= 1e-4f && fog.y() <= 1e-4f && fog.z() <= 1e-4f) {
            return new Vector4f(0.04f, 0.04f, 0.06f, 1.0f);
        }
        return new Vector4f(
                Mth.clamp(fog.x() * skyFactor, 0.0f, 1.0f),
                Mth.clamp(fog.y() * skyFactor, 0.0f, 1.0f),
                Mth.clamp(fog.z() * skyFactor, 0.0f, 1.0f),
                1.0f);
    }

    /**
     * 由对侧维度时间算天光压暗因子。
     *
     * <p>背景（本案根因）：lightmap 全局唯一、由玩家所在维度驱动；玩家站在
     * {@code fixed_time: 6000} 的内饰里时主 lightmap 恒为正午，FBO 借用原版
     * 管线时对侧维度（有天光层）的 sky=15 区域恒亮。修复方式：把压暗烘进
     * 顶点 sky nibble——曲线列内梯度（sky 0..15）健康，更小的 nibble 在恒亮
     * 曲线上取更暗的点，等价于整体压暗；block nibble 不动，火把照常亮。
     *
     * <p>边界：
     * <ul>
     * <li>dayTime = -1（服务端未同步/老协议）→ 不压暗，按白天处理；</li>
     * <li>对侧 hasSkyLight=false（如内饰）→ 不压暗（该方向的对偶画面本就该亮，
     * 内饰光照来自灯具，block light 不随昼夜衰减）；</li>
     * <li>fixed_time 且有天光的维度 → timeOfDay 返回定值 → factor 恒定，语义自洽。</li>
     * </ul>
     *
     * <p>与原版昼夜边界对齐：dayness = cos((timeOfDay − 0.25)·2π)，正午 +1 →
     * 午夜 −1。dayness ≥ 0（日出 tick 0 到日落 tick 12000）全亮；≤ −0.35
     * （约 tick 14250..21750，对应原版深夜）压到 {@link #NIGHT_SKY_FACTOR}；
     * 中间线性过渡（黄昏黎明渐暗）。对原版是亮度级近似，不含夜晚偏蓝色温
     * 与雨天压暗。
     */
    private static float computeSkyFactor(ClientLevel prepared) {
        long dayTime = StpClientState.portalDayTime();
        DimensionType dimType = prepared.dimensionType();
        if (dayTime < 0 || !dimType.hasSkyLight()) return 1f;

        float celestial = Mth.frac((float) dayTime / 24000f);
        float dayness = Mth.cos((celestial - 0.25f) * (float) (Math.PI * 2));

        if (dayness >= 0f) return 1f;
        if (dayness <= -0.35f) return NIGHT_SKY_FACTOR;
        return Mth.lerp((dayness + 0.35f) / 0.35f, NIGHT_SKY_FACTOR, 1.0f);
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
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
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
                .setLight(applySkyDimming(v.light()))
                .setNormal(v.nx(), v.ny(), v.nz());
    }

    /**
     * 夜晚压暗烘进 sky nibble（packed light 位 20..23）；block nibble（位 4..7）
     * 不动——火把/灯具照常亮。白天（factor≈1）零开销直通。
     */
    private static int applySkyDimming(int light) {
        if (skyFactor >= 0.999f) return light;
        int sky = (light >>> 20) & 15;
        return (light & ~0xF00000) | (Math.round(sky * skyFactor) << 20);
    }

    /**
     * 主 pass：把 FBO 画面贴回门洞 quad。
     *
     * <p>UV 不再写死 0..1，而是把对面门洞 4 个角点用本帧 FBO 的
     * 视锥投影变换到 NDC，得到每个角真实的采样位置。玩家正对门中心
     * 时结果退化为 0..1（与旧写死值一致）；斜视时 quad 对应的是
     * FBO 里的斜投影梯形区域本身，楔形清屏区与透视畸变一并消除。
     * GPU 对平面 quad 做透视校正插值，4 角 UV 正确即整幅映射正确
     * （等价于一次 homography）。
     *
     * <p>只渲染 {@code activeThisFrame} 的条目 —— 与
     * {@link #getActiveHoles()}（mask 消费方）严格同源。
     */
    public void renderPortalQuad(RenderPass pass) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;

        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var projection = RenderSystem.getProjectionMatrixBuffer();
        if (projection != null) pass.setUniform("Projection", projection);
        Matrix4f camMat = new Matrix4f(camera.viewRotationMatrix);
        camMat.translate((float) -camera.pos.x, (float) -camera.pos.y, (float) -camera.pos.z);
        var transform = RenderSystem.getDynamicUniforms().writeTransform(
                camMat, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        pass.setUniform("DynamicTransforms", transform);

        for (CacheEntry entry : cache.values()) {
            if (!entry.activeThisFrame) continue;
            if (entry.framebuffer == null || entry.hole == null) continue;
            if (entry.frustumProj == null || entry.portalView == null) continue;

            float[][] uvs = computeQuadUvs(entry);
            if (entry.quadBuffer == null || entry.quadHole != entry.hole
                    || quadAlphaOf(entry) != entry.lastQuadAlpha
                    || uvsChanged(uvs, entry.lastQuadUvs)) {
                closeQuietly(entry.quadBuffer);
                entry.quadBuffer = buildQuadBuffer(entry.hole, entry.fadeAlpha, uvs);
                entry.quadHole = entry.hole;
                entry.lastQuadAlpha = quadAlphaOf(entry);
                entry.lastQuadUvs = flattenUvs(uvs);
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

    /**
     * 计算门洞 4 个角对应的 FBO 采样 UV。
     *
     * <p>角对应关系（由 180° 旋转穿门映射推导）：
     * 本地 tl ↔ 远端 tr、tr ↔ tl、br ↔ bl、bl ↔ br。
     * 虚拟相机从远端门洞背面朝 fB 看，远端 tr 在 FBO 图像左上，对应本地 tl。
     * 返回下标：0=tl, 1=tr, 2=br, 3=bl。
     */
    private static float[][] computeQuadUvs(CacheEntry entry) {
        Matrix4f vp = new Matrix4f(entry.frustumProj).mul(entry.portalView);
        Vec3[] remoteCorners = {
                entry.remoteHole.tr(), // ↔ 本地 tl
                entry.remoteHole.tl(), // ↔ 本地 tr
                entry.remoteHole.bl(), // ↔ 本地 br
                entry.remoteHole.br()  // ↔ 本地 bl
        };
        float[][] uvs = new float[4][2];
        Vector3f p = new Vector3f();
        for (int i = 0; i < 4; i++) {
            Vec3 c = remoteCorners[i];
            p.set((float) c.x, (float) c.y, (float) c.z);
            // transformProject 含透视除法，得到 NDC；reversed-Z 不影响 x/y
            vp.transformProject(p);
            uvs[i][0] = Mth.clamp(p.x * 0.5f + 0.5f, 0.0f, 1.0f);
            uvs[i][1] = Mth.clamp(p.y * 0.5f + 0.5f, 0.0f, 1.0f);
        }
        return uvs;
    }

    private static boolean uvsChanged(float[][] uvs, float[] last) {
        if (last == null) return true;
        int k = 0;
        for (float[] uv : uvs) {
            if (uv[0] != last[k++] || uv[1] != last[k++]) return true;
        }
        return false;
    }

    private static float[] flattenUvs(float[][] uvs) {
        float[] flat = new float[8];
        for (int i = 0; i < 4; i++) {
            flat[i * 2] = uvs[i][0];
            flat[i * 2 + 1] = uvs[i][1];
        }
        return flat;
    }

    private static float quadAlphaOf(CacheEntry entry) {
        return entry.fadeAlpha;
    }

    private static GpuBuffer buildQuadBuffer(PortalDoorHole.DoorHole hole, float alpha, float[][] uvs) {
        ByteBufferBuilder bb = new ByteBufferBuilder(256);
        try {
            BufferBuilder builder = new BufferBuilder(
                    bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int color = ARGB.color((int) (alpha * 255.0f), 255, 255, 255);
            putQuadVertex(builder, hole.tl(), uvs[0][0], uvs[0][1], color);
            putQuadVertex(builder, hole.bl(), uvs[3][0], uvs[3][1], color);
            putQuadVertex(builder, hole.br(), uvs[2][0], uvs[2][1], color);
            putQuadVertex(builder, hole.tr(), uvs[1][0], uvs[1][1], color);
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

    private static void putQuadVertex(BufferBuilder builder, Vec3 worldPos, float u, float v, int color) {
        builder.addVertex((float) worldPos.x, (float) worldPos.y, (float) worldPos.z)
                .setUv(u, v)
                .setColor(color);
    }

    /**
     * mask 消费的本帧孔洞集合。
     *
     * <p>规则：
     * <ul>
     * <li>只含 {@code activeThisFrame} 的门 —— 与 quad 渲染严格同源，
     * 杜绝"mask 了却没画 quad"的黑洞门；</li>
     * <li>fade 进行中（alpha &lt; 1）摘出 mask：让洞后的真实地形透出来，
     * 配合半透明 quad 形成正确的淡入效果。</li>
     * </ul>
     *
     * <p><b>调用顺序要求</b>：必须在同一帧的
     * {@link #renderOffscreenPass()} 之后调用（mask 在地形 solid HEAD
     * 阶段消费本集合），否则拿到的是上一帧的可见集合。
     */
    public Collection<PortalDoorHole.DoorHole> getActiveHoles() {
        List<PortalDoorHole.DoorHole> holes = new ArrayList<>(cache.size());
        for (CacheEntry entry : cache.values()) {
            if (entry.activeThisFrame && entry.hole != null && entry.fadeAlpha >= 0.999f) {
                holes.add(entry.hole);
            }
        }
        return holes;
    }
}