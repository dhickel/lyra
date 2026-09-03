package io.mindspice.lyra.runtime;

import java.util.Arrays;

/** Stable runtime failure categories. */
public enum LyraFailureCategory {
    ARITH("LYR-ARITH"),
    BOUNDS("LYR-BOUNDS"),
    CONVERT("LYR-CONVERT"),
    STACK("LYR-STACK"),
    IO("LYR-IO"),
    INIT("LYR-INIT"),
    THREAD("LYR-THREAD"),
    CLOSED("LYR-CLOSED"),
    LIFECYCLE("LYR-LIFECYCLE"),
    LINK("LYR-LINK"),
    VERIFY("LYR-VERIFY"),
    COMPAT("LYR-COMPAT"),
    INTERNAL("LYR-INTERNAL");

    public static final LyraFailureCategory ARITHMETIC = ARITH;
    public static final LyraFailureCategory CONVERSION = CONVERT;
    public static final LyraFailureCategory INITIALIZATION = INIT;
    public static final LyraFailureCategory VERIFICATION = VERIFY;
    public static final LyraFailureCategory COMPATIBILITY = COMPAT;

    private final String code;

    LyraFailureCategory(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String canonicalSpelling() {
        return code;
    }

    public static LyraFailureCategory fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("runtime failure code must not be null");
        }
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown runtime failure code: " + code));
    }

    @Override
    public String toString() {
        return code;
    }
}
