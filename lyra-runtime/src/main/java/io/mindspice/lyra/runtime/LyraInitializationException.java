package io.mindspice.lyra.runtime;

import java.util.List;

/** Top-level module initialization failure. */
public final class LyraInitializationException extends LyraRuntimeException {
    public LyraInitializationException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraInitializationException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraInitializationException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraInitializationException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraInitializationException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.INIT, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraInitializationException(summary(), frames, relatedSources, cause); }
}
