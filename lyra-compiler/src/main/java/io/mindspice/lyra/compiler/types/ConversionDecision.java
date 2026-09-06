package io.mindspice.lyra.compiler.types;

import java.util.List;
import java.util.Objects;

/** Immutable result of classifying one source/target contract pair. */
public record ConversionDecision(
        LyraType source,
        LyraType target,
        ConversionKind kind,
        List<ConversionStep> steps,
        String reason) {
    public ConversionDecision {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(reason, "reason");
        steps = List.copyOf(steps);
        if (kind == ConversionKind.IDENTITY && !steps.isEmpty()) {
            throw new IllegalArgumentException("identity decisions cannot contain conversion steps");
        }
        if (kind == ConversionKind.INCOMPATIBLE && !steps.isEmpty()) {
            throw new IllegalArgumentException("incompatible decisions cannot contain conversion steps");
        }
    }

    public boolean allowed() {
        return kind.isAllowed();
    }

    public boolean isAllowed() {
        return allowed();
    }

    public boolean isIdentity() {
        return kind == ConversionKind.IDENTITY;
    }

    public boolean isImplicit() {
        return kind.isImplicit() && !isIdentity();
    }

    public boolean isExplicit() {
        return kind.isExplicit();
    }

    public List<ConversionStep> operations() {
        return steps;
    }
}
