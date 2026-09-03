package io.mindspice.lyra.runtime;

import java.util.List;

/** Checked/trapping arithmetic failure. */
public final class LyraArithmeticException extends LyraRuntimeException {
    public LyraArithmeticException(String summary) { this(summary, List.of(), List.of(), null); }
    public LyraArithmeticException(String summary, List<? extends SourceFrame> frames) {
        this(summary, frames, List.of(), null);
    }
    public LyraArithmeticException(String summary, List<? extends SourceFrame> frames, Throwable cause) {
        this(summary, frames, List.of(), cause);
    }
    public LyraArithmeticException(String summary, List<? extends SourceFrame> frames,
                                   List<? extends RelatedSource> relatedSources) {
        this(summary, frames, relatedSources, null);
    }
    public LyraArithmeticException(String summary, List<? extends SourceFrame> frames,
                                   List<? extends RelatedSource> relatedSources, Throwable cause) {
        super(LyraFailureCategory.ARITH, summary, frames, relatedSources, cause);
    }
    @Override protected LyraRuntimeException recreate(List<? extends SourceFrame> frames,
                                                        List<? extends RelatedSource> relatedSources,
                                                        Throwable cause) {
        return new LyraArithmeticException(summary(), frames, relatedSources, cause);
    }
}
