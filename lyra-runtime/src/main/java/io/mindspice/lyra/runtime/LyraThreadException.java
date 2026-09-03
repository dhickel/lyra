package io.mindspice.lyra.runtime;

import java.util.List;

/** Wrong-owner-thread runtime failure. */
public final class LyraThreadException extends LyraRuntimeException {
    public LyraThreadException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraThreadException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraThreadException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraThreadException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraThreadException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.THREAD, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraThreadException(summary(), frames, relatedSources, cause); }
}
