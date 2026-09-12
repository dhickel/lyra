package io.mindspice.lyra.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Owner-confined typed storage authority for in-process submission linkage.
 *
 * <p>The mutable registration map is only a workspace view.  A constructed
 * {@link Linkage} contains immutable link entries that retain the actual
 * producer and its exact accessors, so removing a workspace name cannot
 * retarget or silently invalidate an already constructed link table.  A
 * producer's lifecycle, the session epoch, and the root lifetime still gate
 * every access.</p>
 *
 * <p>Numeric identities/contracts or compiler flow proofs cannot manufacture
 * a binding capability.  Callable leaves retain their original closure
 * authority in the same trusted session domain; an OPEN prepared shell alone
 * does not certify initialized storage.</p>
 */
public final class SessionStorageDomain implements AutoCloseable {
    private final OwnerThread owner;
    private final LyraOwnerController controller;
    private final RootLifetime rootLifetime;
    /** Current workspace registrations; link tables never consult this map. */
    private final Map<Long, Binding> bindings = new HashMap<>();
    /** Kept as a field for the structural-domain admission boundary and diagnostics. */
    private SessionTypeLoader types;
    private long revision;
    private long epoch;
    private boolean closed;
    private final Set<Retention> retentions =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Ownership class used by the explicit trusted retention primitives. */
    public enum RetentionKind {
        /** The workspace owns the producer and retires it on reset/close. */
        OWNED,
        /** The workspace borrows a producer owned by an external root. */
        BORROWED,
        /** A root-backed scratch producer/resource retires only when the root closes. */
        ROOT_OWNED,
        /** A construction attempt is retained, but is not usable until initialized. */
        ATTEMPTED
    }

    /**
     * Root-owned lifetime for structural classes, producer links, and immutable
     * compiler/source holders.  It is owner-thread confined and deliberately
     * does not scan the heap or infer reachability.
     */
    public static final class RootLifetime implements AutoCloseable {
        private final OwnerThread owner;
        private SessionTypeLoader typeDomain;
        private final Set<Retention> retentions =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Object> anchoredHolders =
                Collections.newSetFromMap(new IdentityHashMap<>());
        /** One optional-module source catalog shared by every service on this root. */
        private Object sharedSourceHolder;
        private SessionStorageDomain activeDomain;
        private boolean closed;

        public RootLifetime() {
            this(OwnerThread.capture());
        }

        public RootLifetime(Thread ownerThread) {
            this(OwnerThread.of(ownerThread));
        }

        public RootLifetime(OwnerThread owner) {
            this(owner, null);
        }

        /** Runtime-owned extension domain over a loaded artifact's structural base. */
        RootLifetime(OwnerThread owner, SessionTypeLoader structuralParent) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.typeDomain = structuralParent == null
                    ? new SessionTypeLoader() : new SessionTypeLoader(structuralParent);
            this.anchoredHolders.add(typeDomain);
        }

        public OwnerThread owner() {
            return owner;
        }

        public Thread ownerThread() {
            return owner.thread();
        }

        public boolean isOwnerThread() {
            return owner.isCurrent();
        }

        public boolean isClosed() {
            owner.check();
            return closed;
        }

        /** Returns the current number of explicitly anchored holder objects. */
        int anchoredHolderCount() {
            owner.check();
            return anchoredHolders.size();
        }

        /** Anchors a structural type-domain holder until this root closes. */
        public <T> T anchorTypeDomain(T holder) {
            return anchor(holder);
        }

        /** Anchors immutable source data required by a live producer. */
        public <T> T anchorSource(T holder) {
            return anchor(holder);
        }

        /**
         * Returns the one source catalog shared by successive attachment
         * services on this root, creating and anchoring it on first use.
         * Runtime deliberately treats the holder as opaque so it acquires no
         * compiler or REPL dependency.
         */
        public <T> T sharedSourceHolder(Class<T> holderType,
                                        Supplier<? extends T> factory) {
            owner.check();
            requireOpen();
            Objects.requireNonNull(holderType, "holderType");
            Objects.requireNonNull(factory, "factory");
            if (sharedSourceHolder == null) {
                sharedSourceHolder = Objects.requireNonNull(factory.get(),
                        "source holder factory returned null");
                anchoredHolders.add(sharedSourceHolder);
            }
            if (!holderType.isInstance(sharedSourceHolder)) {
                throw new LyraLifecycleException(
                        "root lifetime already owns an incompatible source holder");
            }
            return holderType.cast(sharedSourceHolder);
        }

        /** Anchors immutable semantic/callable summary data required by a live producer. */
        public <T> T anchorSummary(T holder) {
            return anchor(holder);
        }

        /** Retains an arbitrary immutable holder without assigning execution authority. */
        public Retention retain(Object holder, RetentionKind kind) {
            owner.check();
            requireOpen();
            return addRetention(new Retention(this, null, holder, kind, false));
        }

        /** Retains a real producer instance under this root's lifetime. */
        public Retention retainProducer(ModuleHandle producer, RetentionKind kind) {
            owner.check();
            requireOpen();
            Objects.requireNonNull(producer, "producer");
            requireProducerOpen(producer);
            return addRetention(new Retention(this, null, producer, kind, true));
        }

        @Override
        public void close() {
            owner.check();
            if (closed) return;
            if (activeDomain != null) {
                activeDomain.closeFromRoot();
            }
            for (Retention retention : List.copyOf(retentions)) {
                retention.retireInternal(true);
            }
            typeDomain.retireSelf();
            anchoredHolders.clear();
            sharedSourceHolder = null;
            retentions.clear();
            closed = true;
            activeDomain = null;
        }

        private <T> T anchor(T holder) {
            owner.check();
            requireOpen();
            anchoredHolders.add(Objects.requireNonNull(holder, "holder"));
            return holder;
        }

        private Retention addRetention(Retention retention) {
            retentions.add(retention);
            return retention;
        }

        private void removeRetention(Retention retention) {
            retentions.remove(retention);
        }

        private void attach(SessionStorageDomain domain) {
            owner.check();
            requireOpen();
            if (activeDomain != null && !activeDomain.closed) {
                throw new LyraLifecycleException("root lifetime already has an active storage workspace");
            }
            activeDomain = domain;
        }

        private void detach(SessionStorageDomain domain) {
            owner.check();
            if (activeDomain == domain) activeDomain = null;
        }

        private void requireOpen() {
            if (closed) throw new LyraClosedException("root lifetime is closed");
        }

        private void checkOpen() {
            owner.check();
            requireOpen();
        }

        SessionTypeLoader typeDomain() {
            checkOpen();
            return typeDomain;
        }

        void publishTypeDomain(SessionTypeLoader next) {
            checkOpen();
            typeDomain = Objects.requireNonNull(next, "next");
            anchoredHolders.add(next);
        }

        private Retention retainFor(SessionStorageDomain domain, ModuleHandle producer,
                                    RetentionKind kind) {
            owner.check();
            requireOpen();
            requireProducerOpen(producer);
            Retention retention = new Retention(this, domain, producer, kind, true);
            domain.retentions.add(retention);
            return addRetention(retention);
        }

        private void retireOwned() {
            // An attempted root generation is retained conservatively until
            // root close: a value may have escaped before the attempt failed
            // or was cancelled.  Only scratch-owned resources are retired by
            // workspace reset/detach.
            for (Retention retention : List.copyOf(retentions)) {
                if (retention.domain != null && retention.kind == RetentionKind.OWNED) {
                    retention.retireInternal(true);
                }
            }
        }
    }

    /**
     * Explicit strong retention record.  Retaining a producer never changes
     * its lifecycle or initializes storage; it only keeps the exact object and
     * its lifetime record together until the owner retires the record.  Owned
     * producer records close their producer on retirement; borrowed and
     * attempted records never close the producer they retain.
     */
    public static final class Retention implements AutoCloseable {
        private final RootLifetime root;
        private final SessionStorageDomain domain;
        private final Object holder;
        private final ModuleHandle producer;
        private final RetentionKind kind;
        private final boolean producerRetention;
        private boolean initialized;
        private boolean active = true;

        private Retention(RootLifetime root, SessionStorageDomain domain,
                          Object holder, RetentionKind kind, boolean producerRetention) {
            this.root = root;
            this.domain = domain;
            this.holder = Objects.requireNonNull(holder, "holder");
            this.producer = producerRetention ? (ModuleHandle) holder : null;
            this.kind = Objects.requireNonNull(kind, "kind");
            this.producerRetention = producerRetention;
        }

        public RetentionKind kind() {
            return kind;
        }

        public boolean isProducerRetention() {
            return producerRetention;
        }

        /** Owner-confined state of the explicit retention record. */
        public boolean isActive() {
            owner().check();
            return active && (root == null || !root.closed)
                    && (domain == null || !domain.closed || root != null);
        }

        /**
         * Checks whether this record can authorize a producer read.  An
         * attempted record is intentionally false until its registration has
         * observed a real initialized accessor.
         */
        public boolean isUsable() {
            owner().check();
            try {
                requireUsable();
                return true;
            } catch (LyraRuntimeException failure) {
                return false;
            }
        }

        @Override
        public void close() {
            owner().check();
            retireInternal(true);
        }

        private OwnerThread owner() {
            return root != null ? root.owner : domain.owner;
        }

        private void markInitialized() {
            owner().check();
            if (!active) throw new LyraLinkException("producer retention is retired");
            initialized = true;
        }

        private void requireUsable() {
            owner().check();
            if (!active || root != null && root.closed || domain != null && domain.closed && root == null) {
                throw new LyraClosedException("producer retention is closed");
            }
            if (kind == RetentionKind.ATTEMPTED && !initialized) {
                throw new LyraInitializationException("producer retention has not completed initialization");
            }
            if (producerRetention && producer.isClosed()) {
                throw new LyraClosedException("producer module is closed");
            }
        }

        private void retireInternal(boolean closeOwnedProducer) {
            if (!active) return;
            active = false;
            initialized = false;
            if (root != null) root.removeRetention(this);
            if (domain != null) domain.retentions.remove(this);
            if (closeOwnedProducer && producerRetention
                    && (kind == RetentionKind.OWNED || kind == RetentionKind.ROOT_OWNED)) {
                producer.close();
            } else if (closeOwnedProducer && kind == RetentionKind.ROOT_OWNED
                    && holder instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    throw new LyraLifecycleException(
                            "root-owned attachment resource close failed", List.of(), failure);
                }
            }
        }

        private ModuleHandle producer() {
            return producer;
        }
    }

    public record Requirement(long id, long storageIdentity, String name, String type, boolean writable) {
        public Requirement {
            if (id < 0 || storageIdentity < -1 || writable && storageIdentity < 0) {
                throw new IllegalArgumentException("invalid declaration or mutable storage identity");
            }
            if (Objects.requireNonNull(name, "name").isBlank()) {
                throw new IllegalArgumentException("empty binding name");
            }
            LyraType value = LyraType.parse(Objects.requireNonNull(type, "type"));
            if (value.isMutable()) {
                throw new IllegalArgumentException(
                        "session linkage requires an exact value type without binding mutability");
            }
        }

        public LyraType logicalType() {
            return LyraType.parse(type);
        }
    }

    static Class<?> storageClass(LyraType type, ClassLoader loader, String javaPackage) {
        if (type.baseType() instanceof NominalType nominal) {
            String name = javaPackage + ".$lyra$nominal$" + nominal.id().stableHash();
            try {
                Class<?> representation = Class.forName(name, false, loader);
                if (representation.getSuperclass() != LyraNominalObject.class
                        || !java.lang.reflect.Modifier.isFinal(representation.getModifiers())) {
                    throw new LyraLinkException("nominal contract has no final exact object representation: " + name);
                }
                return representation;
            } catch (ClassNotFoundException failure) {
                throw new LyraLinkException("nominal contract has no generated class: " + name, List.of(), failure);
            }
        }
        if (type.baseType() instanceof RangeType) return LyraRange.class;
        if (type.baseType() instanceof ArrayType array) {
            return storageClass(array.elementType(), loader, javaPackage).arrayType();
        }
        if (type.baseType() instanceof TupleType tuple) {
            String name = javaPackage + ".$lyra$tuple$" + LyraRuntime.sha256(
                    "LYRA-JVM-GENERATED-TYPE", "tuple", tuple.canonicalSpelling()).substring(0, 16);
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException failure) {
                throw new LyraLinkException("session tuple contract has no generated class: " + name,
                        List.of(), failure);
            }
        }
        if (type.baseType() instanceof FunctionType function) {
            String name = javaPackage + ".$lyra$fn$" + LyraRuntime.sha256(
                    "LYRA-JVM-GENERATED-TYPE", "function", function.canonicalSpelling()).substring(0, 16);
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException failure) {
                throw new LyraLinkException("session function contract has no generated interface: " + name,
                        List.of(), failure);
            }
        }
        return scalarClass(type);
    }

    static Class<?> scalarClass(LyraType type) {
        if (!(type.baseType() instanceof PrimitiveType primitive)) {
            throw new LyraLinkException("non-scalar storage type");
        }
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

    /** Creates a standalone owner-confined workspace. */
    public SessionStorageDomain() {
        this(null);
    }

    /** Creates a workspace whose structural and borrowed lifetimes belong to a root. */
    public SessionStorageDomain(RootLifetime rootLifetime) {
        this.rootLifetime = rootLifetime;
        this.owner = rootLifetime == null ? OwnerThread.capture() : rootLifetime.owner;
        this.controller = new LyraOwnerController(owner);
        if (rootLifetime != null) rootLifetime.attach(this);
        this.types = rootLifetime == null ? new SessionTypeLoader() : rootLifetime.typeDomain;
    }

    /**
     * Creates a root-backed workspace sharing one explicitly supplied owner
     * controller.  A live application attachment installs its registered
     * root controller here so that synchronous submissions, owner-dispatched
     * evaluations and generated application safe points observe exactly one
     * active lease and never begin a second evaluation.
     */
    public SessionStorageDomain(RootLifetime rootLifetime, LyraOwnerController controller) {
        this.rootLifetime = Objects.requireNonNull(rootLifetime, "rootLifetime");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.owner = rootLifetime.owner;
        if (!controller.owner().equals(owner)) {
            throw new LyraThreadException("workspace controller belongs to another owner thread");
        }
        rootLifetime.attach(this);
        this.types = rootLifetime.typeDomain;
    }

    /** Returns the optional externally owned root lifetime. */
    public Optional<RootLifetime> rootLifetime() {
        owner.check();
        return Optional.ofNullable(rootLifetime);
    }

    /** Retains a real producer under an explicit optional-session lifetime class. */
    public Retention retainProducer(ModuleHandle producer, RetentionKind kind) {
        checkOpen();
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(kind, "kind");
        requireProducerOpen(producer);
        return rootLifetime == null
                ? addRetention(new Retention(null, this, producer, kind, true))
                : rootLifetime.retainFor(this, producer, kind);
    }

    /**
     * Registers initialized storage from one compiler-issued accessor pair.
     * Standalone workspaces own their default producer; a root-backed
     * workspace borrows its default producer from that externally owned root.
     */
    public Binding register(ModuleHandle module, Requirement requirement) {
        return register(module, module.moduleId(), requirement,
                rootLifetime == null ? RetentionKind.OWNED : RetentionKind.ROOT_OWNED);
    }

    /** Registers a binding owned by one module view in a prepared graph. */
    public Binding register(ModuleHandle graph, ModuleId moduleId, Requirement requirement) {
        return register(graph, moduleId, requirement,
                rootLifetime == null ? RetentionKind.OWNED : RetentionKind.ROOT_OWNED);
    }

    /** Registers an exact storage link with an explicit producer lifetime class. */
    public Binding register(ModuleHandle module, Requirement requirement, RetentionKind kind) {
        return register(module, module.moduleId(), requirement, kind);
    }

    /** Registers an exact storage link through a selected prepared-graph module view. */
    public Binding register(ModuleHandle module, ModuleId moduleId,
                            Requirement requirement, RetentionKind kind) {
        checkOpen();
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(kind, "kind");
        if (bindings.containsKey(requirement.id())) {
            throw new LyraLinkException("storage identity already registered");
        }
        if (!(requirement.logicalType().baseType() instanceof PrimitiveType)) {
            LyraRuntime.requireSessionDataGeneration(module, this);
        }

        Retention retention = retainProducer(module, kind);
        try {
            MethodHandle reader = LyraRuntime.submissionStorageAccessor(module, moduleId, requirement, false);
            MethodType readerType = LyraRuntime.sessionStorageReaderType(module, requirement.logicalType());
            if (!reader.type().equals(readerType)) {
                throw new LyraLinkException("generated session reader MethodType mismatch");
            }
            Optional<MethodType> functionType = LyraRuntime.sessionFunctionMethodType(
                    module, requirement.logicalType());
            verifyInitialized(reader);
            LyraRuntime.requireSubmissionPublication(module);
            MethodHandle writer = requirement.writable()
                    ? LyraRuntime.submissionStorageAccessor(module, moduleId, requirement, true) : null;
            MethodType writerType = writer == null ? null : writer.type();
            if (writer != null && !writerType.equals(
                    LyraRuntime.sessionStorageWriterType(module, requirement.logicalType()))) {
                throw new LyraLinkException("generated session writer MethodType mismatch");
            }
            retention.markInitialized();
            Binding binding = new Binding(this, module, requirement, requirement.logicalType(),
                    reader, writer, readerType, writerType, functionType, retention);
            return binding;
        } catch (RuntimeException | Error failure) {
            // A failed registration is only a rejected capability attempt;
            // it must not close a producer that the caller may still finish
            // or inspect as an uninitialized/failed generation.
            retention.retireInternal(false);
            throw failure;
        }
    }

    /** Retains an attempted producer without claiming initialized storage. */
    public Retention retainAttempted(ModuleHandle producer) {
        return retainProducer(producer, RetentionKind.ATTEMPTED);
    }

    /**
     * Registers an exact external storage link against an explicitly
     * registered attachable root.  The supplied handles must come from the
     * root's real typed accessors; this path never re-executes initializers,
     * never clones values, and only ever admits links into a root-backed
     * workspace whose structural domain is anchored to the root lifetime.
     */
    public Binding registerExternal(ModuleHandle producer, Requirement requirement,
                                    MethodHandle reader, MethodHandle writer) {
        checkOpen();
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(reader, "reader");
        if (rootLifetime == null) {
            throw new LyraLinkException(
                    "external storage links require a root-backed session workspace");
        }
        if (bindings.containsKey(requirement.id())) {
            throw new LyraLinkException("storage identity already registered");
        }
        if (requirement.writable() && writer == null) {
            throw new LyraLinkException("mutable external link requires a writer");
        }
        if (!requirement.writable() && writer != null) {
            throw new LyraLinkException("immutable external link cannot carry a writer");
        }
        Retention retention = retainProducer(producer, RetentionKind.BORROWED);
        try {
            Class<?> storage = storageClass(requirement.logicalType(), rootLifetime.typeDomain(),
                    externalJavaPackage(producer));
            MethodType readerType = MethodType.methodType(storage);
            if (!reader.type().equals(readerType)) {
                throw new LyraLinkException("external storage reader MethodType mismatch");
            }
            java.util.Optional<MethodType> functionType = externalFunctionType(
                    requirement.logicalType(), rootLifetime.typeDomain(),
                    externalJavaPackage(producer));
            verifyInitialized(reader);
            MethodType writerType = writer == null ? null
                    : MethodType.methodType(void.class, storage);
            if (writer != null && !writer.type().equals(writerType)) {
                throw new LyraLinkException("external storage writer MethodType mismatch");
            }
            retention.markInitialized();
            Binding binding = new Binding(this, producer, requirement, requirement.logicalType(),
                    reader, writer, readerType, writerType, functionType, retention);
            // An external root link is a live admission, not a staged
            // namespace publication: the workspace may link it immediately
            // without advancing the submission revision.
            bindings.put(requirement.id(), binding);
            return binding;
        } catch (RuntimeException | Error failure) {
            retention.retireInternal(false);
            throw failure;
        }
    }

    private static String externalJavaPackage(ModuleHandle producer) {
        return producer.metadata().javaPackage();
    }

    private static java.util.Optional<MethodType> externalFunctionType(
            LyraType type, SessionTypeLoader domain, String javaPackage) {
        if (!(type.baseType() instanceof FunctionType)) {
            return java.util.Optional.empty();
        }
        FunctionType function = (FunctionType) type.baseType();
        Class<?>[] parameters = function.parameterTypes().stream()
                .map(value -> storageClass(value, domain, javaPackage))
                .toArray(Class<?>[]::new);
        Class<?> returnType = function.returnType().baseType() instanceof PrimitiveType primitive
                && primitive == PrimitiveType.UNIT && !function.returnType().isNilable()
                ? void.class : storageClass(function.returnType(), domain, javaPackage);
        MethodType expected = MethodType.methodType(returnType, parameters);
        Class<?> functionClass = storageClass(type, domain, javaPackage);
        try {
            Method invoke = functionClass.getMethod("invoke", parameters);
            MethodType actual = MethodType.methodType(invoke.getReturnType(), invoke.getParameterTypes());
            if (!actual.equals(expected) || !Modifier.isPublic(invoke.getModifiers())
                    || Modifier.isStatic(invoke.getModifiers())) {
                throw new LyraLinkException("external function interface MethodType mismatch");
            }
            return java.util.Optional.of(actual);
        } catch (NoSuchMethodException failure) {
            throw new LyraLinkException("external function interface has no exact invoke method",
                    List.of(), failure);
        }
    }

    /**
     * Removes a current workspace registration without retiring link tables
     * that already captured it.  It is intentionally owner-confined.
     */
    public void unregister(long declarationId) {
        checkOpen();
        Binding binding = bindings.remove(declarationId);
        if (binding == null) throw new LyraLinkException("storage identity is not registered");
    }

    /** Validates every exact capability before loading or executing new source. */
    public Linkage link(ArtifactSource artifact, long baseRevision,
                        List<Requirement> requirements, List<Binding> capabilities) {
        checkOpen();
        Objects.requireNonNull(artifact, "artifact");
        List<Requirement> requested = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        List<Binding> supplied = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (baseRevision != revision || requested.size() != supplied.size()) {
            throw new LyraLinkException("stale or incomplete submission linkage");
        }
        Map<Long, LinkEntry> selected = new HashMap<>();
        Map<Long, Requirement> selectedRequirements = new HashMap<>();
        for (int index = 0; index < requested.size(); index++) {
            Requirement requirement = requested.get(index);
            Binding binding = Objects.requireNonNull(supplied.get(index), "binding");
            check(binding);
            if (!binding.matches(requirement)
                    || selected.put(requirement.id(), binding.entry()) != null
                    || selectedRequirements.put(requirement.id(), requirement) != null) {
                throw new LyraLinkException("foreign or incompatible submission storage capability");
            }
        }
        return new Linkage(this, artifact.metadata(), baseRevision, selected, selectedRequirements);
    }

    public LyraOwnerController.EvaluationLease beginEvaluation() {
        checkOpen();
        return controller.beginEvaluation();
    }

    /**
     * Reuses one already-admitted evaluation lease for an owner-dispatched
     * evaluation.  The supplied lease must be active on this workspace's
     * controller; no second lease is ever begun.  Ownership of the lease
     * stays with the admitting controller poll.
     */
    public LyraOwnerController.EvaluationLease beginEvaluation(
            LyraOwnerController.EvaluationLease admitted) {
        checkOpen();
        Objects.requireNonNull(admitted, "admitted");
        if (!controller.isActiveEvaluation(admitted)) {
            throw new LyraLifecycleException(
                    "admitted evaluation lease is not active on this workspace controller");
        }
        return admitted;
    }

    public void commit(long baseRevision, List<Binding> staged) {
        checkOpen();
        if (revision != baseRevision) throw new LyraLinkException("stale namespace publication");
        List<Binding> requested = List.copyOf(Objects.requireNonNull(staged, "staged"));
        Map<Long, Binding> additions = new HashMap<>();
        for (Binding binding : requested) {
            Objects.requireNonNull(binding, "binding");
            if (binding.domain != this || binding.epoch != epoch || !binding.retention.isUsable()
                    || binding.producer.isClosed() || bindings.containsKey(binding.requirement.id())
                    || additions.put(binding.requirement.id(), binding) != null) {
                throw new LyraLinkException("invalid staged storage publication");
            }
            LyraRuntime.requireSubmissionPublication(binding.producer);
        }
        long next = Math.incrementExact(revision);
        bindings.putAll(additions);
        revision = next;
    }

    /** Retires standalone workspace authority without rewinding the revision. */
    public void reset() {
        checkOpen();
        epoch = Math.incrementExact(epoch);
        rootLifetimeRetireOwned();
        bindings.clear();
        if (rootLifetime == null) {
            types.retire();
            types = new SessionTypeLoader();
        } else {
            types = rootLifetime.typeDomain();
        }
    }

    @Override
    public void close() {
        owner.check();
        if (closed) return;
        closeWorkspace(false);
    }

    private void closeFromRoot() {
        owner.check();
        if (closed) return;
        closeWorkspace(true);
    }

    private void closeWorkspace(boolean fromRoot) {
        controller.close();
        if (rootLifetime == null) {
            for (Retention retention : List.copyOf(retentions)) {
                retention.retireInternal(true);
            }
        } else {
            rootLifetime.retireOwned();
        }
        bindings.clear();
        if (rootLifetime == null) {
            epoch = Math.incrementExact(epoch);
            types.retire();
        }
        closed = true;
        if (rootLifetime != null && !fromRoot) rootLifetime.detach(this);
    }

    private void rootLifetimeRetireOwned() {
        if (rootLifetime != null) {
            rootLifetime.retireOwned();
            return;
        }
        for (Retention retention : List.copyOf(retentions)) {
            if (retention.kind == RetentionKind.OWNED
                    || retention.kind == RetentionKind.ATTEMPTED) {
                retention.retireInternal(true);
            }
        }
    }

    private Retention addRetention(Retention retention) {
        retentions.add(retention);
        return retention;
    }

    private static void requireProducerOpen(ModuleHandle producer) {
        if (producer.isClosed()) {
            throw new LyraClosedException("producer module is closed");
        }
    }

    private void checkOpen() {
        owner.check();
        if (closed) throw new LyraClosedException("session storage domain is closed");
        if (rootLifetime != null) rootLifetime.requireOpen();
    }

    private void check(Binding binding) {
        checkOpen();
        if (binding.domain != this || binding.epoch != epoch
                || bindings.get(binding.requirement.id()) != binding) {
            throw new LyraLinkException("foreign or retired session storage capability");
        }
        binding.retention.requireUsable();
        if (binding.producer.isClosed()) {
            throw new LyraClosedException("session storage generation is closed");
        }
    }

    private static void verifyInitialized(MethodHandle reader) {
        try {
            reader.asType(MethodType.methodType(void.class)).invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new LyraLinkException("storage initialization check failed", List.of(), failure);
        }
    }

    /** Opaque workspace authority; it does not expose live values. */
    public static final class Binding {
        private final SessionStorageDomain domain;
        private final ModuleHandle producer;
        private final Requirement requirement;
        private final LyraType logicalType;
        private final long epoch;
        private final MethodHandle reader;
        private final MethodHandle writer;
        private final MethodType readerType;
        private final MethodType writerType;
        private final Optional<MethodType> functionType;
        private final Retention retention;

        private Binding(SessionStorageDomain domain, ModuleHandle producer, Requirement requirement,
                        LyraType logicalType, MethodHandle reader, MethodHandle writer,
                        MethodType readerType, MethodType writerType,
                        Optional<MethodType> functionType, Retention retention) {
            this.domain = domain;
            this.producer = producer;
            this.requirement = requirement;
            this.logicalType = logicalType;
            this.epoch = domain.epoch;
            this.reader = reader;
            this.writer = writer;
            this.readerType = readerType;
            this.writerType = writerType;
            this.functionType = functionType;
            this.retention = retention;
        }

        private boolean matches(Requirement other) {
            return requirement.id() == other.id()
                    && requirement.storageIdentity() == other.storageIdentity()
                    && requirement.name().equals(other.name())
                    && logicalType.equals(other.logicalType())
                    // A read-only consumer may use a producer's mutable
                    // accessor pair; the reverse is never admitted.
                    && (!other.writable() || requirement.writable())
                    && (other.writable() == (writer != null) || !other.writable());
        }

        private LinkEntry entry() {
            return new LinkEntry(producer, requirement.id(), requirement.storageIdentity(),
                    requirement.name(), logicalType, requirement.writable(), reader, writer,
                    readerType, writerType, functionType, retention);
        }

        ModuleHandle producer() {
            return producer;
        }

        LyraType logicalType() {
            return logicalType;
        }

        MethodType readerType() {
            return readerType;
        }

        Optional<MethodType> writerType() {
            return Optional.ofNullable(writerType);
        }

        Optional<MethodType> functionType() {
            return functionType;
        }

        Retention retention() {
            return retention;
        }
    }

    /** Immutable link entry captured from one real producer instance. */
    static final class LinkEntry {
        private final ModuleHandle producer;
        private final long declarationIdentity;
        private final long storageIdentity;
        private final String name;
        private final LyraType logicalType;
        private final boolean writable;
        private final MethodHandle reader;
        private final MethodHandle writer;
        private final MethodType readerType;
        private final MethodType writerType;
        private final Optional<MethodType> functionType;
        private final Retention retention;

        private LinkEntry(ModuleHandle producer, long declarationIdentity, long storageIdentity,
                          String name, LyraType logicalType, boolean writable,
                          MethodHandle reader, MethodHandle writer, MethodType readerType,
                          MethodType writerType, Optional<MethodType> functionType,
                          Retention retention) {
            this.producer = Objects.requireNonNull(producer, "producer");
            this.declarationIdentity = declarationIdentity;
            this.storageIdentity = storageIdentity;
            this.name = Objects.requireNonNull(name, "name");
            this.logicalType = Objects.requireNonNull(logicalType, "logicalType");
            this.writable = writable;
            this.reader = Objects.requireNonNull(reader, "reader");
            this.writer = writer;
            this.readerType = Objects.requireNonNull(readerType, "readerType");
            this.writerType = writerType;
            this.functionType = Objects.requireNonNull(functionType, "functionType");
            this.retention = Objects.requireNonNull(retention, "retention");
        }

        private void requireLive() {
            retention.requireUsable();
            if (producer.isClosed()) throw new LyraClosedException("link producer is closed");
            if (!reader.type().equals(readerType)
                    || writable && (writer == null || !writer.type().equals(writerType))) {
                throw new LyraLinkException("linked accessor MethodType changed");
            }
            verifyInitialized(reader);
        }

        ModuleHandle producer() {
            return producer;
        }

        long declarationIdentity() {
            return declarationIdentity;
        }

        long storageIdentity() {
            return storageIdentity;
        }

        String name() {
            return name;
        }

        LyraType logicalType() {
            return logicalType;
        }

        MethodType readerType() {
            return readerType;
        }

        Optional<MethodType> writerType() {
            return Optional.ofNullable(writerType);
        }

        Optional<MethodType> functionType() {
            return functionType;
        }

        Retention retention() {
            return retention;
        }

        MethodHandle accessor(boolean write) {
            requireLive();
            if (write) {
                if (!writable || writer == null) {
                    throw new LyraLinkException("submission access exceeds its typed storage linkage");
                }
                if (!writer.type().equals(writerType)) {
                    throw new LyraLinkException("linked writer MethodType changed");
                }
                return writer;
            }
            if (!reader.type().equals(readerType)) {
                throw new LyraLinkException("linked reader MethodType changed");
            }
            return reader;
        }
    }

    /** One immutable, revision-checked generation link table. */
    public static final class Linkage {
        private final SessionStorageDomain domain;
        private final ArtifactMetadata artifactMetadata;
        private final long baseRevision;
        private final long epoch;
        private final Map<Long, LinkEntry> entries;
        private final Map<Long, Requirement> requirements;
        private final boolean sourceLocal;
        private final RootLifetime rootLifetime;

        private Linkage(SessionStorageDomain domain, ArtifactMetadata artifactMetadata, long baseRevision,
                        Map<Long, LinkEntry> entries, Map<Long, Requirement> requirements) {
            this.domain = domain;
            this.artifactMetadata = Objects.requireNonNull(artifactMetadata, "artifactMetadata");
            long userModuleCount = artifactMetadata.modules().stream()
                    .map(ModuleMetadata::id)
                    .filter(id -> !id.isUri()
                            || !id.value().equals("lyra:intrinsic/std/io"))
                    .count();
            // A generated root plus a real source dependency is still one
            // trusted source-local session graph.  The compiler-owned std/io
            // namespace alone is not a producer graph for callable exchange.
            this.sourceLocal = artifactMetadata.modules().size() == 1 || userModuleCount > 1;
            this.baseRevision = baseRevision;
            this.epoch = domain.epoch;
            this.entries = Map.copyOf(entries);
            this.requirements = Map.copyOf(requirements);
            this.rootLifetime = domain.rootLifetime;
        }

        void validate(ArtifactMetadata artifact) {
            checkOpen();
            if (!artifact.equals(artifactMetadata)
                    || rootLifetime == null && domain.revision != baseRevision
                    || rootLifetime == null && epoch != domain.epoch) {
                throw new LyraLinkException("submission linkage belongs to another artifact or revision");
            }
            entries.values().forEach(LinkEntry::requireLive);
        }

        /** Separate opt-in authority; ordinary artifact keys remain distinct. */
        boolean authenticates(Linkage other) {
            checkOpen();
            if (other == null || !sourceLocal || !other.sourceLocal) return false;
            if (rootLifetime == null
                    ? domain != other.domain
                    : rootLifetime != other.rootLifetime) return false;
            other.checkOpen();
            return rootLifetime != null || epoch == other.epoch;
        }

        boolean belongsTo(SessionStorageDomain candidate) {
            Objects.requireNonNull(candidate, "candidate");
            candidate.owner.check();
            return rootLifetime == null
                    ? domain == candidate && epoch == domain.epoch
                    : rootLifetime == candidate.rootLifetime && !rootLifetime.closed;
        }

        boolean isActive() {
            domain.owner.check();
            if (rootLifetime != null) return !rootLifetime.closed;
            return !domain.closed && epoch == domain.epoch;
        }

        boolean isRetiredByReset() {
            domain.owner.check();
            return rootLifetime == null && !domain.closed && epoch != domain.epoch;
        }

        void checkOpen() {
            if (rootLifetime != null) {
                rootLifetime.checkOpen();
                return;
            }
            domain.checkOpen();
            if (epoch != domain.epoch) {
                throw new LyraLinkException("submission linkage was retired by reset");
            }
        }

        ClassLoader typeLoader(Map<String, byte[]> classes) {
            checkOpen();
            SessionTypeLoader current = rootLifetime == null ? domain.types : rootLifetime.typeDomain();
            return current.stage(classes);
        }

        void publishTypes(ClassLoader staged) {
            checkOpen();
            SessionTypeLoader current = rootLifetime == null ? domain.types : rootLifetime.typeDomain();
            if (!(staged instanceof SessionTypeLoader next)
                    || next != current && next.getParent() != current) {
                throw new LyraLinkException("stale structural type admission");
            }
            if (rootLifetime != null) rootLifetime.publishTypeDomain(next);
            domain.types = next;
        }

        void safePoint() {
            checkOpen();
            // A detached root retains producer authority but no longer has a
            // live session controller to service.  Generated safe points are
            // therefore inert until a new workspace is attached.
            if (rootLifetime != null && domain.closed) return;
            domain.controller.safePoint();
        }

        MethodHandle accessor(long id, long storageIdentity, String type, boolean write) {
            checkOpen();
            LinkEntry entry = entries.get(id);
            Requirement required = requirements.get(id);
            if (entry == null || required == null
                    || entry.storageIdentity != storageIdentity
                    || !entry.logicalType.equals(LyraType.parse(type))
                    || write && !required.writable()) {
                throw new LyraLinkException("submission access exceeds its typed storage linkage");
            }
            return entry.accessor(write);
        }

        RootLifetime rootLifetime() {
            return rootLifetime;
        }

        Map<Long, LinkEntry> entries() {
            return entries;
        }
    }
}
