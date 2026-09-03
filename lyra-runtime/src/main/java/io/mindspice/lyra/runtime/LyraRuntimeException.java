package io.mindspice.lyra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Base unchecked exception carrying structured Lyra runtime failure data. */
public sealed abstract class LyraRuntimeException extends RuntimeException
        permits LyraArithmeticException, LyraBoundsException, LyraConversionException,
        LyraStackException, LyraIoException, LyraInitializationException,
        LyraThreadException, LyraClosedException, LyraLifecycleException,
        LyraLinkException, LyraVerificationException, LyraCompatibilityException,
        LyraInternalException {
    private final LyraFailureCategory category;
    private final String summary;
    private final List<SourceFrame> frames;
    private final List<RelatedSource> relatedSources;

    protected LyraRuntimeException(LyraFailureCategory category, String summary) {
        this(category, summary, List.of(), List.of(), null);
    }

    protected LyraRuntimeException(LyraFailureCategory category, String summary,
                                   List<? extends SourceFrame> frames,
                                   List<? extends RelatedSource> relatedSources,
                                   Throwable cause) {
        super(requireSummary(summary), cause);
        this.category = Objects.requireNonNull(category, "category");
        this.summary = summary;
        this.frames = copyFrames(frames);
        this.relatedSources = copyRelated(relatedSources);
    }

    public final LyraFailureCategory category() {
        return category;
    }

    public final String code() {
        return category.code();
    }

    public final String summary() {
        return summary;
    }

    public final List<SourceFrame> frames() {
        return frames;
    }

    public final List<SourceFrame> sourceFrames() {
        return frames;
    }

    public final List<RelatedSource> relatedSources() {
        return relatedSources;
    }

    public final List<RelatedSource> related() {
        return relatedSources;
    }

    public final Optional<Throwable> javaCause() {
        Throwable cause = getCause();
        return cause == null ? Optional.empty() : Optional.of(cause);
    }

    public final Optional<Throwable> originalJavaCause() {
        return javaCause();
    }

    public final boolean hasCategory(LyraFailureCategory expected) {
        return category == Objects.requireNonNull(expected, "expected");
    }

    public final String render() {
        return SourceFrameRenderer.render(this);
    }

    public final String render(boolean includeSyntheticFrames) {
        return SourceFrameRenderer.render(this, includeSyntheticFrames);
    }

    /** Returns a new structured failure with one frame appended to the ordered trace. */
    public final LyraRuntimeException withFrame(SourceFrame frame) {
        Objects.requireNonNull(frame, "frame");
        ArrayList<SourceFrame> combined = new ArrayList<>(frames);
        combined.add(frame);
        return recreate(combined, relatedSources, getCause());
    }

    /** Returns a new structured failure with one related source appended. */
    public final LyraRuntimeException withRelatedSource(RelatedSource related) {
        Objects.requireNonNull(related, "related");
        ArrayList<RelatedSource> combined = new ArrayList<>(relatedSources);
        combined.add(related);
        return recreate(frames, combined, getCause());
    }

    @Override
    public final String toString() {
        return code() + ": " + summary;
    }

    protected abstract LyraRuntimeException recreate(List<? extends SourceFrame> frames,
                                                      List<? extends RelatedSource> relatedSources,
                                                      Throwable cause);

    public static LyraRuntimeException of(LyraFailureCategory category, String summary) {
        return of(category, summary, List.of(), List.of(), null);
    }

    public static LyraRuntimeException of(LyraFailureCategory category, String summary,
                                          List<? extends SourceFrame> frames) {
        return of(category, summary, frames, List.of(), null);
    }

    public static LyraRuntimeException of(LyraFailureCategory category, String summary,
                                          Throwable cause) {
        return of(category, summary, List.of(), List.of(), cause);
    }

    public static LyraRuntimeException of(LyraFailureCategory category, String summary,
                                          List<? extends SourceFrame> frames,
                                          List<? extends RelatedSource> relatedSources,
                                          Throwable cause) {
        Objects.requireNonNull(category, "category");
        return switch (category) {
            case ARITH -> new LyraArithmeticException(summary, frames, relatedSources, cause);
            case BOUNDS -> new LyraBoundsException(summary, frames, relatedSources, cause);
            case CONVERT -> new LyraConversionException(summary, frames, relatedSources, cause);
            case STACK -> new LyraStackException(summary, frames, relatedSources, cause);
            case IO -> new LyraIoException(summary, frames, relatedSources, cause);
            case INIT -> new LyraInitializationException(summary, frames, relatedSources, cause);
            case THREAD -> new LyraThreadException(summary, frames, relatedSources, cause);
            case CLOSED -> new LyraClosedException(summary, frames, relatedSources, cause);
            case LIFECYCLE -> new LyraLifecycleException(summary, frames, relatedSources, cause);
            case LINK -> new LyraLinkException(summary, frames, relatedSources, cause);
            case VERIFY -> new LyraVerificationException(summary, frames, relatedSources, cause);
            case COMPAT -> new LyraCompatibilityException(summary, frames, relatedSources, cause);
            case INTERNAL -> new LyraInternalException(summary, frames, relatedSources, cause);
        };
    }

    private static String requireSummary(String summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("runtime failure summary must not be blank");
        }
        return summary;
    }

    private static List<SourceFrame> copyFrames(List<? extends SourceFrame> frames) {
        Objects.requireNonNull(frames, "frames");
        ArrayList<SourceFrame> copy = new ArrayList<>(frames.size());
        for (SourceFrame frame : frames) {
            copy.add(Objects.requireNonNull(frame, "frames must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<RelatedSource> copyRelated(List<? extends RelatedSource> related) {
        Objects.requireNonNull(related, "relatedSources");
        ArrayList<RelatedSource> copy = new ArrayList<>(related.size());
        for (RelatedSource source : related) {
            copy.add(Objects.requireNonNull(source, "relatedSources must not contain null"));
        }
        return List.copyOf(copy);
    }
}
