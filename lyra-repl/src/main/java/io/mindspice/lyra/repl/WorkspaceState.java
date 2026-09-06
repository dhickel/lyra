package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.source.ModuleRevision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable namespace metadata used to describe committed and staged session
 * views. It contains identities and types only, never live values.
 */
public sealed interface WorkspaceState permits WorkspaceState.Committed, WorkspaceState.Pending {
    SessionRevision revision();

    Map<String, BindingMetadata> bindings();

    Map<String, String> moduleRevisions();

    default boolean isCommitted() {
        return this instanceof Committed;
    }

    default boolean isPending() {
        return this instanceof Pending;
    }

    static Committed empty() {
        return new Committed(SessionRevision.initial(), Map.of(), Map.of());
    }

    record Committed(
            SessionRevision revision,
            Map<String, BindingMetadata> bindings,
            Map<String, String> moduleRevisions) implements WorkspaceState {
        public Committed {
            revision = Objects.requireNonNull(revision, "revision");
            bindings = copyBindings(bindings);
            moduleRevisions = copyModuleRevisions(moduleRevisions);
        }
    }

    record Pending(
            SessionRevision baseRevision,
            SessionRevision revision,
            Map<String, BindingMetadata> bindings,
            Map<String, String> moduleRevisions) implements WorkspaceState {
        public Pending {
            baseRevision = Objects.requireNonNull(baseRevision, "baseRevision");
            revision = Objects.requireNonNull(revision, "revision");
            if (revision.value() < baseRevision.value()) {
                throw new IllegalArgumentException(
                        "pending revision cannot precede its base revision");
            }
            bindings = copyBindings(bindings);
            moduleRevisions = copyModuleRevisions(moduleRevisions);
        }
    }

    private static Map<String, BindingMetadata> copyBindings(
            Map<String, BindingMetadata> values) {
        Objects.requireNonNull(values, "bindings");
        LinkedHashMap<String, BindingMetadata> copy = new LinkedHashMap<>();
        for (Map.Entry<String, BindingMetadata> entry : values.entrySet()) {
            String name = token(entry.getKey(), "binding name");
            BindingMetadata metadata = Objects.requireNonNull(
                    entry.getValue(), "bindings must not contain null values");
            if (!name.equals(metadata.name())) {
                throw new IllegalArgumentException(
                        "binding map key does not match metadata name: " + name);
            }
            if (copy.put(name, metadata) != null) {
                throw new IllegalArgumentException("duplicate binding name: " + name);
            }
        }
        return Map.copyOf(copy);
    }

    private static Map<String, String> copyModuleRevisions(
            Map<String, String> values) {
        Objects.requireNonNull(values, "moduleRevisions");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String module = token(entry.getKey(), "module identity");
            String revision = token(entry.getValue(), "module revision");
            if (!ModuleRevision.isRevision(revision)) {
                throw new IllegalArgumentException(
                        "module revision must be a SHA-256 hexadecimal value: " + revision);
            }
            if (copy.put(module, revision) != null) {
                throw new IllegalArgumentException("duplicate module identity: " + module);
            }
        }
        return Map.copyOf(copy);
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }
}
