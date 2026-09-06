package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** Immutable originating source information; never retains a generated class or live value. */
public record RuntimeFrame(
        String functionName,
        SourceOrigin origin,
        SourceSpan span,
        Optional<String> excerpt) {
    public RuntimeFrame {
        functionName = Objects.requireNonNull(functionName, "functionName");
        origin = Objects.requireNonNull(origin, "origin");
        span = Objects.requireNonNull(span, "span");
        excerpt = Objects.requireNonNull(excerpt, "excerpt");
        if (functionName.isBlank()) throw new IllegalArgumentException("empty function name");
    }
}
