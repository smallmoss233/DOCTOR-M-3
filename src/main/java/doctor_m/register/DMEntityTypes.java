package doctor_m.register;

import doctor_m.tardis.console.ConsoleControlEntity;
import mosslib.core.api.AutoRegister;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * DM3 的实体类型注册。
 *
 * <p>通过 {@link AutoRegister#entity} 声明式登记，避免手写
 * {@code Registry.register(...)} 样板。调用 {@link AutoRegister#entities} 完成实际注册。
 */
public final class DMEntityTypes {

    private DMEntityTypes() {}

    // =========================================================
    //                    TARDIS 控制台控件
    // =========================================================

    /**
     * 控件实体：不渲染、不可推、不可伤，仅作为"骨骼锚点"服务交互。
     *
     * <p>{@code sized(0.1f, 0.1f)} 只是<b>初始尺寸</b>——运行时
     * {@link ConsoleControlEntity#applyBounds} 会把碰撞箱改成布局算出的 AABB。
     */
    public static final EntityType<ConsoleControlEntity> CONSOLE_CONTROL =
            AutoRegister.entity("doctor_m", "console_control",
                    EntityType.Builder
                            .<ConsoleControlEntity>of(
                                    (type, level) -> new ConsoleControlEntity(type, level),
                                    MobCategory.MISC)
                            .sized(0.1f, 0.1f)
                            .noSummon()         // 不允许 /summon
                            .fireImmune()       // 不受火焰伤害
                            .build(ResourceKey.create(
                                    Registries.ENTITY_TYPE,
                                    Identifier.fromNamespaceAndPath("doctor_m", "console_control"))));

    // =========================================================
    //                        注册入口
    // =========================================================

    /**
     * 在主类 {@code onInitialize} 里调用一次。
     *
     * <p>它做两件事：
     * <ol>
     *   <li>把 {@link #CONSOLE_CONTROL} 等字段注册到 {@code BuiltInRegistries.ENTITY_TYPE}</li>
     *   <li>调用 {@code registerAttributes()}（如果类里定义了该方法）</li>
     * </ol>
     */
    public static void register() {
        AutoRegister.entities(DMEntityTypes.class);
    }
}