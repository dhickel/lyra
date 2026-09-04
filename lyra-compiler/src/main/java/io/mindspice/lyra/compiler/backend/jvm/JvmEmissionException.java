package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/** Expected failure while lowering a validated program to the Phase-15 subset. */
final class JvmEmissionException extends RuntimeException {
    private final SourceSpan span;
    private final boolean invalidPlan;

    JvmEmissionException(SourceSpan span, String message) {
        this(span, message, false, null);
    }

    JvmEmissionException(SourceSpan span, String message, Throwable cause) {
        this(span, message, false, cause);
    }

    JvmEmissionException(SourceSpan span, String message, boolean invalidPlan) {
        this(span, message, invalidPlan, null);
    }

    JvmEmissionException(SourceSpan span, String message, boolean invalidPlan,
                         Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.span = Objects.requireNonNull(span, "span");
        this.invalidPlan = invalidPlan;
    }

    SourceSpan span() {
        return span;
    }

    boolean invalidPlan() {
        return invalidPlan;
    }
}
