package io.mindspice.lyra.runtime;

import java.util.List;

/** Runtime ownership, signature, linkage, or definition boundary failure. */
public final class LyraLinkException extends LyraRuntimeException {
    public LyraLinkException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraLinkException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraLinkException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraLinkException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraLinkException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.LINK, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraLinkException(summary(), frames, relatedSources, cause); }
}
