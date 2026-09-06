package io.mindspice.lyra.compiler.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider for logical modules outside the default filesystem roots.
 * Implementations must be side-effect free with respect to the returned
 * source value and must return an immutable {@link ResolvedSource} candidate
 * whose filesystem physical key is already canonical (use
 * {@link PhysicalSourceKey#from(java.nio.file.Path)} for files).
 * Discovery still queries every configured resolver and performs the
 * ambiguity and physical-identity checks centrally.
 */
@FunctionalInterface
public interface SourceResolver {
    Optional<ResolvedSource> resolve(LogicalModuleId logicalModule);

    default Optional<ResolvedSource> resolve(String logicalModule) {
        return resolve(LogicalModuleId.parse(logicalModule));
    }

    /** Creates an immutable resolver backed by a logical-module map. */
    static SourceResolver inMemory(Map<LogicalModuleId, ResolvedSource> sources) {
        Objects.requireNonNull(sources, "sources");
        Map<LogicalModuleId, ResolvedSource> copied = new HashMap<>();
        for (Map.Entry<LogicalModuleId, ResolvedSource> entry : sources.entrySet()) {
            LogicalModuleId key = Objects.requireNonNull(entry.getKey(), "source map key");
            ResolvedSource value = Objects.requireNonNull(entry.getValue(), "source map value");
            copied.put(key, value);
        }
        Map<LogicalModuleId, ResolvedSource> frozen = Map.copyOf(copied);
        return logicalModule -> Optional.ofNullable(frozen.get(
                Objects.requireNonNull(logicalModule, "logicalModule")));
    }

    static SourceResolver memory(Map<LogicalModuleId, ResolvedSource> sources) {
        return inMemory(sources);
    }

    /** Creates an immutable resolver from a fixed set of resolved sources. */
    static SourceResolver inMemory(Iterable<ResolvedSource> sources) {
        Objects.requireNonNull(sources, "sources");
        Map<LogicalModuleId, ResolvedSource> byLogical = new HashMap<>();
        for (ResolvedSource source : sources) {
            Objects.requireNonNull(source, "sources must not contain null");
            ResolvedSource previous = byLogical.put(source.logicalModule(), source);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate in-memory logical module: " + source.logicalModule());
            }
        }
        return inMemory(byLogical);
    }

    static SourceResolver memory(ResolvedSource... sources) {
        Objects.requireNonNull(sources, "sources");
        List<ResolvedSource> copied = new ArrayList<>();
        for (ResolvedSource source : sources) {
            copied.add(Objects.requireNonNull(source, "sources must not contain null"));
        }
        return inMemory(copied);
    }

    static SourceResolver single(ResolvedSource source) {
        return inMemory(List.of(Objects.requireNonNull(source, "source")));
    }
}
