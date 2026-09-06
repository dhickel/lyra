package io.mindspice.lyra.compiler.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical semantics-affecting options used when computing module
 * revisions.  Source roots, resolver order, aliases, and physical paths are
 * intentionally not options and therefore cannot affect a revision.
 */
public record RevisionOptions(Map<String, String> values) {
    public RevisionOptions {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "option key");
            String value = Objects.requireNonNull(entry.getValue(), "option value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("revision option keys must not be blank");
            }
            if (containsControl(key) || containsControl(value)) {
                throw new IllegalArgumentException(
                        "revision option keys and values must not contain control characters");
            }
            if (copied.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate revision option: " + key);
            }
        }
        values = Collections.unmodifiableMap(copied);
    }

    public static RevisionOptions empty() {
        return new RevisionOptions(Map.of());
    }

    public static RevisionOptions of(Map<String, String> values) {
        return new RevisionOptions(values);
    }

    /** Returns entries in the canonical UTF-16 lexicographic key order. */
    public List<Map.Entry<String, String>> canonicalEntries() {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            entries.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        entries.sort(Map.Entry.comparingByKey());
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
