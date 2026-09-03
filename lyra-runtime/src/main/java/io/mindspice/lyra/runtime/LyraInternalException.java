package io.mindspice.lyra.runtime;

import java.util.List;

/** Internal runtime invariant failure. */
public final class LyraInternalException extends LyraRuntimeException {
    public LyraInternalException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraInternalException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraInternalException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraInternalException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraInternalException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.INTERNAL, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraInternalException(summary(), frames, relatedSources, cause); }
}
