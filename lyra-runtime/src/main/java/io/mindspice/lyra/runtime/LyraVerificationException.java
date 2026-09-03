package io.mindspice.lyra.runtime;

import java.util.List;

/** JVM verification failure translated at a controlled definition boundary. */
public final class LyraVerificationException extends LyraRuntimeException {
    public LyraVerificationException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraVerificationException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraVerificationException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraVerificationException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraVerificationException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.VERIFY, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraVerificationException(summary(), frames, relatedSources, cause); }
}
