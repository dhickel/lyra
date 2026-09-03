package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.identity.JavaNameMangler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic logical-export to Java-member name plan for generated facades. */
final class JvmJavaNamePlan {
    private final Map<JvmExportId, String> mappings;
    private final Map<JvmExportId, String> invocationNames;

    public JvmJavaNamePlan(Map<JvmExportId, String> mappings) {
        Objects.requireNonNull(mappings, "mappings");
        ArrayList<Map.Entry<JvmExportId, String>> entries = new ArrayList<>(mappings.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<JvmExportId, String> copied = new LinkedHashMap<>();
        for (Map.Entry<JvmExportId, String> entry : entries) {
            JvmExportId id = Objects.requireNonNull(entry.getKey(), "mapping key");
            String name = JvmNames.requireMemberName(entry.getValue(), "Java export name");
            if (name.startsWith("$lyra$")) {
                throw new IllegalArgumentException("Java export name uses reserved Lyra infrastructure prefix");
            }
            if (copied.put(id, name) != null) {
                throw new IllegalArgumentException("duplicate Java export mapping: " + id);
            }
        }
        requireFacadeLocalMemberUniqueness(copied, "Java export members");
        this.mappings = Collections.unmodifiableMap(copied);
        LinkedHashMap<JvmExportId, String> invocations = new LinkedHashMap<>();
        for (JvmExportId id : copied.keySet()) {
            String invocation = JavaNameMangler.isFacadeInvocationReserved(id.exportName())
                    ? "invoke$" + copied.get(id) : copied.get(id);
            if (isFunctionContract(id.canonicalContract())
                    && JavaNameMangler.isFacadeInvocationReserved(invocation)) {
                throw new IllegalArgumentException(
                        "function invocation name collides with a facade-reserved member: "
                                + invocation);
            }
            boolean collision = invocations.entrySet().stream().anyMatch(entry ->
                    entry.getKey().moduleId().equals(id.moduleId())
                            && entry.getValue().equals(invocation));
            if (collision) {
                throw new IllegalArgumentException("facade invocation names collide: " + invocation);
            }
            invocations.put(id, invocation);
        }
        this.invocationNames = Collections.unmodifiableMap(invocations);
    }

    public static JvmJavaNamePlan plan(Collection<? extends JvmExportId> exports) {
        Objects.requireNonNull(exports, "exports");
        List<JvmExportId> sorted = exports.stream()
                .map(export -> Objects.requireNonNull(export, "exports must not contain null"))
                .sorted().toList();
        LinkedHashMap<JvmExportId, String> result = new LinkedHashMap<>();
        Map<io.mindspice.lyra.compiler.source.ModuleId, java.util.Set<String>> usedByModule =
                new java.util.HashMap<>();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).equals(sorted.get(index))) {
                throw new IllegalArgumentException("duplicate ABI export identity: " + sorted.get(index));
            }
        }
        for (JvmExportId export : sorted) {
            java.util.Set<String> used = usedByModule.computeIfAbsent(
                    export.moduleId(), ignored -> new java.util.HashSet<>());
            String base = JavaNameMangler.mangle(export.exportName());
            String candidate = base;
            if (!used.add(candidate)) {
                candidate = collisionName(base, export, used);
                if (!used.add(candidate)) {
                    throw new IllegalStateException("Java-name collision resolution failed: " + candidate);
                }
            }
            result.put(export, candidate);
        }
        return new JvmJavaNamePlan(result);
    }

    public Map<JvmExportId, String> mappings() {
        return mappings;
    }

    public Map<JvmExportId, String> names() {
        return mappings;
    }

    public Map<JvmExportId, String> invocationNames() {
        return invocationNames;
    }

    public String nameFor(JvmExportId export) {
        String name = mappings.get(Objects.requireNonNull(export, "export"));
        if (name == null) {
            throw new IllegalArgumentException("export is not in the Java name plan: " + export);
        }
        return name;
    }

    public String invocationNameFor(JvmExportId export) {
        String name = invocationNames.get(Objects.requireNonNull(export, "export"));
        if (name == null) {
            throw new IllegalArgumentException("export is not in the Java name plan: " + export);
        }
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof JvmJavaNamePlan plan
                && mappings.equals(plan.mappings)
                && invocationNames.equals(plan.invocationNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mappings, invocationNames);
    }

    @Override
    public String toString() {
        return "JvmJavaNamePlan" + mappings;
    }

    private static void requireFacadeLocalMemberUniqueness(
            Map<JvmExportId, String> mappings, String label) {
        Map<io.mindspice.lyra.compiler.source.ModuleId, java.util.Set<String>> byModule =
                new java.util.HashMap<>();
        for (Map.Entry<JvmExportId, String> entry : mappings.entrySet()) {
            JvmExportId export = entry.getKey();
            String mapped = entry.getValue();
            java.util.Set<String> names = byModule.computeIfAbsent(
                    export.moduleId(), ignored -> new java.util.HashSet<>());
            List<String> members = isFunctionContract(export.canonicalContract())
                    ? List.of(JavaNameMangler.isFacadeInvocationReserved(export.exportName())
                            ? "invoke$" + mapped : mapped,
                            "value$" + mapped, "set$" + mapped)
                    : List.of("get$" + mapped, "set$" + mapped);
            for (String member : members) {
                if (!names.add(member)) {
                    throw new IllegalArgumentException(
                            label + " must be collision-free within each facade: " + member);
                }
            }
        }
    }

    private static boolean isFunctionContract(String contract) {
        Objects.requireNonNull(contract, "contract");
        return contract.startsWith("Fn<")
                || contract.startsWith("@nilFn<");
    }

    private static String collisionName(String base, JvmExportId export, java.util.Set<String> used) {
        String shortName = base + "$" + export.hash().substring(0, 8);
        if (!used.contains(shortName)) {
            return shortName;
        }
        String fullName = base + "$" + export.hash();
        if (!used.contains(fullName)) {
            return fullName;
        }
        int ordinal = 2;
        String candidate;
        do {
            candidate = fullName + "$" + ordinal++;
        } while (used.contains(candidate));
        return candidate;
    }
}
