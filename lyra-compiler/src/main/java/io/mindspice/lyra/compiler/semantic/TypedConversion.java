package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;

/** One explicit or compiler-inserted conversion with its complete source location. */
public record TypedConversion(
        SourceSpan span,
        LyraType sourceType,
        LyraType targetType,
        ConversionKind kind,
        ConversionStep step) implements ImmutablePhaseArtifact {
    public TypedConversion {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(step, "step");
        if (kind == ConversionKind.IDENTITY || kind == ConversionKind.INCOMPATIBLE) {
            throw new IllegalArgumentException("a typed conversion must change or explicitly convert a value");
        }
    }

    public LyraType source() {
        return sourceType;
    }

    public LyraType target() {
        return targetType;
    }
}
