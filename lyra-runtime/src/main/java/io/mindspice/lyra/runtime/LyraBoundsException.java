package io.mindspice.lyra.runtime;

import java.util.List;

/** Array, tuple, or string bounds failure. */
public final class LyraBoundsException extends LyraRuntimeException {
    public LyraBoundsException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraBoundsException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraBoundsException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraBoundsException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraBoundsException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.BOUNDS, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraBoundsException(summary(), frames, relatedSources, cause); }
}
