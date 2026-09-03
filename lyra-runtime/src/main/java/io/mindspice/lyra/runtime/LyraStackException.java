package io.mindspice.lyra.runtime;

import java.util.List;

/** Lyra recursion/stack exhaustion failure. */
public final class LyraStackException extends LyraRuntimeException {
    public LyraStackException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraStackException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraStackException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraStackException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraStackException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.STACK, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraStackException(summary(), frames, relatedSources, cause); }
}
