package io.mindspice.lyra.compiler.backend.jvm;

/**
 * The two representation boundaries understood by the Phase-14 ABI planner.
 * Semantic qualifiers are retained at both boundaries; this value only selects
 * the JVM materialization policy.
 */
enum JvmAbiBoundary {
    /** A compiler-owned local, field, capture, or linkage representation. */
    INTERNAL("internal"),
    /** A generated Java member or generated functional-interface boundary. */
    JAVA_VISIBLE("java-visible");

    private final String spelling;

    JvmAbiBoundary(String spelling) {
        this.spelling = spelling;
    }

    public String canonicalSpelling() {
        return spelling;
    }

    public String canonical() {
        return spelling;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
