package io.mindspice.lyra.compiler.types;

import java.util.Arrays;
import java.util.Optional;

/** Qualifiers that are part of a value contract. */
public enum TypeQualifier {
    /** The contract grants mutation permission to a parameter/binding position. */
    MUT("@mut"),
    /** The contract admits the nil value in addition to its base type. */
    NIL("@nil");

    /** Compatibility-readable aliases; the canonical names are {@link #MUT} and {@link #NIL}. */
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
        return Arrays.stream(values())
                .filter(qualifier -> qualifier.spelling.equals(spelling))
                .findFirst();
    }
}
