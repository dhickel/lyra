package io.mindspice.lyra.compiler.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable inputs to whole-graph source discovery.
 *
 * <p>Every configured filesystem root and every resolver participates in each
 * logical-module query.  The discovery phase, rather than this value object,
 * performs canonical filesystem validation so invalid configuration is
 * returned as structured phase data.</p>
 */
public record SourceConfiguration(
        List<SourceRoot> sourceRoots,
        List<SourceResolver> resolvers,
        RevisionOptions revisionOptions) {
    public SourceConfiguration {
        Objects.requireNonNull(sourceRoots, "sourceRoots");
        Objects.requireNonNull(resolvers, "resolvers");
        Objects.requireNonNull(revisionOptions, "revisionOptions");
        sourceRoots = copyRoots(sourceRoots);
        resolvers = copyResolvers(resolvers);
    }

    public SourceConfiguration(List<SourceRoot> sourceRoots, List<SourceResolver> resolvers) {
        this(sourceRoots, resolvers, RevisionOptions.empty());
    }

    public static SourceConfiguration empty() {
        return new SourceConfiguration(List.of(), List.of(), RevisionOptions.empty());
    }

    /** Accepts either {@link SourceRoot} or {@link Path} entries for convenience. */
    public static SourceConfiguration of(
            List<?> sourceRoots, List<SourceResolver> resolvers) {
        return new SourceConfiguration(toRootsAny(sourceRoots), resolvers);
    }

    /** Accepts either {@link SourceRoot} or {@link Path} entries for convenience. */
    public static SourceConfiguration of(
            List<?> sourceRoots,
            List<SourceResolver> resolvers,
            RevisionOptions revisionOptions) {
        return new SourceConfiguration(toRootsAny(sourceRoots), resolvers, revisionOptions);
    }

    public static SourceConfiguration of(
            List<?> sourceRoots,
            List<SourceResolver> resolvers,
            Map<String, String> revisionOptions) {
        return new SourceConfiguration(
                toRootsAny(sourceRoots), resolvers, RevisionOptions.of(revisionOptions));
    }

    public static SourceConfiguration ofRoots(List<Path> sourceRoots) {
        return new SourceConfiguration(toRoots(sourceRoots), List.of());
    }

    public static SourceConfiguration ofRoot(Path sourceRoot) {
        return ofRoots(List.of(sourceRoot));
    }

    public static SourceConfiguration ofPaths(
            List<Path> sourceRoots, List<SourceResolver> resolvers) {
        return new SourceConfiguration(toRoots(sourceRoots), resolvers);
    }

    public static SourceConfiguration ofPaths(
            List<Path> sourceRoots,
            List<SourceResolver> resolvers,
            Map<String, String> revisionOptions) {
        return new SourceConfiguration(
                toRoots(sourceRoots), resolvers, RevisionOptions.of(revisionOptions));
    }

    public SourceConfiguration withSourceRoots(List<SourceRoot> roots) {
        return new SourceConfiguration(roots, resolvers, revisionOptions);
    }

    public SourceConfiguration withResolvers(List<SourceResolver> values) {
        return new SourceConfiguration(sourceRoots, values, revisionOptions);
    }

    public SourceConfiguration withRevisionOptions(RevisionOptions options) {
        return new SourceConfiguration(sourceRoots, resolvers, options);
    }

    public SourceConfiguration withRevisionOptions(Map<String, String> options) {
        return withRevisionOptions(RevisionOptions.of(options));
    }

    public List<Path> rootPaths() {
        return sourceRoots.stream().map(SourceRoot::path).toList();
    }

    public RevisionOptions semanticsOptions() {
        return revisionOptions;
    }

    private static List<SourceRoot> copyRoots(List<SourceRoot> values) {
        List<SourceRoot> copy = new ArrayList<>();
        for (SourceRoot value : values) {
            copy.add(Objects.requireNonNull(value, "sourceRoots must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<SourceResolver> copyResolvers(List<SourceResolver> values) {
        List<SourceResolver> copy = new ArrayList<>();
        for (SourceResolver value : values) {
            copy.add(Objects.requireNonNull(value, "resolvers must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<SourceRoot> toRoots(List<Path> values) {
        Objects.requireNonNull(values, "sourceRoots");
        List<SourceRoot> roots = new ArrayList<>();
        for (Path value : values) {
            roots.add(new SourceRoot(Objects.requireNonNull(value, "source root")));
        }
        return roots;
    }

    private static List<SourceRoot> toRootsAny(List<?> values) {
        Objects.requireNonNull(values, "sourceRoots");
        List<SourceRoot> roots = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof SourceRoot sourceRoot) {
                roots.add(sourceRoot);
            } else if (value instanceof Path path) {
                roots.add(new SourceRoot(path));
            } else {
                throw new IllegalArgumentException(
                        "source root must be a SourceRoot or Path: " + value);
            }
        }
        return roots;
    }
}
