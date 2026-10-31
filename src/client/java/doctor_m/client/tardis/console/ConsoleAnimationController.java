package doctor_m.client.tardis.console;

import doctor_m.block.entity.TardisConsoleBlockEntity;
import doctor_m.tardis.console.ConsoleBoneMatcher;
import doctor_m.tardis.console.TardisConsoleAppearance;
import doctor_m.tardis.console.control.ConsoleControlEntity;
import doctor_m.tardis.console.control.ControlConfig;
import mosslib.core.bedrock.BedrockAnimationModel;
import mosslib.core.render.BedrockModelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.*;

/**
 * 控制台控件的客户端动画控制器。
 *
 * <h2>机制</h2>
 * 每个控件实体有独立的 {@code targetTime}（由服务端写入 DataTracker，随挡位变化）。
 * 本控制器维护一个"当前播放时间"，每帧朝 {@code targetTime} 推进（恒速 1 秒动画 /
 * 1 秒真实时间）。
 *
 * <p>渲染时，把每个控件的 (片段, 当前时间) 组装成 {@code overrides} 传进
 * {@link mosslib.core.render.BedrockModelRenderer#render} —— 每个控件骨骼
 * 独立走自己的轨道，其余骨骼走 idle。
 *
 * <h2>时间推进基于真实时间差分</h2>
 * 用 {@code System.nanoTime()} 差分而非 tick 计数，因为：
 * <ul>
 *   <li>渲染帧率 (60+) 和 tick 率 (20) 不一致</li>
 *   <li>渲染器可能每个 BE 调一次 {@link #buildOverrides}，多调无害（每次差分小）</li>
 *   <li>游戏暂停时不会继续推进（如果暂停也调的话会被 deltaSeconds 上限钳住）</li>
 * </ul>
 *
 * <h2>控件索引（新增）</h2>
 * 旧版每帧、每个控制台都遍历 {@code level.entitiesForRendering()}（全世界实体），
 * 然后按 {@code getConsolePos()} 过滤：控制台或控件多时是 {@code O(控制台 × 实体)}。
 * 现在每 tick 只重建一次 {@link #INDEX}（控制台位置 → 所属控件列表），
 * 所有控制台共用一次遍历，渲染时直接按位置查引。
 */
public final class ConsoleAnimationController {

    private ConsoleAnimationController() {}

    // =========================================================
    //                      播放状态
    // =========================================================

    /** 每个控件实体的播放时间。WeakHashMap 让实体被回收时自动清理。 */
    private static final Map<ConsoleControlEntity, Playback> PLAYBACK =
            new WeakHashMap<>();

    private static final class Playback {
        float currentTime;
        long lastUpdateNs;
    }

    /** 单帧最大推进量（秒）。防止卡顿后动画跳跃。 */
    private static final float MAX_STEP_SECONDS = 0.1f;

    // =========================================================
    //                      控件索引（控制台位置 → 控件列表）
    // =========================================================

    /** 控制台方块位置 → 该控制台下的控件实体列表。 */
    private static Map<BlockPos, List<ConsoleControlEntity>> INDEX = Map.of();

    /**
     * 每 tick 调用一次：重建控件索引。
     *
     * <p>控件实体是 noPhysics、位置固定的，一个 tick 内不会移动，
     * 所以每 tick 重建一次、整帧复用是安全的。渲染时所有控制台共用
     * 这一份索引，不再逐台遍历全世界实体。
     */
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            INDEX = Map.of();
            return;
        }

        Map<BlockPos, List<ConsoleControlEntity>> map = new HashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof ConsoleControlEntity c)) continue;
            BlockPos pos = c.getConsolePos();
            if (pos == null) continue;
            map.computeIfAbsent(pos, k -> new ArrayList<>()).add(c);
        }
        INDEX = Map.copyOf(map);
    }

    /** 清空索引（资源重载 / 断线时调用）。 */
    public static void clearIndex() {
        INDEX = Map.of();
    }

    // =========================================================
    //                      idle 时间
    // =========================================================

    /**
     * 全局 idle 时间（秒）。所有控制台共用同一个循环时间。
     *
     * <p>无状态 —— 直接用系统时间取模。idle 本来就是循环动画，不同端
     * 从不同起点开始看不出来。
     */
    public static float idleTime() {
        return (System.currentTimeMillis() % 3600_000L) / 1000f;
    }

    // =========================================================
    //                      推进
    // =========================================================

    /**
     * 推进某个控件的当前播放时间，返回推进后的值。
     *
     * <p>每帧调用一次。所有控件各自独立。
     */
    private static float advance(ConsoleControlEntity c) {
        long now = System.nanoTime();

        Playback p = PLAYBACK.computeIfAbsent(c, k -> {
            Playback np = new Playback();
            np.lastUpdateNs = now;
            return np;
        });

        float target = c.getTargetTime();
        float deltaSeconds = (now - p.lastUpdateNs) / 1_000_000_000f;
        deltaSeconds = Math.min(deltaSeconds, MAX_STEP_SECONDS);
        p.lastUpdateNs = now;

        float diff = target - p.currentTime;
        if (Math.abs(diff) < 1e-4f) {
            p.currentTime = target;
            return target;
        }

        float step = Math.signum(diff) * deltaSeconds;
        if (Math.abs(step) >= Math.abs(diff)) {
            p.currentTime = target;
        } else {
            p.currentTime += step;
        }
        return p.currentTime;
    }

    // =========================================================
    //                      组装 overrides
    // =========================================================

    /**
     * 为某个控制台组装骨骼覆盖表。
     *
     * <p>直接查 {@link #INDEX} 里属于该控制台的控件，不再遍历全世界实体。
     *
     * @param console 控制台 BE
     * @param clips   该外观的动画片段表（{@code animationId → Animation}），
     *                从 {@code BedrockCache.animations(...)} 拿
     * @param app     外观配置（用于解析骨骼名 → 配置 / 片段名）
     * @return 骨骼名 → override；空 map 表示无控件或无动画
     */
    public static Map<String, mosslib.core.render.BedrockModelRenderer.BoneOverride>
    buildOverrides(TardisConsoleBlockEntity console,
                   Map<String, BedrockAnimationModel.Animation> clips,
                   TardisConsoleAppearance app) {

        Map<String, mosslib.core.render.BedrockModelRenderer.BoneOverride> out =
                new HashMap<>();

        if (console == null || clips == null || clips.isEmpty() || app == null) {
            return out;
        }

        BlockPos pos = console.getBlockPos();
        List<ConsoleControlEntity> controls = INDEX.get(pos);
        if (controls == null) return out;

        for (ConsoleControlEntity c : controls) {
            ControlConfig config = ConsoleBoneMatcher.resolve(c.getBoneName(), app);
            if (config == null) continue;

            String clipName = ConsoleBoneMatcher.animationNameFor(c.getBoneName(), config);
            if (clipName == null) continue;

            BedrockAnimationModel.Animation clip = clips.get(clipName);
            if (clip == null) continue;

            float time = advance(c);

            if (clip.bones().isEmpty()) {

                out.put(c.getBoneName(), new BedrockModelRenderer.BoneOverride(clip, time));
            } else {
                for (String animBoneName : clip.bones().keySet()) {
                    out.put(animBoneName, new BedrockModelRenderer.BoneOverride(clip, time));
                }
            }
        }

        return out;
    }
}
