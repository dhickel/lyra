package io.mindspice.lyra.runtime;

import java.util.List;

/** Access after an owning module or handle has closed. */
public final class LyraClosedException extends LyraRuntimeException {
    public LyraClosedException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraClosedException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraClosedException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraClosedException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraClosedException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.CLOSED, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraClosedException(summary(), frames, relatedSources, cause); }
}
