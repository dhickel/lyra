package io.mindspice.lyra.compiler.backend.jvm;

/** Why one generated class refers to another generated class. */
enum GeneratedDependencyKind {
    TUPLE_MEMBER_TYPE,
    FUNCTION_SIGNATURE_TYPE,
    CELL_VALUE_TYPE,
    CLOSURE_FUNCTION_INTERFACE,
    CLOSURE_MODULE_STATE,
    CLOSURE_CAPTURE_TYPE,
    CLOSURE_SHARED_CELL,
    MODULE_IMPORT_LINKAGE,
    MODULE_INITIALIZATION,
    MODULE_STATE_FIELD_TYPE,
    FACADE_STATE,
    FACADE_EXPORT_TYPE,
    RECURSIVE_FUNCTION_LINKAGE;
}
