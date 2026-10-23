package doctor_m.client.tardis.render;

import com.mojang.blaze3d.vertex.PoseStack;
import doctor_m.tardis.console.control.ConsoleControlEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * 控件实体的渲染器。
 *
 * <p>控件实体不绘制任何东西，但 26.3 的 {@code EntityRenderDispatcher}
 * 要求每个实体都注册 renderer，否则 {@code shouldRender} 会 NPE 崩游戏。
 *
 * <p>所以这是一个"什么都不画"的 renderer：{@link #shouldRender} 永远返回
 * false，{@link #submit} 空实现。
 *
 * <h2>26.3 的渲染接口</h2>
 * {@code EntityRenderer} 现在用"提取状态 → 提交绘制"两段式：
 * <ol>
 *   <li>{@link #createRenderState} + {@code extractRenderState} 提取一帧不可变的
 *       {@link EntityRenderState}</li>
 *   <li>{@link #submit} 拿到状态去做实际绘制</li>
 * </ol>
 * 本渲染器不需要任何状态，直接用空的 {@link EntityRenderState}。
 *
 * <h2>名字显示的门槛</h2>
 * 控件名<b>不允许像玩家铭牌那样直接常显</b>：显示必须经过条件判断
 * （{@link ConsoleControlEntity#shouldShowName}）。目前是空判断条件 ——
 * 恒 {@code false}，名字完全不显示。之后做控件名称显示功能时：
 * <ul>
 *   <li>条件加在 {@code ConsoleControlEntity#shouldShowName}（单一权威）</li>
 *   <li>渲染文本取 {@code getName()}（按控件类型派生的 ID，如
 *       {@code doctor_m:console_control.throttle}）或 custom name（玩家覆盖名）</li>
 *   <li>在 {@link #submit} 里按状态绘制，不要走默认铭牌路径</li>
 * </ul>
 */
public class ConsoleControlRenderer
        extends EntityRenderer<ConsoleControlEntity, EntityRenderState> {

    public ConsoleControlRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    /**
     * 名字显示门槛：恒 false（空判断条件），名字完全不显示。
     * 之后做名称显示功能时改为调用 {@code entity.shouldShowName(viewer)}。
     */
    @Override
    protected boolean shouldShowName(ConsoleControlEntity entity, double distanceToCameraSq) {
        return false;
    }

    @Override
    public boolean shouldRender(ConsoleControlEntity entity, Frustum culler,
                                double camX, double camY, double camZ,
                                float partialTicks) {
        return super.shouldRender(entity, culler, camX, camY, camZ, partialTicks);
    }

    @Override
    public void submit(EntityRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        // 什么都不画
    }
}