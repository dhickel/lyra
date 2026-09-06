package io.mindspice.lyra.compiler.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic source-name planner for a later Java facade.  It produces
 * names only; it emits no classes and consults no JVM descriptor state.
 */
public final class JavaNameMangler {
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null", "_",
            "record", "sealed", "permits", "non-sealed", "var", "yield",
            "module", "open", "opens", "requires", "transitive", "exports", "to",
            "uses", "provides", "with", "when" );
    private static final Set<String> FACADE_INVOCATION_RESERVED = Set.of(
            "close", "equals", "hashCode", "toString", "getClass", "clone", "finalize",
            "notify", "notifyAll", "wait");
    private static final Comparator<ExportId> EXPORT_ORDER =
            Comparator.comparing(ExportId::canonicalInput);

    private JavaNameMangler() {
    }

    public static String mangle(ExportId exportId) {
        Objects.requireNonNull(exportId, "exportId");
        return baseName(exportId.exportName());
    }

    public static String mangle(String sourceName) {
        return baseName(sourceName);
    }

    /** Returns the typed facade invocation member name for an export. */
    public static String mangleInvocation(String sourceName) {
        String mapped = baseName(sourceName);
        return FACADE_INVOCATION_RESERVED.contains(sourceName)
                ? "invoke$" + mapped
                : mapped;
    }

    public static String mangleInvocation(ExportId exportId) {
        Objects.requireNonNull(exportId, "exportId");
        return mangleInvocation(exportId.exportName());
    }

    public static boolean isFacadeInvocationReserved(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        return FACADE_INVOCATION_RESERVED.contains(sourceName);
    }

    public static Plan plan(Collection<? extends ExportId> exports) {
        Objects.requireNonNull(exports, "exports");
        List<ExportId> sorted = exports.stream()
                .map(export -> Objects.requireNonNull(export, "exports must not contain null"))
                .sorted(EXPORT_ORDER)
                .toList();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).equals(sorted.get(index))) {
                throw new IllegalArgumentException("duplicate export identity: " + sorted.get(index));
            }
        }

        Map<String, List<ExportId>> byBase = new HashMap<>();
        for (ExportId export : sorted) {
            byBase.computeIfAbsent(baseName(export.exportName()), ignored -> new ArrayList<>())
                    .add(export);
        }
        Set<String> baseNames = Set.copyOf(byBase.keySet());
        Set<String> used = new HashSet<>();
        LinkedHashMap<ExportId, String> mappings = new LinkedHashMap<>();
        for (ExportId export : sorted) {
            String base = baseName(export.exportName());
            List<ExportId> collisionGroup = byBase.get(base);
            String candidate;
            if (collisionGroup.getFirst().equals(export) && !used.contains(base)) {
                // Stable-ID order owns the readable base spelling; only the
                // remaining members of a collision need a hash suffix.
                candidate = base;
            } else {
                candidate = collisionName(base, export, baseNames, used);
            }
            if (!used.add(candidate)) {
                throw new IllegalStateException("Java-name planner produced a collision: " + candidate);
            }
            mappings.put(export, candidate);
        }
        return new Plan(mappings);
    }

    private static String baseName(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        if (sourceName.isEmpty()) {
            throw new IllegalArgumentException("source name must not be empty");
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < sourceName.length(); index++) {
            char character = sourceName.charAt(index);
            if (isAsciiLetter(character) || character == '_' || isAsciiDigit(character)) {
                if (index == 0 && isAsciiDigit(character)) {
                    appendEscape(result, character);
                } else {
                    result.append(character);
                }
            } else {
                appendEscape(result, character);
            }
        }
        String mapped = result.toString();
        return JAVA_KEYWORDS.contains(sourceName) || JAVA_KEYWORDS.contains(mapped)
                ? "lyra$" + mapped
                : mapped;
    }

    private static String collisionName(
            String base, ExportId export, Set<String> baseNames, Set<String> used) {
        String shortCandidate = base + "$" + export.hash().substring(0, 8);
        if (!baseNames.contains(shortCandidate) && !used.contains(shortCandidate)) {
            return shortCandidate;
        }
        String fullCandidate = base + "$" + export.hash();
        if (!baseNames.contains(fullCandidate) && !used.contains(fullCandidate)) {
            return fullCandidate;
        }
        int ordinal = 2;
        String candidate;
        do {
            candidate = fullCandidate + "$" + ordinal++;
        } while (baseNames.contains(candidate) || used.contains(candidate));
        return candidate;
    }

    private static void appendEscape(StringBuilder result, char character) {
        result.append("$u");
        result.append(Character.forDigit((character >>> 12) & 0xF, 16));
        result.append(Character.forDigit((character >>> 8) & 0xF, 16));
        result.append(Character.forDigit((character >>> 4) & 0xF, 16));
        result.append(Character.forDigit(character & 0xF, 16));
        int start = result.length() - 4;
        for (int index = start; index < result.length(); index++) {
            result.setCharAt(index, Character.toUpperCase(result.charAt(index)));
        }
    }

    private static boolean isAsciiLetter(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z';
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /** Immutable logical-to-Java name map. */
    public record Plan(Map<ExportId, String> mappings) {
        public Plan {
            Objects.requireNonNull(mappings, "mappings");
            LinkedHashMap<ExportId, String> copied = new LinkedHashMap<>();
            mappings.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(EXPORT_ORDER))
                    .forEach(entry -> {
                        ExportId id = Objects.requireNonNull(entry.getKey(), "mapping key");
                        String name = Objects.requireNonNull(entry.getValue(), "mapping value");
                        if (copied.put(id, name) != null) {
                            throw new IllegalArgumentException("duplicate export mapping: " + id);
                        }
                    });
            if (copied.values().stream().distinct().count() != copied.size()) {
                throw new IllegalArgumentException("Java-name mappings must be collision-free");
            }
            mappings = java.util.Collections.unmodifiableMap(copied);
        }

        public String nameFor(ExportId exportId) {
            return mappings.get(Objects.requireNonNull(exportId, "exportId"));
        }

        public Map<ExportId, String> names() {
            return mappings;
        }

        /** Returns the reserved-name-safe invocation mapping for every export. */
        public Map<ExportId, String> invocationNames() {
            LinkedHashMap<ExportId, String> result = new LinkedHashMap<>();
            mappings.keySet().stream()
                    .sorted(EXPORT_ORDER)
                    .forEach(export -> result.put(export, invocationName(export)));
            if (result.values().stream().distinct().count() != result.size()) {
                throw new IllegalStateException("facade invocation mappings must be collision-free");
            }
            return java.util.Collections.unmodifiableMap(result);
        }

        public String invocationNameFor(ExportId exportId) {
            Objects.requireNonNull(exportId, "exportId");
            if (!mappings.containsKey(exportId)) {
                throw new IllegalArgumentException("export is not in this name plan: " + exportId);
            }
            return invocationName(exportId);
        }

        private String invocationName(ExportId exportId) {
            String mapped = mappings.get(exportId);
            return isFacadeInvocationReserved(exportId.exportName())
                    ? "invoke$" + mapped
                    : mapped;
        }
    }
}
