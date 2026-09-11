package io.mindspice.lyra.compiler.backend.jvm;

/** Physical JVM kind retained by an ABI mapping. */
enum JvmTypeKind {
    VOID,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    CHAR,
    REFERENCE,
    WRAPPER,
    ARRAY,
    TUPLE,
    NOMINAL,
    FUNCTION,
    UNIT;

    public boolean isPrimitive() {
        return switch (this) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BOOLEAN, CHAR -> true;
            default -> false;
        };
    }

    public boolean isReference() {
        return this == REFERENCE || this == WRAPPER || this == ARRAY
                || this == TUPLE || this == NOMINAL || this == FUNCTION || this == UNIT;
    }

    public boolean isCategory2() {
        return this == LONG || this == DOUBLE;
    }
}
