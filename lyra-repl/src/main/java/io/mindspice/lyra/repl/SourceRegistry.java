package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/** Owner-thread source registry with fixed retained-record and UTF-16 bounds. */
final class SourceRegistry {
    private final int maxRecords;
    private final int maxCharacters;
    private final ArrayList<SourceRecord> records = new ArrayList<>();
    /** Account the same caller text at preflight and append, even when decoding strips a BOM. */
    private final Map<SourceId, EvaluationSource> reservedSubmissions = new HashMap<>();
    /** Every source snapshot needed by a live producer, including imports. */
    private final Map<SourceKey, Integer> retainedSources = new HashMap<>();
    private final Map<SourceId, SourceSnapshot> graphSources = new HashMap<>();
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
            SourceSnapshot previous = graphSources.get(source.sourceId());
            if (previous != null && !previous.equals(source)) return false;
            EvaluationSource submitted = reservedSubmissions.get(source.sourceId());
            additions.putIfAbsent(new SourceKey(source.sourceId(), submitted == null ? source.sha256() : hash(submitted.text())),
                    submitted == null ? source.utf16Length() : submitted.utf16Length());
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
            EvaluationSource submitted = reservedSubmissions.get(source.sourceId());
            retainSource(source.sourceId(), submitted == null ? source.utf16Length() : submitted.utf16Length(),
                    submitted == null ? source.sha256() : hash(submitted.text()));
            graphSources.putIfAbsent(source.sourceId(), source);
        }
    }

    /** Chooses a stable compiler identity while avoiding duplicate module IDs. */
    SourceId reserve(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        SourceId sourceId = candidate(request);
        if (!canRetain(request.source().utf16Length(), sourceId)) {
            throw new IllegalStateException("source registry capacity was not reserved");
        }
        if (reservedSubmissions.putIfAbsent(sourceId, request.source()) != null) {
            throw new IllegalStateException("duplicate compiler source identity");
        }
        return sourceId;
    }

    /** Returns the same candidate that {@link #reserve} would choose. */
    SourceId candidate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        SourceId preferred = preferred(request);
        if (!reservedSubmissions.containsKey(preferred) && !hasSourceId(preferred)) {
            return preferred;
        }
        String base = "repl/submission-" + request.evaluationId();
        for (int suffix = 0; ; suffix++) {
            String name = suffix == 0 ? base + ".lyra" : base + "-" + suffix + ".lyra";
            SourceId unique = SourceId.path(name);
            if (!reservedSubmissions.containsKey(unique) && !hasSourceId(unique)) {
                return unique;
            }
        }
    }

    void append(SourceRecord record) {
        Objects.requireNonNull(record, "record");
        if (!reservedSubmissions.containsKey(record.compilerSourceId())) {
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

    java.util.Optional<EvaluationSource> source(SourceId sourceId) {
        var submitted = records.stream().filter(record -> record.compilerSourceId().equals(sourceId))
                .map(SourceRecord::source).findFirst();
        if (submitted.isPresent()) return submitted;
        return java.util.Optional.ofNullable(graphSources.get(sourceId)).map(snapshot -> {
            var original = snapshot.originSourceId();
            var uri = original.isUri() ? java.util.Optional.of(original.asUri())
                    : snapshot.physicalKey().isPath()
                    ? java.util.Optional.of(snapshot.physicalKey().asPath().toUri())
                    : java.util.Optional.<java.net.URI>empty();
            return new EvaluationSource(new SourceOrigin(original.value(), uri, java.util.Optional.empty(),
                    0, snapshot.utf16Length()), snapshot.text());
        });
    }

    void clear() {
        records.clear();
        graphSources.clear();
        reservedSubmissions.clear();
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
