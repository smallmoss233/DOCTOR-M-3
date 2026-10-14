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
 */
public record TardisConsoleAppearance(
        Identifier id,
        String displayName,
        Identifier geometry,
        Identifier animation,       // 可为 null
        Identifier texture,         // 可为 null（回退到 geometry 自带）
        String idleAnimation,       // 可为 null
        Direction modelFacing,
        float offsetX, float offsetY, float offsetZ,
        float scale,
        Map<String, ControlConfig> controls
) {
    public TardisConsoleAppearance {
        if (controls == null) controls = Map.of();
    }
}