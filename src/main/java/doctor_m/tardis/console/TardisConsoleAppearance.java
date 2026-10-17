package doctor_m.tardis.console;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * 控制台外观的独立配置。
 *
 * <p>结构与 {@code TardisAsset.Bedrock} 类似，但语义不同：
 * <ul>
 *   <li>只有 idle 动画（没有 open/close）</li>
 *   <li>带 modelFacing（模型在 Blockbench 里的正面朝向）</li>
 *   <li>带 controls：骨骼名 → 控件配置；未配的骨骼走命名约定</li>
 * </ul>
 *
 * @param id            外观 id
 * @param displayName   显示名
 * @param geometry      几何模型
 * @param animation     动画文件，可为 null
 * @param texture       贴图，可为 null（回退到 geometry 自带）
 * @param idleAnimation idle 动画片段名，可为 null
 * @param modelFacing   模型正面朝向
 * @param offsetX       模型 X 偏移（像素，渲染时 /16）
 * @param offsetY       模型 Y 偏移（像素）
 * @param offsetZ       模型 Z 偏移（像素）
 * @param scale         缩放（16 = 1 格）
 * @param controls      骨骼名 → 控件配置；可为 null（按空表处理）
 */
public record TardisConsoleAppearance(
        Identifier id,
        String displayName,
        Identifier geometry,
        Identifier animation,
        Identifier texture,
        String idleAnimation,
        Direction modelFacing,
        float offsetX, float offsetY, float offsetZ,
        float scale,
        Map<String, ControlConfig> controls
) {
    public TardisConsoleAppearance {
        if (controls == null) controls = Map.of();
    }
}
