package io.mindspice.lyra.runtime;

import java.util.List;

/** Explicit checked conversion failure. */
public final class LyraConversionException extends LyraRuntimeException {
    public LyraConversionException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraConversionException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraConversionException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraConversionException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraConversionException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.CONVERT, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraConversionException(summary(), frames, relatedSources, cause); }
}
