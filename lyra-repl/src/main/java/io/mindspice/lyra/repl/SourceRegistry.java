package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.source.SourceId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Owner-thread source registry with fixed retained-record and UTF-16 bounds. */
final class SourceRegistry {
    private final int maxRecords;
    private final int maxCharacters;
    private final ArrayList<SourceRecord> records = new ArrayList<>();
    private final Set<SourceId> compilerSourceIds = new HashSet<>();
    private int retainedCharacters;

    SourceRegistry(SessionOptions options) {
        Objects.requireNonNull(options, "options");
        maxRecords = options.maxSourceRecords();
        maxCharacters = options.maxSourceCharacters();
    }

    boolean canRetain(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        int length = source.utf16Length();
        return records.size() < maxRecords
                && length <= maxCharacters
                && retainedCharacters <= maxCharacters - length;
    }

    /** Chooses a stable compiler identity while avoiding duplicate module IDs. */
    SourceId reserve(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!canRetain(request.source())) {
            throw new IllegalStateException("source registry capacity was not reserved");
        }
        SourceId sourceId = candidate(request);
        if (!compilerSourceIds.add(sourceId)) {
            throw new IllegalStateException("duplicate compiler source identity");
        }
        return sourceId;
    }

    /** Returns the same candidate that {@link #reserve} would choose. */
    SourceId candidate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        SourceId preferred = preferred(request);
        if (!compilerSourceIds.contains(preferred)) {
            return preferred;
        }
        String base = "repl/submission-" + request.evaluationId();
        for (int suffix = 0; ; suffix++) {
            String name = suffix == 0 ? base + ".lyra" : base + "-" + suffix + ".lyra";
            SourceId unique = SourceId.path(name);
            if (!compilerSourceIds.contains(unique)) {
                return unique;
            }
        }
    }

    void append(SourceRecord record) {
        Objects.requireNonNull(record, "record");
        if (!compilerSourceIds.contains(record.compilerSourceId())) {
            throw new IllegalArgumentException("source record was not reserved");
        }
        if (!canRetain(record.source())) {
            throw new IllegalStateException("source registry capacity was exceeded");
        }
        records.add(record);
        retainedCharacters = Math.addExact(retainedCharacters, record.source().utf16Length());
    }

    List<SourceRecord> records() {
        return List.copyOf(records);
    }

    void clear() {
        records.clear();
        compilerSourceIds.clear();
        retainedCharacters = 0;
    }

    private static SourceId preferred(EvaluationRequest request) {
        // A submission's compiler identity must be owned by this session and
        // remain disjoint from any resolver-provided module identity. Caller
        // origin data is retained separately for diagnostics and source tools.
        return SourceId.path("repl/submission-" + request.evaluationId() + ".lyra");
    }
}
