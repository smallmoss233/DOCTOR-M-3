package mosslib.core.api;

import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class MossLibCodec {
    private MossLibCodec() {}

    /**
     * 必填字段：自动缩减 fieldOf + forGetter 的样板代码。
     */
    public static <T, O> RecordCodecBuilder<O, T> f(String name, Codec<T> codec, Function<O, T> getter) {
        return codec.fieldOf(name).forGetter(getter);
    }

    /**
     * 可选字段（带默认值）。
     * 如果存档里缺少该字段，会自动使用 def 的值。
     */
    public static <T, O> RecordCodecBuilder<O, T> o(String name, Codec<T> codec, T def, Function<O, T> getter) {
        return codec.optionalFieldOf(name, def).forGetter(getter);
    }

    /**
     * 可选字段（无默认值，返回 Optional）。
     * 注意：底层框架的 apply 阶段不允许传 null，所以这里只能返回 Optional。
     * 业务层在 apply 时请务必使用 .orElse(null) 来解包！
     */
    public static <T, O> RecordCodecBuilder<O, Optional<T>> opt(String name, Codec<T> codec, Function<O, T> getter) {
        return codec.optionalFieldOf(name).forGetter(o -> Optional.ofNullable(getter.apply(o)));
    }

    // ==================== 组装器 (of2 - of16) ====================

    public static <T1, R> Codec<R> of(Function<T1, R> factory, RecordCodecBuilder<R, T1> f1) {
        return RecordCodecBuilder.create(i -> i.group(f1).apply(i, factory));
    }

    public static <T1, T2, R> Codec<R> of(BiFunction<T1, T2, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2).apply(i, factory));
    }

    public static <T1, T2, T3, R> Codec<R> of(Function3<T1, T2, T3, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3).apply(i, factory));
    }

    public static <T1, T2, T3, T4, R> Codec<R> of(Function4<T1, T2, T3, T4, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, R> Codec<R> of(Function5<T1, T2, T3, T4, T5, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, R> Codec<R> of(Function6<T1, T2, T3, T4, T5, T6, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, R> Codec<R> of(Function7<T1, T2, T3, T4, T5, T6, T7, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, R> Codec<R> of(Function8<T1, T2, T3, T4, T5, T6, T7, T8, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> Codec<R> of(Function9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> Codec<R> of(Function10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> Codec<R> of(Function11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10, RecordCodecBuilder<R, T11> f11) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> Codec<R> of(Function12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10, RecordCodecBuilder<R, T11> f11, RecordCodecBuilder<R, T12> f12) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> Codec<R> of(Function13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10, RecordCodecBuilder<R, T11> f11, RecordCodecBuilder<R, T12> f12, RecordCodecBuilder<R, T13> f13) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> Codec<R> of(Function14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10, RecordCodecBuilder<R, T11> f11, RecordCodecBuilder<R, T12> f12, RecordCodecBuilder<R, T13> f13, RecordCodecBuilder<R, T14> f14) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> Codec<R> of(Function15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10, RecordCodecBuilder<R, T11> f11, RecordCodecBuilder<R, T12> f12, RecordCodecBuilder<R, T13> f13, RecordCodecBuilder<R, T14> f14, RecordCodecBuilder<R, T15> f15) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15).apply(i, factory));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> Codec<R> of(Function16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> factory, RecordCodecBuilder<R, T1> f1, RecordCodecBuilder<R, T2> f2, RecordCodecBuilder<R, T3> f3, RecordCodecBuilder<R, T4> f4, RecordCodecBuilder<R, T5> f5, RecordCodecBuilder<R, T6> f6, RecordCodecBuilder<R, T7> f7, RecordCodecBuilder<R, T8> f8, RecordCodecBuilder<R, T9> f9, RecordCodecBuilder<R, T10> f10, RecordCodecBuilder<R, T11> f11, RecordCodecBuilder<R, T12> f12, RecordCodecBuilder<R, T13> f13, RecordCodecBuilder<R, T14> f14, RecordCodecBuilder<R, T15> f15, RecordCodecBuilder<R, T16> f16) {
        return RecordCodecBuilder.create(i -> i.group(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16).apply(i, factory));
    }
}