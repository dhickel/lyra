package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/** Immutable Lyra module/function source frame. */
public final class SourceFrame {
    private final ModuleId moduleId;
    private final String functionName;
    private final SourceSpan span;
    private final Optional<SourceData> sourceData;
    private final Optional<String> sourceLabel;
    private final boolean synthetic;
    private final Optional<SourceFrame> origin;

    public SourceFrame(ModuleId moduleId, String functionName, SourceSpan span) {
        this(moduleId, functionName, span, Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    public SourceFrame(ModuleId moduleId, String functionName, SourceSpan span, SourceData sourceData) {
        this(moduleId, functionName, span,
                Optional.of(Objects.requireNonNull(sourceData, "sourceData")),
                Objects.requireNonNull(sourceData, "sourceData").label(), false, Optional.empty());
    }

    /** A frame may retain a source label even when source text is unavailable. */
    public SourceFrame(ModuleId moduleId, String functionName, SourceSpan span, String sourceLabel) {
        this(moduleId, functionName, span, Optional.empty(), Optional.of(requireLabel(sourceLabel)),
                false, Optional.empty());
    }

    public SourceFrame(ModuleId moduleId, String functionName, SourceSpan span,
                       Optional<SourceData> sourceData, boolean synthetic,
                       Optional<SourceFrame> origin) {
        this(moduleId, functionName, span, sourceData,
                Objects.requireNonNull(sourceData, "sourceData").flatMap(SourceData::label),
                synthetic, origin);
    }

    public SourceFrame(ModuleId moduleId, String functionName, SourceSpan span,
                       Optional<SourceData> sourceData, Optional<String> sourceLabel,
                       boolean synthetic, Optional<SourceFrame> origin) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.functionName = requireName(functionName, "functionName");
        this.span = Objects.requireNonNull(span, "span");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("frame module and source span have different identities");
        }
        this.sourceData = Objects.requireNonNull(sourceData, "sourceData").map(data -> {
            if (!span.sourceId().equals(data.sourceId())) {
                throw new IllegalArgumentException("frame span and source data have different source IDs");
            }
            span.validateAgainst(data);
            return data;
        });
        this.sourceLabel = Objects.requireNonNull(sourceLabel, "sourceLabel").map(SourceFrame::requireLabel);
        this.synthetic = synthetic;
        this.origin = Objects.requireNonNull(origin, "origin");
        if (synthetic && origin.isEmpty()) {
            throw new IllegalArgumentException("synthetic frames require an originating frame");
        }
        if (!synthetic && origin.isPresent()) {
            throw new IllegalArgumentException("only synthetic frames may carry an origin");
        }
        if (origin.isPresent() && origin.get() == this) {
            throw new IllegalArgumentException("a frame cannot be its own origin");
        }
    }

    public static SourceFrame of(ModuleId moduleId, String functionName, SourceSpan span) {
        return new SourceFrame(moduleId, functionName, span);
    }

    public static SourceFrame synthetic(ModuleId moduleId, String functionName,
                                        SourceSpan span, SourceFrame nearestOrigin) {
        return new SourceFrame(moduleId, functionName, span, Optional.empty(), true,
                Optional.of(Objects.requireNonNull(nearestOrigin, "nearestOrigin")));
    }

    public SourceFrame withSource(SourceData sourceData) {
        SourceData data = Objects.requireNonNull(sourceData, "sourceData");
        return new SourceFrame(moduleId, functionName, span, Optional.of(data),
                sourceLabel.isPresent() ? sourceLabel : data.label(), synthetic, origin);
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public ModuleId module() {
        return moduleId;
    }

    public String functionName() {
        return functionName;
    }

    public String function() {
        return functionName;
    }

    public SourceSpan span() {
        return span;
    }

    public Optional<SourceData> sourceData() {
        return sourceData;
    }

    public Optional<SourceData> source() {
        return sourceData;
    }

    public Optional<String> sourceLabel() {
        return sourceLabel;
    }

    public boolean synthetic() {
        return synthetic;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public Optional<SourceFrame> origin() {
        return origin;
    }

    /** Returns the nearest non-synthetic frame, or this frame when no origin exists. */
    public SourceFrame nearestOrigin() {
        SourceFrame current = this;
        while (current.synthetic && current.origin.isPresent() && current.origin.get() != current) {
            current = current.origin.get();
        }
        return current;
    }

    public Optional<SourceFrame> nearestOriginFrame() {
        SourceFrame nearest = nearestOrigin();
        return nearest == this ? Optional.empty() : Optional.of(nearest);
    }

    public SourcePosition startPosition() {
        return sourceData.map(data -> data.positionAt(span.startOffset()))
                .orElseThrow(() -> new IllegalStateException("frame has no source text"));
    }

    public SourcePosition endPosition() {
        return sourceData.map(data -> data.positionAt(span.endOffset()))
                .orElseThrow(() -> new IllegalStateException("frame has no source text"));
    }

    public Optional<String> excerpt() {
        return sourceData.map(data -> data.excerpt(span));
    }

    public String render() {
        return SourceFrameRenderer.render(this);
    }

    public String render(boolean includeSynthetic) {
        return SourceFrameRenderer.render(this, includeSynthetic);
    }

    public String sourceLocation() {
        return location();
    }

    public String location() {
        if (sourceData.isPresent()) {
            SourcePosition position = startPosition();
            return sourceLabel.orElse(span.sourceId().value()) + ":"
                    + position.line() + ":" + position.column();
        }
        if (sourceLabel.isPresent()) {
            return sourceLabel.get() + ":" + span.startOffset() + ".." + span.endOffset();
        }
        return span.sourceId() + ":" + span.startOffset() + ".." + span.endOffset();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourceFrame frame
                && moduleId.equals(frame.moduleId)
                && functionName.equals(frame.functionName)
                && span.equals(frame.span)
                && sourceData.equals(frame.sourceData)
                && sourceLabel.equals(frame.sourceLabel)
                && synthetic == frame.synthetic
                && origin.equals(frame.origin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleId, functionName, span, sourceData, sourceLabel, synthetic, origin);
    }

    @Override
    public String toString() {
        return moduleId + "::" + functionName + "@" + span;
    }

    private static String requireLabel(String value) {
        return requireName(value, "sourceLabel");
    }

    private static String requireName(String value, String field) {
        CanonicalJson.requireUtf8(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        return value;
    }
}
