package io.mindspice.lyra.compiler.diagnostic;

import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/** A labeled secondary source location attached to a diagnostic. */
public record RelatedSpan(SourceSpan span, String label) {
    public RelatedSpan {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("related span label must not be blank");
        }
    }

    public static RelatedSpan of(SourceSpan span, String label) {
        return new RelatedSpan(span, label);
    }
}
