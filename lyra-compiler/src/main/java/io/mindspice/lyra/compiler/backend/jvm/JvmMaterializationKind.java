package io.mindspice.lyra.compiler.backend.jvm;

/** Explicit materialization choice recorded beside every mapped value. */
enum JvmMaterializationKind {
    DIRECT,
    NULLABLE_REFERENCE,
    NULLABLE_PRIMITIVE_WRAPPER,
    INTERNAL_PRESENCE_PAYLOAD,
    UNIT_SINGLETON,
    LANGUAGE_UNIT_RETURN_VOID,
    ARRAY_WRAPPER_ELEMENTS,
    NULLABLE_ARRAY_WRAPPER_ELEMENTS;
}
