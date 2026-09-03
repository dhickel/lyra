package io.mindspice.lyra.runtime;

import java.util.List;

/** Invalid module/context lifecycle transition. */
public final class LyraLifecycleException extends LyraRuntimeException {
    public LyraLifecycleException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraLifecycleException(String summary, List<? extends SourceFrame> frames) { this(summary, frames, List.of(), null); }
    public LyraLifecycleException(String summary, List<? extends SourceFrame> frames, Throwable cause) { this(summary, frames, List.of(), cause); }
    public LyraLifecycleException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources) { this(summary, frames, relatedSources, null); }
    public LyraLifecycleException(String summary, List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { super(LyraFailureCategory.LIFECYCLE, summary, frames, relatedSources, cause); }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames, List<? extends RelatedSource> relatedSources, Throwable cause) { return new LyraLifecycleException(summary(), frames, relatedSources, cause); }
}
