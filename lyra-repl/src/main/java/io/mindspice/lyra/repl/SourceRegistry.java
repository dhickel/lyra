package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/** Owner-thread source registry with fixed retained-record and UTF-16 bounds. */
final class SourceRegistry {
    private final int maxRecords;
    private final int maxCharacters;
    private final ArrayList<SourceRecord> records = new ArrayList<>();
    private final Set<SourceId> compilerSourceIds = new HashSet<>();
    /** Every source snapshot needed by a live producer, including imports. */
    private final Map<SourceKey, Integer> retainedSources = new HashMap<>();
    private int retainedCharacters;

    SourceRegistry(SessionOptions options) {
        Objects.requireNonNull(options, "options");
        maxRecords = options.maxSourceRecords();
        maxCharacters = options.maxSourceCharacters();
    }

    boolean canRetain(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        return canRetain(source.utf16Length(), null);
    }

    boolean canRetainGraph(Collection<SourceSnapshot> sources) {
        Objects.requireNonNull(sources, "sources");
        Map<SourceKey, Integer> additions = new HashMap<>();
        for (SourceSnapshot source : sources) {
            Objects.requireNonNull(source, "sources must not contain null");
            additions.putIfAbsent(new SourceKey(source.sourceId(), source.sha256()), source.utf16Length());
        }
        int addedCount = 0;
        int addedCharacters = 0;
        for (Map.Entry<SourceKey, Integer> entry : additions.entrySet()) {
            if (retainedSources.containsKey(entry.getKey())) continue;
            addedCount = Math.addExact(addedCount, 1);
            addedCharacters = Math.addExact(addedCharacters, entry.getValue());
        }
        return retainedSources.size() <= maxRecords - addedCount
                && retainedCharacters <= maxCharacters - addedCharacters;
    }

    void retainGraph(Collection<SourceSnapshot> sources) {
        Objects.requireNonNull(sources, "sources");
        if (!canRetainGraph(sources)) {
            throw new IllegalStateException("source registry graph capacity was not reserved");
        }
        for (SourceSnapshot source : sources) {
            retainSource(source.sourceId(), source.utf16Length(), source.sha256());
        }
    }

    /** Chooses a stable compiler identity while avoiding duplicate module IDs. */
    SourceId reserve(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        SourceId sourceId = candidate(request);
        if (!canRetain(request.source().utf16Length(), sourceId)) {
            throw new IllegalStateException("source registry capacity was not reserved");
        }
        if (!compilerSourceIds.add(sourceId)) {
            throw new IllegalStateException("duplicate compiler source identity");
        }
        return sourceId;
    }

    /** Returns the same candidate that {@link #reserve} would choose. */
    SourceId candidate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        SourceId preferred = preferred(request);
        if (!compilerSourceIds.contains(preferred) && !hasSourceId(preferred)) {
            return preferred;
        }
        String base = "repl/submission-" + request.evaluationId();
        for (int suffix = 0; ; suffix++) {
            String name = suffix == 0 ? base + ".lyra" : base + "-" + suffix + ".lyra";
            SourceId unique = SourceId.path(name);
            if (!compilerSourceIds.contains(unique) && !hasSourceId(unique)) {
                return unique;
            }
        }
    }

    void append(SourceRecord record) {
        Objects.requireNonNull(record, "record");
        if (!compilerSourceIds.contains(record.compilerSourceId())) {
            throw new IllegalArgumentException("source record was not reserved");
        }
        String hash = hash(record.source().text());
        if (!canRetain(record.source().utf16Length(), record.compilerSourceId(), hash)) {
            throw new IllegalStateException("source registry capacity was exceeded");
        }
        records.add(record);
        retainSource(record.compilerSourceId(), record.source().utf16Length(), hash);
    }

    List<SourceRecord> records() {
        return List.copyOf(records);
    }

    void clear() {
        records.clear();
        compilerSourceIds.clear();
        retainedSources.clear();
        retainedCharacters = 0;
    }

    private boolean canRetain(int length, SourceId sourceId) {
        return canRetain(length, sourceId, null);
    }

    private boolean canRetain(int length, SourceId sourceId, String hash) {
        if (length < 0) return false;
        if (sourceId != null && hash != null && retainedSources.containsKey(new SourceKey(sourceId, hash))) {
            return true;
        }
        return retainedSources.size() < maxRecords
                && length <= maxCharacters
                && retainedCharacters <= maxCharacters - length;
    }

    private void retainSource(SourceId sourceId, int length, String hash) {
        SourceKey key = new SourceKey(sourceId, hash);
        Integer previous = retainedSources.putIfAbsent(key, length);
        if (previous == null) {
            retainedCharacters = Math.addExact(retainedCharacters, length);
        } else if (previous != length) {
            throw new IllegalStateException("a retained source revision changed text length");
        }
    }

    private boolean hasSourceId(SourceId sourceId) {
        return retainedSources.keySet().stream().anyMatch(key -> key.sourceId().equals(sourceId));
    }

    private static String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record SourceKey(SourceId sourceId, String hash) {
        private SourceKey {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(hash, "hash");
        }
    }

    private static SourceId preferred(EvaluationRequest request) {
        // A submission's compiler identity must be owned by this session and
        // remain disjoint from any resolver-provided module identity. Caller
        // origin data is retained separately for diagnostics and source tools.
        return SourceId.path("repl/submission-" + request.evaluationId() + ".lyra");
    }
}
