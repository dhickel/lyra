package io.mindspice.lyra.runtime;

import java.util.List;

/** Standard-I/O or stream failure at the Lyra boundary. */
public final class LyraIoException extends LyraRuntimeException {
    public LyraIoException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraIoException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraIoException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraIoException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraIoException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.IO, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraIoException(summary(), frames, relatedSources, cause); }
}
