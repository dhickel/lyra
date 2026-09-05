package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable committed session input. It contains only typed declaration
 * contracts, identities, pinned source revisions, and namespace metadata.
 */
public record SessionSnapshot(
        SessionRevision revision,
        Map<String, ExternalBinding> bindings,
        Map<String, SessionImport> imports,
        Map<LogicalModuleId, PinnedModule> pinnedModules,
        IdentityAllocator allocator) {
    public SessionSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        bindings = copyBindings(bindings);
        imports = copyImports(imports);
        pinnedModules = copyPinned(pinnedModules);
        allocator = Objects.requireNonNull(allocator, "allocator");
        validateIdentities(bindings, allocator);
        for (String name : imports.keySet()) {
            if (bindings.containsKey(name)) {
                throw new IllegalArgumentException("binding and import alias share a name: " + name);
            }
        }
    }

    public SessionSnapshot(SessionRevision revision, Map<String, ExternalBinding> bindings,
                           Map<LogicalModuleId, PinnedModule> pinnedModules,
                           IdentityAllocator allocator) {
        this(revision, bindings, Map.of(), pinnedModules, allocator);
    }

    public static SessionSnapshot empty() {
        return new SessionSnapshot(SessionRevision.initial(), Map.of(), Map.of(), Map.of(),
                IdentityAllocator.initial());
    }

    public Optional<ExternalBinding> binding(String name) {
        return Optional.ofNullable(bindings.get(Objects.requireNonNull(name, "name")));
    }

    public Optional<SessionImport> importAlias(String name) {
        return Optional.ofNullable(imports.get(Objects.requireNonNull(name, "name")));
    }

    public List<ExternalBinding> orderedBindings() {
        return bindings.values().stream()
                .sorted(Comparator.comparing(ExternalBinding::name))
                .toList();
    }

    public Map<String, String> moduleRevisions() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        pinnedModules.values().stream()
                .sorted(Comparator.comparing(value -> value.logicalModule().value()))
                .forEach(value -> result.put(value.logicalModule().value(), value.revision()));
        return Map.copyOf(result);
    }

    public SessionSnapshot withBinding(ExternalBinding binding) {
        Objects.requireNonNull(binding, "binding");
        LinkedHashMap<String, ExternalBinding> next = new LinkedHashMap<>(bindings);
        next.put(binding.name(), binding);
        return new SessionSnapshot(revision, next, imports, pinnedModules, allocator);
    }

    public SessionSnapshot withImport(SessionImport value) {
        Objects.requireNonNull(value, "value");
        LinkedHashMap<String, SessionImport> next = new LinkedHashMap<>(imports);
        next.put(value.name(), value);
        return new SessionSnapshot(revision, bindings, next, pinnedModules, allocator);
    }

    public SessionSnapshot withPinnedModule(PinnedModule module) {
        Objects.requireNonNull(module, "module");
        LinkedHashMap<LogicalModuleId, PinnedModule> next = new LinkedHashMap<>(pinnedModules);
        PinnedModule previous = next.put(module.logicalModule(), module);
        if (previous != null && !previous.equals(module)) {
            throw new IllegalArgumentException(
                    "a logical module is already pinned to another source: " + module.logicalModule());
        }
        return new SessionSnapshot(revision, bindings, imports, next, allocator);
    }

    public SessionSnapshot withAllocator(IdentityAllocator nextAllocator) {
        return new SessionSnapshot(revision, bindings, imports, pinnedModules, nextAllocator);
    }

    public SessionSnapshot nextRevision(
            Map<String, ExternalBinding> nextBindings,
            Map<String, SessionImport> nextImports,
            IdentityAllocator nextAllocator) {
        return nextRevision(nextBindings, nextImports, pinnedModules, nextAllocator);
    }

    public SessionSnapshot nextRevision(
            Map<String, ExternalBinding> nextBindings,
            Map<String, SessionImport> nextImports,
            Map<LogicalModuleId, PinnedModule> nextPinnedModules,
            IdentityAllocator nextAllocator) {
        return new SessionSnapshot(revision.next(), nextBindings, nextImports,
                nextPinnedModules, nextAllocator);
    }

    private static Map<String, ExternalBinding> copyBindings(
            Map<String, ExternalBinding> values) {
        Objects.requireNonNull(values, "bindings");
        LinkedHashMap<String, ExternalBinding> result = new LinkedHashMap<>();
        for (Map.Entry<String, ExternalBinding> entry : values.entrySet()) {
            String key = token(entry.getKey(), "binding name");
            ExternalBinding value = Objects.requireNonNull(entry.getValue(), "binding");
            if (!key.equals(value.name())) {
                throw new IllegalArgumentException("binding map key does not match binding name");
            }
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate binding name: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, SessionImport> copyImports(
            Map<String, SessionImport> values) {
        Objects.requireNonNull(values, "imports");
        LinkedHashMap<String, SessionImport> result = new LinkedHashMap<>();
        for (Map.Entry<String, SessionImport> entry : values.entrySet()) {
            String key = token(entry.getKey(), "import alias");
            SessionImport value = Objects.requireNonNull(entry.getValue(), "import");
            if (!key.equals(value.name())) {
                throw new IllegalArgumentException("import map key does not match alias name");
            }
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate import alias: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<LogicalModuleId, PinnedModule> copyPinned(
            Map<LogicalModuleId, PinnedModule> values) {
        Objects.requireNonNull(values, "pinnedModules");
        LinkedHashMap<LogicalModuleId, PinnedModule> result = new LinkedHashMap<>();
        for (Map.Entry<LogicalModuleId, PinnedModule> entry : values.entrySet()) {
            LogicalModuleId key = Objects.requireNonNull(entry.getKey(), "pinned module key");
            PinnedModule value = Objects.requireNonNull(entry.getValue(), "pinned module");
            if (!key.equals(value.logicalModule())) {
                throw new IllegalArgumentException("pinned module map key does not match logical id");
            }
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate pinned logical module: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static void validateIdentities(
            Map<String, ExternalBinding> bindings, IdentityAllocator allocator) {
        List<DeclarationId> ids = new ArrayList<>();
        for (ExternalBinding binding : bindings.values()) {
            ids.add(binding.declarationId());
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("external declaration identities must be unique");
        }
        long maximum = ids.stream().mapToLong(DeclarationId::ordinal).max().orElse(-1L);
        if (maximum >= allocator.nextDeclarationOrdinal()) {
            throw new IllegalArgumentException(
                    "allocator must be advanced beyond every external declaration identity");
        }
        List<StorageIdentity> storage = bindings.values().stream()
                .flatMap(value -> value.storageIdentity().stream())
                .toList();
        if (storage.stream().distinct().count() != storage.size()) {
            throw new IllegalArgumentException("external storage identities must be unique");
        }
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }
}
