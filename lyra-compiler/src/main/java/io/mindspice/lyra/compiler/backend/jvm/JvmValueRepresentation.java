package io.mindspice.lyra.compiler.backend.jvm;

import java.util.List;

/** Physical JVM components used to materialize one logical Lyra value. */
sealed interface JvmValueRepresentation
        permits JvmSingleValue, JvmPresencePayload {
    List<JvmType> components();

    default boolean isSingle() {
        return components().size() == 1;
    }

    default JvmType single() {
        if (!isSingle()) {
            throw new IllegalStateException("the logical value has multiple JVM components");
        }
        return components().getFirst();
    }

    /** Returns a descriptor only when this value is one JVM value. */
    default String descriptor() {
        return single().descriptor();
    }

    default List<String> descriptors() {
        return components().stream().map(JvmType::descriptor).toList();
    }

    String canonicalSpelling();

    default String canonical() {
        return canonicalSpelling();
    }
}
