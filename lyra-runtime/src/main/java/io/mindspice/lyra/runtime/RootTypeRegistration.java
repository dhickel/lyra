package io.mindspice.lyra.runtime;

import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exact public-root view installed by {@link LyraRuntime#registerRoot(ModuleHandle)}.
 *
 * <p>The view contains only generated public export accessors from one live
 * root instance.  It does not own the module, copy its values, or expose a
 * reflective name-based execution surface.  The bound handles retain the
 * original facade instance and its generated descriptors.</p>
 */
public final class RootTypeRegistration implements AutoCloseable {
    /** One exact generated root export and its optional typed accessors. */
    public record Binding(ExportMetadata metadata, MethodHandle invocation,
                          MethodHandle getter, Optional<MethodHandle> setter,
                          Optional<MethodHandle> functionValue) {
        public Binding {
            metadata = Objects.requireNonNull(metadata, "metadata");
            invocation = Objects.requireNonNull(invocation, "invocation");
            getter = Objects.requireNonNull(getter, "getter");
            setter = Objects.requireNonNull(setter, "setter");
            functionValue = Objects.requireNonNull(functionValue, "functionValue");
            if (metadata.isFunction() != functionValue.isPresent()) {
                throw new IllegalArgumentException("root function-value accessor does not match export kind");
            }
            if (!metadata.isFunction() && functionValue.isPresent()) {
                throw new IllegalArgumentException("scalar root binding has a function value");
            }
            if (metadata.mutable() != setter.isPresent()) {
                throw new IllegalArgumentException("root mutability does not match setter presence");
            }
        }

        public boolean mutable() {
            return setter.isPresent();
        }

        public boolean function() {
            return metadata.isFunction();
        }
    }

    private final ModuleHandle module;
    private final ModuleLifecycle lifecycle;
    private final SessionStorageDomain.RootLifetime rootLifetime;
    private final Map<String, Binding> bindings;
    private final Set<Class<?>> structuralTypes;
    private final LyraOwnerController controller;
    private boolean closed;

    RootTypeRegistration(ModuleHandle module, ModuleLifecycle lifecycle,
                         SessionStorageDomain.RootLifetime rootLifetime,
                         Map<String, Binding> bindings, Set<Class<?>> structuralTypes,
                         LyraOwnerController controller) {
        this.module = Objects.requireNonNull(module, "module");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.rootLifetime = Objects.requireNonNull(rootLifetime, "rootLifetime");
        this.bindings = Collections.unmodifiableMap(new TreeMap<>(bindings));
        this.structuralTypes = Set.copyOf(structuralTypes);
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    /** The exact live root module from which these handles were obtained. */
    public ModuleHandle module() {
        requireActive();
        return module;
    }

    public ModuleId moduleId() {
        requireActive();
        return module.moduleId();
    }

    public ArtifactMetadata metadata() {
        requireActive();
        return module.metadata();
    }

    public ModuleLifecycle lifecycle() {
        requireActive();
        return lifecycle;
    }

    /** The root-owned structural class domain retained across service reopenings. */
    public SessionStorageDomain.RootLifetime rootLifetime() {
        requireActive();
        return rootLifetime;
    }

    /** Exact generated structural classes observed by the registered exports. */
    public Set<Class<?>> structuralTypes() {
        requireActive();
        return structuralTypes;
    }

    /** Sorted public root bindings; private state is never included. */
    public List<Binding> bindings() {
        requireActive();
        return List.copyOf(bindings.values());
    }

    public Optional<Binding> binding(String name) {
        requireActive();
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(bindings.get(name));
    }

    public Binding requireBinding(String name) {
        return binding(name).orElseThrow(() ->
                new IllegalArgumentException("root export is absent: " + name));
    }

    /** Owner-thread control service installed for this exact root. */
    public LyraOwnerController controller() {
        requireActive();
        return controller;
    }

    public LyraOwnerController applicationController() {
        return controller();
    }

    public boolean isClosed() {
        requireOwner();
        return closed;
    }

    /** Polls at most one registered operation on the application owner. */
    public boolean poll() {
        requireOpen();
        return controller.poll();
    }

    /** Publishes one non-reentrant owner operation for a generated safe point. */
    public LyraOwnerController.Dispatch dispatch(Runnable operation) {
        requireOpen();
        return controller.dispatch(operation);
    }

    /** Closes only this registration/service; the externally owned root remains open. */
    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        lifecycle.unregisterApplicationController(controller);
        controller.close();
        closed = true;
    }

    private void requireOpen() {
        requireActive();
        lifecycle.checkOpen();
    }

    private void requireActive() {
        requireOwner();
        if (closed) throw new LyraClosedException("root registration is closed");
        lifecycle.checkOpen();
    }

    private void requireOwner() {
        if (!lifecycle.isOwnerThread()) {
            throw new LyraThreadException("root registration accessed from a non-owner thread");
        }
    }
}
