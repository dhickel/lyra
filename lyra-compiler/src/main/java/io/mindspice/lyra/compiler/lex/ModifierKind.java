package io.mindspice.lyra.compiler.lex;

import java.util.Arrays;
import java.util.Optional;

/** The closed set of modifiers accepted by the current language contract. */
public enum ModifierKind {
    PUBLIC("@pub"),
    MUTABLE("@mut"),
    NILABLE("@nil");

    private final String spelling;

    ModifierKind(String spelling) {
        this.spelling = spelling;
    }

    public String spelling() {
        return spelling;
    }

    public static Optional<ModifierKind> fromSpelling(String spelling) {
        if (spelling == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(modifier -> modifier.spelling.equals(spelling))
                .findFirst();
    }
}
