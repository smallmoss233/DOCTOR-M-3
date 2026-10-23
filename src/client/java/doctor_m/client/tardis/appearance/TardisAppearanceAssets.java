package doctor_m.client.tardis.appearance;

import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import mosslib.core.bedrock.BedrockCache;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 外观资产的"体检"：把一套外观解析成资源引用，交给
 * {@link BedrockCache#warmUp} 预加载并收集问题。
 *
 * <h2>为什么需要这一层</h2>
 * {@code BedrockCache} 属于 MossLib 技术库，<b>不认识"外观"这个概念</b> ——
 * 它只接受几何 / 动画 / 片段名。这个类负责把模组侧的
 * {@link TardisAppearance} 翻译成那些参数。
 *
 * <p>依赖方向因此是单向的：模组 → 技术库，技术库不反向依赖模组。
 *
 * <h2>为什么在客户端源集</h2>
 * {@code BedrockCache} 读客户端资源管理器，属客户端专属。
 * 外观注册表虽然双端可见，但"预加载模型资源"这件事本身只在客户端有意义。
 *
 * <h2>用途</h2>
 * 在"应用外观"或"/dmbedrock check"时提前暴露问题（几何缺失、动画名拼错），
 * 而不是等玩家穿门时才发现模型是空的。
 */
public final class TardisAppearanceAssets {

    private TardisAppearanceAssets() {}

    /**
     * 检查某套已注册外观引用的全部资源。
     *
     * @return 问题描述；外观不存在或一切正常时返回相应结果
     */
    public static List<String> warmUp(Identifier appearanceId) {
        TardisAppearance app = TardisAppearanceRegistry.get(appearanceId);
        if (app == null) {
            List<String> problems = new ArrayList<>();
            problems.add("unknown appearance " + appearanceId);
            return problems;
        }
        return warmUp(app);
    }

    /** 检查一套外观引用的全部资源。 */
    public static List<String> warmUp(TardisAppearance app) {
        List<String> problems = new ArrayList<>();
        if (app == null) return problems;

        warmUpAsset("exterior", app.exterior(), problems);
        warmUpAsset("interior", app.interior(), problems);
        return problems;
    }

    private static void warmUpAsset(String label, TardisAsset asset, List<String> problems) {
        if (!(asset instanceof TardisAsset.Bedrock b)) return;

        List<String> found = BedrockCache.warmUp(
                b.geometry(),
                b.animation(),
                b.openAnimation(),
                b.closeAnimation());

        // 加上 exterior / interior 前缀，出问题时能一眼看出是哪一侧
        for (String p : found) {
            problems.add(label + ": " + p);
        }
    }
}
