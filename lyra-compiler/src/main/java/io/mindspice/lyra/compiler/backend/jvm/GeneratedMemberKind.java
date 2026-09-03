package io.mindspice.lyra.compiler.backend.jvm;

/** Exact generated member roles planned before any method body is emitted. */
enum GeneratedMemberKind {
    TUPLE_FIELD(false),
    TUPLE_COMPONENT_GET(true),
    TUPLE_CONSTRUCTOR(true),
    FUNCTION_INVOKE(true),
    CLOSURE_AUTHORITY_FIELD(false),
    CLOSURE_STATE_FIELD(false),
    CLOSURE_CAPTURE_FIELD(false),
    CLOSURE_CAPTURE_PRESENCE_FIELD(false),
    CLOSURE_CAPTURE_PAYLOAD_FIELD(false),
    CLOSURE_CONSTRUCTOR(true),
    CLOSURE_INVOKE(true),
    CELL_PRESENCE_FIELD(false),
    CELL_VALUE_FIELD(false),
    CELL_CONSTRUCTOR(true),
    CELL_GET(true),
    CELL_SET(true),
    CELL_PRESENCE_GET(true),
    CELL_PAYLOAD_GET(true),
    STATE_BINDING_FIELD(false),
    STATE_PRESENCE_FIELD(false),
    STATE_PAYLOAD_FIELD(false),
    STATE_CELL_FIELD(false),
    STATE_IMPORT_FIELD(false),
    STATE_IMPORT_LINK(true),
    STATE_COMPONENT_GET(true),
    STATE_COMPONENT_SET(true),
    STATE_CONSTRUCTOR(true),
    FACADE_STATE_FIELD(false),
    FACADE_CONSTRUCTOR(true),
    FUNCTION_INVOCATION(true),
    VALUE_GETTER(true),
    FUNCTION_VALUE_GETTER(true),
    SETTER(true),
    FACTORY(true),
    FACTORY_WITH_OPTIONS(true),
    METADATA(true),
    CLOSE(true);

    private final boolean method;

    GeneratedMemberKind(boolean method) {
        this.method = method;
    }

    public boolean isMethod() {
        return method;
    }

    public boolean isField() {
        return !method;
    }

    public boolean isExportMember() {
        return this == FUNCTION_INVOCATION || this == VALUE_GETTER
                || this == FUNCTION_VALUE_GETTER || this == SETTER;
    }
}
