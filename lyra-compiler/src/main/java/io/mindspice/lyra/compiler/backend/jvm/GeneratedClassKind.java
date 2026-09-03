package io.mindspice.lyra.compiler.backend.jvm;

/** Generated class categories and their default dependency rank. */
enum GeneratedClassKind {
    TUPLE_VALUE(0),
    FUNCTION_INTERFACE(1),
    CELL(2),
    CLOSURE(3),
    MODULE_STATE(4),
    MODULE_FACADE(5);

    private final int orderRank;

    GeneratedClassKind(int orderRank) {
        this.orderRank = orderRank;
    }

    public int orderRank() {
        return orderRank;
    }

    public boolean isValueType() {
        return this == TUPLE_VALUE || this == FUNCTION_INTERFACE;
    }
}
