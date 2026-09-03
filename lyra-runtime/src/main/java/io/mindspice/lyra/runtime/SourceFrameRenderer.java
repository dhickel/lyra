package io.mindspice.lyra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic reusable rendering for source frames and runtime failures. */
public final class SourceFrameRenderer {
    private SourceFrameRenderer() {
    }

    public static String renderSourceFrame(SourceFrame frame) {
        return render(frame);
    }

    public static String render(SourceFrame frame) {
        SourceFrame value = Objects.requireNonNull(frame, "frame");
        if (value.synthetic()) {
            value = value.nearestOrigin();
            if (value.synthetic()) {
                return "";
            }
        }
        return renderFrame(value, false);
    }

    public static String render(SourceFrame frame, boolean includeSynthetic) {
        SourceFrame value = Objects.requireNonNull(frame, "frame");
        if (!includeSynthetic && value.synthetic()) {
            value = value.nearestOrigin();
            if (value.synthetic()) {
                return "";
            }
        }
        return renderFrame(value, includeSynthetic);
    }

    public static String renderFrames(List<? extends SourceFrame> frames) {
        return renderFrames(frames, false);
    }

    public static String renderFrames(List<? extends SourceFrame> frames, boolean includeSynthetic) {
        Objects.requireNonNull(frames, "frames");
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (SourceFrame frame : visibleFrames(frames, includeSynthetic)) {
            if (!first) {
                result.append('\n');
            }
            first = false;
            result.append(renderFrame(frame, includeSynthetic));
        }
        return result.toString();
    }

    public static String renderFailure(LyraRuntimeException failure) {
        return render(failure);
    }

    public static String render(LyraRuntimeException failure) {
        return render(Objects.requireNonNull(failure, "failure"), false);
    }

    public static String render(LyraRuntimeException failure, boolean includeSynthetic) {
        Objects.requireNonNull(failure, "failure");
        StringBuilder result = new StringBuilder()
                .append(failure.code()).append(": ").append(failure.summary());
        String frames = renderFrames(failure.frames(), includeSynthetic);
        if (!frames.isEmpty()) {
            result.append('\n').append(frames);
        }
        if (!failure.relatedSources().isEmpty()) {
            result.append('\n').append("related:");
            for (RelatedSource related : failure.relatedSources()) {
                result.append('\n').append("  ").append(related.render());
            }
        }
        return result.toString();
    }

    private static List<SourceFrame> visibleFrames(List<? extends SourceFrame> frames,
                                                    boolean includeSynthetic) {
        List<SourceFrame> visible = new ArrayList<>();
        for (SourceFrame frame : frames) {
            SourceFrame nonNull = Objects.requireNonNull(frame, "frames must not contain null");
            SourceFrame candidate = includeSynthetic || !nonNull.synthetic()
                    ? nonNull : nonNull.nearestOrigin();
            if (includeSynthetic || !nonNull.synthetic() || candidate != nonNull) {
                visible.add(candidate);
            }
        }
        return visible;
    }

    private static String renderFrame(SourceFrame frame, boolean includeSynthetic) {
        SourceFrame rendered = !includeSynthetic && frame.synthetic() ? frame.nearestOrigin() : frame;
        StringBuilder result = new StringBuilder("  at ")
                .append(rendered.moduleId()).append("::").append(rendered.functionName())
                .append(" (").append(rendered.location()).append(')');
        if (rendered.synthetic()) {
            result.append(" [synthetic]");
        }
        if (rendered.sourceData().isPresent()) {
            SourceData source = rendered.sourceData().orElseThrow();
            SourcePosition start = source.positionAt(rendered.span().startOffset());
            String line = source.lineText(start.line());
            int markerLength = Math.max(1, Math.min(rendered.span().length(),
                    line.length() - Math.min(start.column() - 1, line.length())));
            result.append('\n').append("    ").append(line)
                    .append('\n').append("    ")
                    .append(" ".repeat(Math.max(0, start.column() - 1)))
                    .append("^".repeat(markerLength));
        }
        return result.toString();
    }
}
