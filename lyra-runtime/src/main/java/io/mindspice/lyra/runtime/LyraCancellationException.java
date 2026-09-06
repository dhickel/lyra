package io.mindspice.lyra.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Cooperative cancellation signal raised by an owner-thread safe point.
 *
 * <p>Stable runtime category/code: {@code CANCEL}/{@code LYR-CANCEL}.</p>
 */
public final class LyraCancellationException extends LyraRuntimeException {
    private final LyraCancellation.Scope scope;
    private final long cancellationId;

    public LyraCancellationException(String summary) {
        this(LyraCancellation.Scope.EVALUATION, -1L, summary,
                List.of(), List.of(), null);
    }

    public LyraCancellationException(String summary, List<? extends SourceFrame> frames) {
        this(LyraCancellation.Scope.EVALUATION, -1L, summary,
                frames, List.of(), null);
    }

    public LyraCancellationException(String summary,
                                     List<? extends SourceFrame> frames,
                                     Throwable cause) {
        this(LyraCancellation.Scope.EVALUATION, -1L, summary,
                frames, List.of(), cause);
    }

    public LyraCancellationException(String summary,
                                     List<? extends SourceFrame> frames,
                                     List<? extends RelatedSource> relatedSources) {
        this(LyraCancellation.Scope.EVALUATION, -1L, summary,
                frames, relatedSources, null);
    }

    public LyraCancellationException(String summary,
                                     List<? extends SourceFrame> frames,
                                     List<? extends RelatedSource> relatedSources,
                                     Throwable cause) {
        this(LyraCancellation.Scope.EVALUATION, -1L, summary,
                frames, relatedSources, cause);
    }

    static LyraCancellationException forToken(LyraCancellation token) {
        LyraCancellation value = Objects.requireNonNull(token, "token");
        return new LyraCancellationException(value.scope(), value.id(),
                "active evaluation was cancelled", List.of(), List.of(), null);
    }

    public LyraCancellation.Scope scope() {
        return scope;
    }

    /** Returns the token identity, or {@code -1} for manually constructed signals. */
    public long cancellationId() {
        return cancellationId;
    }

    /**
     * Returns the evaluation identity carried by this signal.
     *
     * @throws IllegalStateException when this signal is not evaluation-scoped
     *         or has no token identity
     */
    public long evaluationId() {
        if (scope != LyraCancellation.Scope.EVALUATION || cancellationId < 0) {
            throw new IllegalStateException("cancellation signal has no evaluation identity");
        }
        return cancellationId;
    }

    @Override
    protected LyraRuntimeException recreate(List<? extends SourceFrame> frames,
                                            List<? extends RelatedSource> relatedSources,
                                            Throwable cause) {
        return new LyraCancellationException(scope, cancellationId, summary(),
                frames, relatedSources, cause);
    }

    private LyraCancellationException(LyraCancellation.Scope scope,
                                      long cancellationId,
                                      String summary,
                                      List<? extends SourceFrame> frames,
                                      List<? extends RelatedSource> relatedSources,
                                      Throwable cause) {
        super(LyraFailureCategory.CANCEL, summary, frames, relatedSources, cause);
        this.scope = Objects.requireNonNull(scope, "scope");
        this.cancellationId = cancellationId;
    }
}
