package io.mindspice.lyra.compiler.backend.jvm;

/**
 * A semantic position whose JVM representation can differ even when the
 * underlying Lyra type is identical.
 */
enum JvmValuePosition {
    BINDING("binding", true, true),
    VALUE("value", true, true),
    FUNCTION_PARAMETER("function-parameter", true, false),
    FUNCTION_RETURN("function-return", false, false),
    EXPORTED_VALUE("exported-value", false, false),
    TUPLE_FIELD("tuple-field", false, false),
    CAPTURE("capture", false, true),
    CELL_VALUE("cell-value", false, true),
    ARRAY_ELEMENT("array-element", false, false),
    FUNCTION_VALUE("function-value", false, false);

    private final String spelling;
    private final boolean permitsMutable;
    private final boolean permitsInternalPresencePayload;

    JvmValuePosition(
            String spelling,
            boolean permitsMutable,
            boolean permitsInternalPresencePayload) {
        this.spelling = spelling;
        this.permitsMutable = permitsMutable;
        this.permitsInternalPresencePayload = permitsInternalPresencePayload;
    }

    public String canonicalSpelling() {
        return spelling;
    }

    public boolean permitsMutableQualifier() {
        return permitsMutable;
    }

    public boolean permitsInternalPresencePayload() {
        return permitsInternalPresencePayload;
    }

    public boolean isFunctionReturn() {
        return this == FUNCTION_RETURN;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
