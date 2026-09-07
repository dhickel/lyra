package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.api.SessionFlowCertificate;
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
 * Immutable session compilation input. It contains typed declaration
 * contracts, identities, pinned source revisions, namespace metadata and an
 * optional compiler-issued flow certificate for retained source-local values.
 *
 * <p>A snapshot may be staged by compilation before any code executes. Its
 * revision, allocator and certificate establish compiler metadata consistency,
 * not successful initialization or ownership of live storage. It is not a
 * runtime link capability and must never substitute for owner-thread link
 * validation.</p>
 */
public record SessionSnapshot(
        SessionRevision revision,
        Map<String, ExternalBinding> bindings,
        Map<String, SessionImport> imports,
        Map<LogicalModuleId, PinnedModule> pinnedModules,
        IdentityAllocator allocator,
        Optional<SessionFlowCertificate> flowCertificate,
        SessionModuleEnvironment moduleEnvironment) {
    public SessionSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        bindings = copyBindings(bindings);
        imports = copyImports(imports);
        pinnedModules = copyPinned(pinnedModules);
        allocator = Objects.requireNonNull(allocator, "allocator");
        flowCertificate = Objects.requireNonNull(flowCertificate, "flowCertificate");
        moduleEnvironment = Objects.requireNonNull(moduleEnvironment, "moduleEnvironment");
        if (flowCertificate.isPresent()
                && !allocator.dominates(flowCertificate.orElseThrow().allocator())) {
            throw new IllegalArgumentException(
                    "snapshot allocator regressed its flow certificate");
        }
        validateIdentities(bindings, allocator);
        validateEnvironment(moduleEnvironment, allocator);
        for (var module : moduleEnvironment.modules()) {
            if (module.isIntrinsic()) continue;
            var pin = pinnedModules.get(module.logicalModule());
            if (pin == null || !pin.moduleId().equals(module.moduleId())
                    || !pin.snapshot().equals(module.source()) || !pin.revision().equals(module.revision())
                    || !pin.revisionOptions().values().equals(module.revisionOptions())) {
                throw new IllegalArgumentException("retained producer and pinned source/options disagree");
            }
        }
        for (var imported : imports.values()) {
            if (imported.isSelective() && imported.moduleContract().isEmpty()) {
                throw new IllegalArgumentException("retained selected import requires its exact producer contract");
            }
            if (imported.moduleContract().isPresent()) {
                SessionModuleContract contract = imported.moduleContract().orElseThrow();
                if (!contract.producer().logicalModule().equals(imported.logicalModule())
                        || !contract.producer().moduleId().equals(imported.moduleId())) {
                    throw new IllegalArgumentException(
                            "retained import contract disagrees with its producer identity");
                }
                if (moduleEnvironment.module(imported.moduleId()).isEmpty()
                        || !contract.equals(SessionModuleContract.from(moduleEnvironment, imported.moduleId()))) {
                    throw new IllegalArgumentException("retained import differs from its exact historical producer");
                }
            }
        }
        for (String name : imports.keySet()) {
            if (bindings.containsKey(name)) {
                throw new IllegalArgumentException("binding and import alias share a name: " + name);
            }
        }
    }

    /** Compatibility constructor for snapshots without imports or flow proof. */
    public SessionSnapshot(SessionRevision revision, Map<String, ExternalBinding> bindings,
                           Map<LogicalModuleId, PinnedModule> pinnedModules,
                           IdentityAllocator allocator) {
        this(revision, bindings, Map.of(), pinnedModules, allocator, Optional.empty(),
                SessionModuleEnvironment.empty());
    }

    /** Compatibility constructor for snapshots without a flow proof. */
    public SessionSnapshot(SessionRevision revision, Map<String, ExternalBinding> bindings,
                           Map<String, SessionImport> imports,
                           Map<LogicalModuleId, PinnedModule> pinnedModules,
                           IdentityAllocator allocator) {
        this(revision, bindings, imports, pinnedModules, allocator, Optional.empty(),
                SessionModuleEnvironment.empty());
    }

    /** Compatibility constructor for snapshots with explicit module environment data. */
    public SessionSnapshot(SessionRevision revision, Map<String, ExternalBinding> bindings,
                           Map<String, SessionImport> imports,
                           Map<LogicalModuleId, PinnedModule> pinnedModules,
                           IdentityAllocator allocator,
                           SessionModuleEnvironment moduleEnvironment) {
        this(revision, bindings, imports, pinnedModules, allocator, Optional.empty(),
                moduleEnvironment);
    }

    public static SessionSnapshot empty() {
        return new SessionSnapshot(SessionRevision.initial(), Map.of(), Map.of(), Map.of(),
                IdentityAllocator.initial(), Optional.empty(), SessionModuleEnvironment.empty());
    }

    public SessionModuleEnvironment moduleEnvironment() {
        return moduleEnvironment;
    }

    /** Alias for callers that describe the value as retained module metadata. */
    public SessionModuleEnvironment environment() {
        return moduleEnvironment;
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

    public List<SessionImport> orderedImports() {
        return imports.values().stream()
                .sorted(Comparator.comparing(SessionImport::name))
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
        return new SessionSnapshot(revision, next, imports, pinnedModules, allocator,
                flowCertificate, moduleEnvironment);
    }

    public SessionSnapshot withImport(SessionImport value) {
        Objects.requireNonNull(value, "value");
        LinkedHashMap<String, SessionImport> next = new LinkedHashMap<>(imports);
        next.put(value.name(), value);
        return new SessionSnapshot(revision, bindings, next, pinnedModules, allocator,
                flowCertificate, moduleEnvironment);
    }

    public SessionSnapshot withPinnedModule(PinnedModule module) {
        Objects.requireNonNull(module, "module");
        LinkedHashMap<LogicalModuleId, PinnedModule> next = new LinkedHashMap<>(pinnedModules);
        PinnedModule previous = next.put(module.logicalModule(), module);
        if (previous != null && !previous.equals(module)) {
            throw new IllegalArgumentException(
                    "a logical module is already pinned to another source: " + module.logicalModule());
        }
        return new SessionSnapshot(revision, bindings, imports, next, allocator,
                flowCertificate, moduleEnvironment);
    }

    public SessionSnapshot withAllocator(IdentityAllocator nextAllocator) {
        return new SessionSnapshot(revision, bindings, imports, pinnedModules, nextAllocator,
                flowCertificate, moduleEnvironment);
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
        return nextRevision(nextBindings, nextImports, nextPinnedModules,
                nextAllocator, moduleEnvironment);
    }

    /** Advances the namespace while publishing an immutable retained module environment. */
    public SessionSnapshot nextRevision(
            Map<String, ExternalBinding> nextBindings,
            Map<String, SessionImport> nextImports,
            Map<LogicalModuleId, PinnedModule> nextPinnedModules,
            IdentityAllocator nextAllocator,
            SessionModuleEnvironment nextEnvironment) {
        return new SessionSnapshot(revision.next(), nextBindings, nextImports,
                nextPinnedModules, nextAllocator, flowCertificate,
                Objects.requireNonNull(nextEnvironment, "nextEnvironment"));
    }

    /** Replaces only the retained compiler module environment. */
    public SessionSnapshot withModuleEnvironment(SessionModuleEnvironment nextEnvironment) {
        return new SessionSnapshot(revision, bindings, imports, pinnedModules, allocator,
                flowCertificate, Objects.requireNonNull(nextEnvironment, "nextEnvironment"));
    }

    /** Returns a snapshot carrying the compiler-issued proof for its retained state. */
    public SessionSnapshot withFlowCertificate(SessionFlowCertificate certificate) {
        return new SessionSnapshot(revision, bindings, imports, pinnedModules, allocator,
                Optional.of(Objects.requireNonNull(certificate, "certificate")), moduleEnvironment);
    }

    /** Removes compiler proof while preserving namespace metadata and identities. */
    public SessionSnapshot withoutFlowCertificate() {
        return new SessionSnapshot(revision, bindings, imports, pinnedModules, allocator,
                Optional.empty(), moduleEnvironment);
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

    private static void validateEnvironment(
            SessionModuleEnvironment environment, IdentityAllocator allocator) {
        Objects.requireNonNull(environment, "environment");
        environment.typedGraph().ifPresent(graph -> {
            if (!allocator.dominates(graph.allocator())) {
                throw new IllegalArgumentException("snapshot allocator regressed retained semantic identities");
            }
        });
        for (var module : environment.producers()) {
            if (!allocator.dominates(module.producerGraph().allocator())) {
                throw new IllegalArgumentException("snapshot allocator regressed a producer graph");
            }
        }
        long generation = environment.producers().stream()
                .mapToLong(value -> value.generationId().ordinal()).max().orElse(-1L);
        if (generation >= allocator.nextGenerationOrdinal()) {
            throw new IllegalArgumentException(
                    "allocator must be advanced beyond every module generation identity");
        }
        long producer = environment.producers().stream()
                .mapToLong(value -> value.producerId().ordinal()).max().orElse(-1L);
        if (producer >= allocator.nextProducerOrdinal()) {
            throw new IllegalArgumentException(
                    "allocator must be advanced beyond every module producer identity");
        }
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
