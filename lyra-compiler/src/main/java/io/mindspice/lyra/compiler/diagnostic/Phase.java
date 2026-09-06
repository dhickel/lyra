package io.mindspice.lyra.compiler.diagnostic;

import java.util.Arrays;
import java.util.Optional;

/** Compiler phase names used in stable diagnostic identifiers. */
public enum Phase {
    SOURCE,
    LEX,
    PARSE,
    MODULE,
    RESOLVE,
    TYPE,
    IR,
    EMIT,
    PACKAGE,
    SESSION;

    public String codeName() {
        return name();
    }

    public static Optional<Phase> fromCodeName(String codeName) {
        if (codeName == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(phase -> phase.codeName().equals(codeName))
                .findFirst();
    }
}
