package io.mindspice.lyra.runtime;

import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owner-confined typed storage authority for in-process submission linkage.
 * Stores exact accessors to initialized generated storage, never copied values.
 * Numeric identities/contracts or compiler flow proofs cannot manufacture a Binding capability.
 * Callable leaves retain their original closure authority in the same source-local
 * session epoch; an OPEN prepared shell alone does not certify initialized storage.
 */
public final class SessionStorageDomain implements AutoCloseable {
    private final OwnerThread owner = OwnerThread.capture();
    private final LyraOwnerController controller = new LyraOwnerController(owner);
    private final Map<Long, Binding> bindings = new HashMap<>();
    private SessionTypeLoader types = new SessionTypeLoader();
    private long revision;
    private long epoch;
    private boolean closed;

    public record Requirement(long id, long storageIdentity, String name, String type, boolean writable) {
        public Requirement {
            if (id < 0 || storageIdentity < -1 || writable && storageIdentity < 0) {
                throw new IllegalArgumentException("invalid declaration or mutable storage identity");
            }
            if (Objects.requireNonNull(name, "name").isBlank()) throw new IllegalArgumentException("empty binding name");
            LyraType value = LyraType.parse(Objects.requireNonNull(type, "type"));
            if (value.isMutable()) {
                throw new IllegalArgumentException("session linkage requires an exact value type without binding mutability");
            }
        }
    }

    static Class<?> storageClass(LyraType type, ClassLoader loader, String javaPackage) {
        if (type.baseType() instanceof ArrayType array) {
            return storageClass(array.elementType(), loader, javaPackage).arrayType();
        }
        if (type.baseType() instanceof TupleType tuple) {
            String name = javaPackage + ".$lyra$tuple$" + LyraRuntime.sha256(
                    "LYRA-JVM-GENERATED-TYPE", "tuple", tuple.canonicalSpelling()).substring(0, 16);
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException failure) {
                throw new LyraLinkException("session tuple contract has no generated class: " + name, List.of(), failure);
            }
        }
        if (type.baseType() instanceof FunctionType function) {
            String name = javaPackage + ".$lyra$fn$" + LyraRuntime.sha256(
                    "LYRA-JVM-GENERATED-TYPE", "function", function.canonicalSpelling()).substring(0, 16);
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException failure) {
                throw new LyraLinkException("session function contract has no generated interface: " + name, List.of(), failure);
            }
        }
        return scalarClass(type);
    }

    static Class<?> scalarClass(LyraType type) {
        if (!(type.baseType() instanceof PrimitiveType primitive)) throw new LyraLinkException("non-scalar storage type");
        return switch (primitive) {
            case I8, U8 -> type.isNilable() ? Byte.class : byte.class;
            case I16, U16 -> type.isNilable() ? Short.class : short.class;
            case I32, U32 -> type.isNilable() ? Integer.class : int.class;
            case I64, U64 -> type.isNilable() ? Long.class : long.class;
            case F32 -> type.isNilable() ? Float.class : float.class;
            case F64 -> type.isNilable() ? Double.class : double.class;
            case BOOL -> type.isNilable() ? Boolean.class : boolean.class;
            case CHAR -> type.isNilable() ? Character.class : char.class;
            case STRING -> String.class;
            case UNIT -> LyraUnit.class;
        };
    }

    /** Registers initialized storage from one compiler-issued accessor pair. */
    public Binding register(ModuleHandle module, Requirement requirement) {
        checkOpen();
        Objects.requireNonNull(requirement, "requirement");
        if (bindings.containsKey(requirement.id())) throw new LyraLinkException("storage identity already registered");
        if (!(LyraType.parse(requirement.type()).baseType() instanceof PrimitiveType)) {
            LyraRuntime.requireSessionDataGeneration(module, this);
        }
        MethodHandle reader = LyraRuntime.submissionStorageAccessor(module, requirement, false);
        try {
            // A prepared OPEN shell is not proof that this particular binding ran.
            reader.asType(java.lang.invoke.MethodType.methodType(void.class)).invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new LyraLinkException("storage initialization check failed", List.of(), failure);
        }
        MethodHandle writer = requirement.writable()
                ? LyraRuntime.submissionStorageAccessor(module, requirement, true) : null;
        Binding binding = new Binding(this, module, requirement, reader, writer);
        return binding;
    }

    /** Validates every exact capability before loading or executing new source. */
    public Linkage link(ArtifactSource artifact, long baseRevision,
                        List<Requirement> requirements, List<Binding> capabilities) {
        checkOpen();
        if (baseRevision != revision || requirements.size() != capabilities.size()) {
            throw new LyraLinkException("stale or incomplete submission linkage");
        }
        Map<Long, Binding> selected = new HashMap<>();
        for (int index = 0; index < requirements.size(); index++) {
            Requirement requirement = requirements.get(index);
            Binding binding = Objects.requireNonNull(capabilities.get(index), "binding");
            check(binding);
            if (binding.domain != this || !binding.requirement.name().equals(requirement.name())
                    || binding.requirement.id() != requirement.id()
                    || binding.requirement.storageIdentity() != requirement.storageIdentity()
                    || !binding.requirement.type().equals(requirement.type())
                    || requirement.writable() && binding.writer == null
                    || selected.put(requirement.id(), binding) != null) {
                throw new LyraLinkException("foreign or incompatible submission storage capability");
            }
        }
        return new Linkage(this, artifact.metadata(), baseRevision, selected,
                requirements.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Requirement::id, value -> value)));
    }

    public LyraOwnerController.EvaluationLease beginEvaluation() {
        checkOpen();
        return controller.beginEvaluation();
    }

    public void commit(long baseRevision, List<Binding> staged) {
        checkOpen();
        if (revision != baseRevision) throw new LyraLinkException("stale namespace publication");
        Map<Long, Binding> additions = new HashMap<>();
        for (Binding binding : staged) {
            if (binding.domain != this || binding.epoch != epoch || binding.module.isClosed()
                    || bindings.containsKey(binding.requirement.id())
                    || additions.put(binding.requirement.id(), binding) != null) {
                throw new LyraLinkException("invalid staged storage publication");
            }
        }
        long next = Math.incrementExact(revision);
        bindings.putAll(additions);
        revision = next;
    }

    /** Retires scratch authority without reusing declaration IDs or rewinding the revision. */
    public void reset() {
        checkOpen();
        epoch = Math.incrementExact(epoch);
        bindings.clear();
        types.retire();
        types = new SessionTypeLoader();
    }

    @Override
    public void close() {
        owner.check();
        if (closed) return;
        controller.close();
        bindings.clear();
        types.retire();
        closed = true;
    }

    private void checkOpen() {
        owner.check();
        if (closed) throw new LyraClosedException("session storage domain is closed");
    }

    private void check(Binding binding) {
        checkOpen();
        if (binding.domain != this || binding.epoch != epoch || bindings.get(binding.requirement.id()) != binding) {
            throw new LyraLinkException("foreign or retired session storage capability");
        }
        if (binding.module.isClosed()) throw new LyraClosedException("session storage generation is closed");
    }

    /** Opaque authority; does not expose live values or executable handles. */
    public static final class Binding {
        private final SessionStorageDomain domain;
        private final ModuleHandle module;
        private final Requirement requirement;
        private final long epoch;
        private final MethodHandle reader;
        private final MethodHandle writer;

        private Binding(SessionStorageDomain domain, ModuleHandle module, Requirement requirement,
                        MethodHandle reader, MethodHandle writer) {
            this.domain = domain;
            this.module = module;
            this.requirement = requirement;
            this.epoch = domain.epoch;
            this.reader = reader;
            this.writer = writer;
        }
    }

    /** One authenticated, revision-checked generation link table. */
    public static final class Linkage {
        private final SessionStorageDomain domain;
        private final ArtifactMetadata artifactMetadata;
        private final long baseRevision;
        private final long epoch;
        private final Map<Long, Binding> bindings;
        private final Map<Long, Requirement> requirements;
        private final boolean sourceLocal;

        private Linkage(SessionStorageDomain domain, ArtifactMetadata artifactMetadata, long baseRevision,
                        Map<Long, Binding> bindings, Map<Long, Requirement> requirements) {
            this.domain = domain;
            this.artifactMetadata = Objects.requireNonNull(artifactMetadata, "artifactMetadata");
            this.sourceLocal = artifactMetadata.modules().size() == 1;
            this.baseRevision = baseRevision;
            this.epoch = domain.epoch;
            this.bindings = Map.copyOf(bindings);
            this.requirements = Map.copyOf(requirements);
        }

        void validate(ArtifactMetadata artifact) {
            domain.checkOpen();
            if (!artifact.equals(artifactMetadata) || domain.revision != baseRevision || epoch != domain.epoch) {
                throw new LyraLinkException("submission linkage belongs to another artifact or revision");
            }
            if (artifact.modules().size() != 1 && requirements.values().stream()
                    .anyMatch(value -> !(LyraType.parse(value.type()).baseType() instanceof PrimitiveType))) {
                throw new LyraLinkException("imported graphs cannot access session aggregate storage before provenance linkage is supported");
            }
            bindings.values().forEach(domain::check);
        }

        /** Separate opt-in authority; artifact keys themselves remain distinct. */
        boolean authenticates(Linkage other) {
            checkOpen();
            if (other == null || domain != other.domain || !sourceLocal || !other.sourceLocal) return false;
            other.checkOpen();
            return epoch == other.epoch;
        }

        boolean belongsTo(SessionStorageDomain candidate) {
            return domain == candidate && epoch == domain.epoch;
        }

        boolean isActive() {
            domain.owner.check();
            return !domain.closed && epoch == domain.epoch;
        }

        void checkOpen() {
            domain.checkOpen();
            if (epoch != domain.epoch) throw new LyraLinkException("submission linkage was retired by reset");
        }

        ClassLoader typeLoader(Map<String, byte[]> classes) {
            checkOpen();
            return domain.types.stage(classes);
        }

        void publishTypes(ClassLoader staged) {
            checkOpen();
            if (!(staged instanceof SessionTypeLoader next)
                    || next != domain.types && next.getParent() != domain.types) {
                throw new LyraLinkException("stale structural type admission");
            }
            domain.types = next;
        }

        void safePoint() {
            checkOpen();
            domain.controller.safePoint();
        }

        MethodHandle accessor(long id, long storageIdentity, String type, boolean write) {
            checkOpen();
            Binding binding = bindings.get(id);
            Requirement required = requirements.get(id);
            if (binding == null || required.storageIdentity() != storageIdentity
                    || !required.type().equals(type) || write && !required.writable()) {
                throw new LyraLinkException("submission access exceeds its typed storage linkage");
            }
            domain.check(binding);
            return write ? binding.writer : binding.reader;
        }
    }
}
