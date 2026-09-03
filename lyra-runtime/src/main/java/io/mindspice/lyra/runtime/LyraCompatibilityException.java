package io.mindspice.lyra.runtime;

import java.util.List;

/** Artifact/profile/ABI/metadata compatibility failure. */
public final class LyraCompatibilityException extends LyraRuntimeException {
    public LyraCompatibilityException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraCompatibilityException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraCompatibilityException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraCompatibilityException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraCompatibilityException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.COMPAT, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraCompatibilityException(summary(), frames, relatedSources, cause); }
}
