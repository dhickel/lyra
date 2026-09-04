package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Public source-provider contract used during whole-graph compilation. */
@FunctionalInterface
public interface SourceResolver extends io.mindspice.lyra.compiler.source.SourceResolver {
    @Override
    Optional<ResolvedSource> resolve(LogicalModuleId logicalModule);

    /** Creates an immutable resolver backed by logical module identities. */
    static SourceResolver inMemory(Map<LogicalModuleId, ResolvedSource> sources) {
        Objects.requireNonNull(sources, "sources");
        Map<LogicalModuleId, ResolvedSource> copied = Map.copyOf(sources);
        return logicalModule -> Optional.ofNullable(copied.get(
                Objects.requireNonNull(logicalModule, "logicalModule")));
    }

    static SourceResolver memory(Map<LogicalModuleId, ResolvedSource> sources) {
        return inMemory(sources);
    }

    /** Creates an immutable resolver backed by a fixed source collection. */
    static SourceResolver inMemory(Iterable<ResolvedSource> sources) {
        return sourceMap(sources);
    }

    static SourceResolver memory(Iterable<ResolvedSource> sources) {
        return inMemory(sources);
    }

    static SourceResolver memory(ResolvedSource... sources) {
        Objects.requireNonNull(sources, "sources");
        return sourceMap(java.util.List.of(sources));
    }

    static SourceResolver single(ResolvedSource source) {
        return inMemory(java.util.List.of(Objects.requireNonNull(source, "source")));
    }

    private static SourceResolver sourceMap(Iterable<ResolvedSource> sources) {
        Objects.requireNonNull(sources, "sources");
        java.util.HashMap<LogicalModuleId, ResolvedSource> map = new java.util.HashMap<>();
        for (ResolvedSource source : sources) {
            Objects.requireNonNull(source, "sources must not contain null");
            if (map.put(source.logicalModule(), source) != null) {
                throw new IllegalArgumentException("duplicate in-memory logical module: "
                        + source.logicalModule());
            }
        }
        return inMemory(map);
    }
}
