package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableRootContext;
import io.mindspice.lyra.repl.remote.ApplicationAttachmentAdapter;
import io.mindspice.lyra.repl.remote.OwnerDispatcher;
import io.mindspice.lyra.repl.remote.ProtocolMessage;
import io.mindspice.lyra.repl.remote.RemoteCancellation;
import io.mindspice.lyra.repl.remote.RemoteCompletion;
import io.mindspice.lyra.repl.remote.RemoteEndpoint;
import io.mindspice.lyra.repl.remote.RemoteQuery;
import io.mindspice.lyra.repl.remote.RemoteServer;
import io.mindspice.lyra.repl.remote.RemoteServerOptions;
import io.mindspice.lyra.repl.remote.RemoteSessionAdapter;
import io.mindspice.lyra.repl.remote.RemoteSessionUnavailableException;
import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.OwnerThread;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Run/host activation composition for one attachable application.
 *
 * <p>Bootstrap opens the bounded loopback v2 listener before the root module
 * is published. Live work is truthfully rejected until
 * {@link #register(ModuleHandle, AttachableRootContext)} succeeds on the
 * owner thread; after registration the remote transport dispatches on the
 * root's shared controller, so generated safe points and explicit polls
 * execute remote work with exactly one admitted lease. An optional wait
 * pauses after registration and before main until the v2 controller
 * handshake completes or the service shuts down orderly; a raw accepted
 * socket is never mistaken for a ready controller.</p>
 *
 * <p>{@link #close()} retires queued control requests and closes the
 * listener and service surface while the externally owned root, its retained
 * values, and the root-lifetime producer/type domain stay open. Closing the
 * root module and its generations remains the caller's ownership step; a
 * later activation may reopen attachment on the same live root.</p>
 */
public final class ReplActivation implements AutoCloseable {
    public static final String ENABLED_PROPERTY = "lyra.repl.enabled";
    public static final String PORT_PROPERTY = "lyra.repl.port";
    public static final String WAIT_PROPERTY = "lyra.repl.wait";

    static final String UNAVAILABLE_DETAIL = "the application REPL service is initializing; "
            + "live work is admitted only after initialization and root registration";

    private final OwnerThread owner;
    private final SessionOptions options;
    private final RemoteServer server;
    private final DelegatingAdapter adapter = new DelegatingAdapter();
    private final SwitchingDispatcher dispatcher;
    private final boolean waitForController;
    private volatile ApplicationAttachment attachment;

    private ReplActivation(int port, boolean waitForController, SessionOptions options,
                           OwnerThread owner) throws IOException {
        this.options = Objects.requireNonNull(options, "options");
        this.waitForController = waitForController;
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dispatcher = new SwitchingDispatcher(owner);
        this.server = RemoteServer.open(adapter, dispatcher,
                RemoteServerOptions.builder().port(port).build());
    }

    /**
     * Reads the documented activation properties with the default disabled.
     * {@code lyra.repl.enabled=true} enables the listener, with optional
     * {@code lyra.repl.port} (0..65535, default 0) and
     * {@code lyra.repl.wait=true}. Absent or non-true enablement starts no
     * listener and touches no application arguments.
     */
    public static Optional<ReplActivation> fromProperties(SessionOptions options,
                                                          OutputStream status)
            throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(status, "status");
        String enabled = System.getProperty(ENABLED_PROPERTY);
        if (!"true".equals(enabled)) {
            return Optional.empty();
        }
        int port = 0;
        String portValue = System.getProperty(PORT_PROPERTY);
        if (portValue != null) {
            port = parsePort(PORT_PROPERTY, portValue);
        }
        boolean wait = "true".equals(System.getProperty(WAIT_PROPERTY));
        return Optional.of(bootstrap(port, wait, options, status));
    }

    /** Opens the listener bootstrap before any root publication. */
    public static ReplActivation bootstrap(int port, SessionOptions options,
                                           OutputStream status) throws IOException {
        return bootstrap(port, false, options, status);
    }

    /** Opens the listener bootstrap with the wait-for-controller control. */
    public static ReplActivation bootstrap(int port, boolean waitForController,
                                           SessionOptions options,
                                           OutputStream status) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(status, "status");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("repl port must be in 0..65535: " + port);
        }
        ReplActivation activation = new ReplActivation(port, waitForController, options,
                OwnerThread.capture());
        activation.announce(status);
        return activation;
    }

    /** The actual bound endpoint, including the authority warning. */
    public RemoteEndpoint endpoint() {
        return server.endpoint();
    }

    /** True once initialization succeeded and the root registration is live. */
    public boolean isRegistered() {
        return attachment != null;
    }

    /**
     * Handshake-complete v2 controllers. Controller readiness is exactly
     * this count; an accepted TCP connection that has not completed the v2
     * handshake is never counted.
     */
    public int controllerCount() {
        return server.controllerCount();
    }

    /**
     * Publishes the live attachment on the owner thread. Until this call
     * succeeds, remote live work is truthfully rejected and never touches
     * the root; afterwards the transport dispatches on the root's shared
     * controller so generated safe points service remote work.
     */
    public void register(ModuleHandle root, AttachableRootContext context) {
        owner.check();
        if (!server.isOpen()) {
            throw new LyraLifecycleException("REPL activation is closed");
        }
        if (attachment != null) {
            throw new LyraLifecycleException("REPL activation is already registered");
        }
        ApplicationAttachment live = ApplicationAttachment.open(
                Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(context, "context"),
                options);
        if (!server.isOpen()) {
            try {
                live.close();
            } catch (RuntimeException failure) {
                throw new LyraLifecycleException(
                        "REPL activation closed during root registration", List.of(), failure);
            }
            throw new LyraLifecycleException("REPL activation closed during root registration");
        }
        attachment = live;
        adapter.switchTo(ApplicationAttachmentAdapter.of(live));
        dispatcher.switchTo(OwnerDispatcher.runtime(live.registration().controller()));
    }

    /** Blocks, when configured, until controller readiness or orderly shutdown. */
    public boolean awaitControllerIfConfigured() {
        return waitForController && awaitController();
    }

    /**
     * Blocks on the owner thread until a controller completes the v2
     * handshake, the service shuts down orderly, or the thread is
     * interrupted. Returns whether a controller became ready.
     */
    public boolean awaitController() {
        owner.check();
        while (server.isOpen() && server.controllerCount() == 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return server.controllerCount() > 0;
    }

    /** Owner-side pump for explicitly embedding hosts; at most one operation. */
    public boolean poll() {
        return server.poll();
    }

    /** The live attachment, after successful registration. */
    public ApplicationAttachment attachment() {
        owner.check();
        ApplicationAttachment current = attachment;
        if (current == null) {
            throw new LyraLifecycleException("REPL activation has not registered a root");
        }
        return current;
    }

    /**
     * Retires queued control requests, closes the listener and the service
     * surface in that order, and never closes the externally owned root.
     * A later activation may reopen attachment on the same live root and
     * retained domain. Cleanup failures are aggregated, never silently
     * dropped.
     */
    @Override
    public void close() {
        owner.check();
        Throwable failure = null;
        try {
            server.close();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable current) {
            failure = current;
        }
        ApplicationAttachment current = attachment;
        if (current != null) {
            try {
                current.close();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private void announce(OutputStream status) {
        write(status, "lyra: repl listener: " + endpoint().display() + "\n");
    }

    private static void write(OutputStream output, String text) {
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
            // The status stream is not owned by the activation.
        }
    }

    private static int parsePort(String property, String value) {
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    property + " must be an integer in 0..65535: " + value);
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(property + " must be in 0..65535: " + value);
        }
        return port;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new LyraLifecycleException("REPL activation close failed",
                List.of(), failure);
    }

    /**
     * Dispatches on a volatile target. Before registration every dispatch is
     * truthfully rejected; the owner never runs remote live work before the
     * root registration succeeds. After registration the target is the
     * root's shared controller, so remote work and generated safe points
     * observe exactly one lease.
     */
    private static final class SwitchingDispatcher implements OwnerDispatcher {
        private final OwnerThread owner;
        private volatile OwnerDispatcher target;

        private SwitchingDispatcher(OwnerThread owner) {
            this.owner = owner;
            this.target = new UnavailableDispatcher(owner);
        }

        @Override
        public boolean isOwnerThread() {
            return owner.isCurrent();
        }

        @Override
        public Dispatch dispatch(Runnable operation) {
            return target.dispatch(operation);
        }

        @Override
        public boolean poll() {
            return target.poll();
        }

        private void switchTo(OwnerDispatcher live) {
            target = Objects.requireNonNull(live, "live");
        }
    }

    /** The bootstrap boundary: no live work is admitted before registration. */
    private static final class UnavailableDispatcher implements OwnerDispatcher {
        private final OwnerThread owner;

        private UnavailableDispatcher(OwnerThread owner) {
            this.owner = owner;
        }

        @Override
        public boolean isOwnerThread() {
            return owner.isCurrent();
        }

        @Override
        public Dispatch dispatch(Runnable operation) {
            throw new LyraLifecycleException(UNAVAILABLE_DETAIL);
        }

        @Override
        public boolean poll() {
            return false;
        }
    }

    /**
     * Stable session identity with a gated live target. Before registration
     * every stateful operation reports the initialization state truthfully;
     * after registration all operations forward to the real attachment
     * adapter, which runs on the owner thread only.
     */
    private static final class DelegatingAdapter implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private volatile RemoteSessionAdapter target;

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public SessionRevision revision() {
            RemoteSessionAdapter current = target;
            return current == null ? SessionRevision.initial() : current.revision();
        }

        @Override
        public EvaluationResult evaluate(EvaluationRequest request,
                                         RemoteCancellation cancellation) {
            return live().evaluate(request, cancellation);
        }

        @Override
        public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                     RemoteCancellation cancellation) {
            return live().load(request, cancellation);
        }

        @Override
        public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                     EvaluationSource source,
                                     RemoteCancellation cancellation) {
            return live().load(request, source, cancellation);
        }

        @Override
        public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                     RemoteCancellation cancellation,
                                     int maxFrameBytes) {
            return live().load(request, cancellation, maxFrameBytes);
        }

        @Override
        public EvaluationResult reload(ProtocolMessage.ReloadRequest request,
                                       RemoteCancellation cancellation) {
            return live().reload(request, cancellation);
        }

        @Override
        public boolean cancel(EvaluationId evaluationId) {
            RemoteSessionAdapter current = target;
            return current != null && current.cancel(evaluationId);
        }

        @Override
        public void reset() {
            live().reset();
        }

        @Override
        public RemoteQuery.Result query(RemoteQuery query) {
            return live().query(query);
        }

        @Override
        public RemoteCompletion.Result complete(RemoteCompletion request) {
            return live().complete(request);
        }

        private RemoteSessionAdapter live() {
            RemoteSessionAdapter current = target;
            if (current == null) {
                throw new RemoteSessionUnavailableException(UNAVAILABLE_DETAIL);
            }
            return current;
        }

        private void switchTo(RemoteSessionAdapter liveTarget) {
            target = Objects.requireNonNull(liveTarget, "liveTarget");
        }
    }
}
