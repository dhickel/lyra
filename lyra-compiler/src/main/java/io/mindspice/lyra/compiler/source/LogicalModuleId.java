package io.mindspice.lyra.compiler.source;

import io.mindspice.lyra.compiler.ast.SyntaxNode;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The logical name used by an import before it is mapped to a source.
 *
 * <p>A logical module name is deliberately not a {@link SourceId}: it names
 * the import contract ({@code game->math->vector}), while a source identity
 * names the selected file or resolver URI.  The name is independent of
 * aliases, source roots, and platform path separators.</p>
 */
public record LogicalModuleId(List<String> segments) implements Comparable<LogicalModuleId> {
    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public static final LogicalModuleId STD_IO =
            new LogicalModuleId(List.of("std", "io"));

    public LogicalModuleId {
        Objects.requireNonNull(segments, "segments");
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("logical module path must contain a segment");
        }
        for (String segment : segments) {
            validateSegment(segment);
        }
    }

    /** Parses the source spelling used after {@code import}. */
    public LogicalModuleId(String spelling) {
        this(parseSegments(spelling));
    }

    public static LogicalModuleId of(String spelling) {
        return new LogicalModuleId(spelling);
    }

    public static LogicalModuleId parse(String spelling) {
        return new LogicalModuleId(spelling);
    }

    public static LogicalModuleId of(List<String> segments) {
        return new LogicalModuleId(segments);
    }

    public static LogicalModuleId fromImportPath(SyntaxNode.ImportPath path) {
        Objects.requireNonNull(path, "path");
        return new LogicalModuleId(path.segments().stream()
                .map(SyntaxNode.Identifier::name)
                .toList());
    }

    /** Converts a path-shaped stable source identity back to a logical name. */
    public static LogicalModuleId fromSourceId(SourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isUri()) {
            throw new IllegalArgumentException(
                    "a URI source identity has no implicit logical module name: " + sourceId);
        }
        String path = sourceId.value();
        if (path.endsWith(".lyra")) {
            path = path.substring(0, path.length() - ".lyra".length());
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("source identity has no logical module name: " + sourceId);
        }
        return new LogicalModuleId(List.of(path.split("/", -1)));
    }

    public String value() {
        return String.join("->", segments);
    }

    public String spelling() {
        return value();
    }

    public String finalSegment() {
        return segments.getLast();
    }

    /** Returns the source-root-relative POSIX path for the default resolver. */
    public String relativeSourcePath() {
        return String.join("/", segments) + ".lyra";
    }

    public String relativePath() {
        return relativeSourcePath();
    }

    public SourceId defaultSourceId() {
        return SourceId.path(relativeSourcePath());
    }

    public boolean isStdIo() {
        return equals(STD_IO);
    }

    @Override
    public int compareTo(LogicalModuleId other) {
        Objects.requireNonNull(other, "other");
        return value().compareTo(other.value());
    }

    @Override
    public String toString() {
        return value();
    }

    private static List<String> parseSegments(String spelling) {
        Objects.requireNonNull(spelling, "spelling");
        if (spelling.isEmpty()) {
            throw new IllegalArgumentException("logical module path must not be empty");
        }
        return List.of(spelling.split("->", -1));
    }

    private static void validateSegment(String segment) {
        Objects.requireNonNull(segment, "logical module segment");
        if (!IDENTIFIER.matcher(segment).matches()) {
            throw new IllegalArgumentException(
                    "invalid logical module segment: " + segment);
        }
    }
}
