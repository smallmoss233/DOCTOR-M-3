package mosslib.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 翻译文本的安全构造。
 *
 * <h2>为什么需要它</h2>
 * Minecraft 26.3 的 {@code Component.translatable(key, args...)} 只接受
 * <b>String</b> 或 <b>Component</b> 作为参数。传入 {@code Integer}、{@code Identifier}
 * 或其他类型时，构造会成功、{@code getString()} 也不报错，
 * 但<b>序列化时抛异常</b>：
 *
 * <pre>
 * io.netty.handler.codec.EncoderException: Failed to encode:
 *   This value needs to be parsed as component
 *   translation{key='…', args=[…]}
 * </pre>
 *
 * <p>结果就是命令在聊天栏正常打出，但一旦要发给客户端就崩 —— 排查起来极不直观。
 * 参数数量与占位符不匹配（多给或少给）会以同样方式失败。
 *
 * <p>本类把这些参数统一转成 {@link Component}，从根上杜绝该问题：
 *
 * <pre>{@code
 * // 会崩：
 * Component.translatable("key", pos.getX(), list.size());
 * // 安全：
 * Text.tr("key", Text.of(pos.getX()), Text.of(list.size()));
 * }</pre>
 */
public final class Text {

    private Text() {}

    /** 把任意值转成可以安全放进翻译参数的组件。null 转成空组件。 */
    public static Component of(Object value) {
        if (value == null) return Component.empty();
        if (value instanceof Component c) return c;
        if (value instanceof CharSequence cs) return Component.literal(cs.toString());
        return Component.literal(String.valueOf(value));
    }

    /**
     * 安全版 {@code Component.translatable}：所有参数都会被转成组件。
     *
     * <p>调用方必须保证参数数量与翻译串里的占位符数量一致。
     */
    public static MutableComponent tr(String key, Object... args) {
        if (args == null || args.length == 0) {
            return Component.translatable(key);
        }
        Object[] safe = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            safe[i] = of(args[i]);
        }
        return Component.translatable(key, safe);
    }

    /** 直接给字符串字面量上色，等价于旧代码里的 {@code "§a[DM] …"} 写法。 */
    public static MutableComponent literal(String text) {
        return Component.literal(text);
    }

    /** 空组件，用于"没有内容"的占位。 */
    public static MutableComponent empty() {
        return Component.empty();
    }

    /** 是否可以作为翻译参数安全传输。用于测试与断言。 */
    public static boolean isSafeArg(Object value) {
        return value == null || value instanceof Component || value instanceof CharSequence;
    }
}
