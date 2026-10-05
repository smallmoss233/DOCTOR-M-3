package doctor_m.client.tardis.console;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * 控制台外观的独立配置。
 *
 * <p>结构与 {@code TardisAsset.Bedrock} 类似，但语义不同：
 *   - 只有 idle 动画（没有 open/close）
 *   - 带 modelFacing（模型在 Blockbench 里的正面朝向）
 *   - 之后会加 controls 列表（控件骨骼清单）
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
        float scale
) {}