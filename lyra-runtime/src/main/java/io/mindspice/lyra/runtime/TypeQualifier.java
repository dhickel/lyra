package io.mindspice.lyra.runtime;

import java.util.Arrays;
import java.util.Optional;

/** Qualifiers that are part of a Lyra value contract. */
public enum TypeQualifier {
    MUT("@mut"),
    NIL("@nil");

    public static final TypeQualifier MUTABLE = MUT;
    public static final TypeQualifier NILABLE = NIL;

    private final String spelling;

    TypeQualifier(String spelling) {
        this.spelling = spelling;
    }

    public String spelling() {
        return spelling;
    }

    public static Optional<TypeQualifier> fromSpelling(String spelling) {
        if (spelling == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.spelling.equals(spelling)).findFirst();
    }
}
