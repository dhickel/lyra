package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.SessionOptions;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.runtime.LyraOwnerController;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-session LOAD/RELOAD over the credential-free v2 protocol. Files are
 * read exactly once on the server into captured file-URI sources; reload
 * carries real initializer progress; oversized graph sources and LOAD
 * payloads are rejected before effects; large dynamic results stay
 * terminal; host program I/O never enters wire payloads.
 */
class RemoteFileModuleTest {
    @TempDir
    Path serverRoot;

    @TempDir
    Path clientRoot;

    private SessionOptions options(ByteArrayOutputStream output) {
        return SessionOptions.builder()
                .sourceRoots(List.of(serverRoot))
                .ioEnvironment(new RuntimeIoEnvironment(
                        new ByteArrayInputStream(new byte[0]), output, output,
                        StandardCharsets.UTF_8))
                .build();
    }

    private record Fixture(
            LyraSession session,
            CountingAdapter adapter,
            RemoteServer server,
            RemoteClient client) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            client.close();
            server.close();
            session.close();
        }
    }

    private Fixture fixture(ByteArrayOutputStream output) throws Exception {
        LyraSession session = LyraSession.open(options(output));
        CountingAdapter adapter = CountingAdapter.of(session);
        RemoteServer server = RemoteServer.open(adapter, new LyraOwnerController());
        RemoteClient client = RemoteClient.connect(server.endpoint());
        return new Fixture(session, adapter, server, client);
    }

    @Test
    void completionRejectsPrefixesThatEscapeConfiguredRoots() throws Exception {
        Files.createDirectories(serverRoot.resolve("safe"));
        Files.writeString(serverRoot.resolve("safe/module.lyra"), "",
                StandardCharsets.UTF_8);

        RemoteCompletion.Result result = RemoteFileCompletion.moduleFiles(
                List.of(serverRoot), Optional.of("../"));

        assertEquals(RemoteCompletion.Status.UNAVAILABLE, result.status());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void loadReadsServerFileExactlyOnceIntoAFileUriSource() throws Exception {
        // A same-named file with different contents exists on the client
        // side; the server must read its own file, never the client's.
        Path clientCopy = clientRoot.resolve("loadme.lyra");
        Files.writeString(clientCopy, "let @pub @mut v :I32 = 999\n",
                StandardCharsets.UTF_8);
        Path serverFile = serverRoot.resolve("loadme.lyra");
        Files.writeString(serverFile, "let @pub @mut v :I32 = 7 v\n",
                StandardCharsets.UTF_8);
        Path failingFile = serverRoot.resolve("failing.lyra");
        Files.writeString(failingFile, "let zero :I32 = 0 (% 1 zero)\n",
                StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            RemoteRequest load = fixture.client().load(serverFile.toString());
            pump(fixture.server(), load.result());
            ProtocolMessage.Result result = load.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals("7", assertInstanceOf(ProtocolMessage.Scalar.class,
                    result.value().orElseThrow().data()).value());
            assertEquals(1, fixture.adapter().loads.get());
            assertEquals(0, fixture.adapter().evaluations.get());
            assertEquals("", output.toString(StandardCharsets.UTF_8));

            // A failing loaded file maps its runtime failure to the captured
            // server file URI, proving the file-URI source capture.
            RemoteRequest failure = fixture.client().load(failingFile.toString());
            pump(fixture.server(), failure.result());
            ProtocolMessage.Result failed = failure.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.RUNTIME_FAILURE, failed.status());
            assertTrue(failed.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.primarySpan().sourceId()
                                    .equals(failingFile.toUri().toString())),
                    failed.diagnostics().toString());
        }
    }

    @Test
    void loadIsReadExactlyOncePerRequestAndDuplicatesNeverRereadOrRerun()
            throws Exception {
        Path serverFile = serverRoot.resolve("exactly-once.lyra");
        Files.writeString(serverFile, "40", StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            // The first load is submitted at revision 0 mutation 0; capture
            // those fields for the later duplicate-identity control.
            RemoteRequest first = fixture.client().load(serverFile.toString());
            long firstRevision = 0;
            long firstMutation = 0;
            pump(fixture.server(), first.result());
            assertEquals("40", assertInstanceOf(ProtocolMessage.Scalar.class,
                    first.result().get(5, TimeUnit.SECONDS).value().orElseThrow().data()).value());
            assertEquals(1, fixture.adapter().loads.get());
            assertEquals(1, fixture.adapter().reads.get());

            // A second, distinct request executes exactly once more.
            RemoteRequest second = fixture.client().load(serverFile.toString());
            pump(fixture.server(), second.result());
            assertEquals(2, fixture.adapter().loads.get());
            assertEquals(2, fixture.adapter().reads.get());

            // An identical duplicate replays the retained result without any
            // additional read or execution; a changed payload is rejected.
            // The controlling client must release its slot first.
            fixture.client().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.server().controllerCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            try (RawClient raw = RawClient.open(fixture.server().endpoint())) {
                raw.send(new ProtocolMessage.LoadRequest(
                        first.requestId(), first.sequence(),
                        new SessionRevision(firstRevision), firstMutation,
                        serverFile.toString()));
                ProtocolMessage.Result replay = raw.readResult();
                assertEquals(first.result().get(5, TimeUnit.SECONDS), replay);

                raw.send(new ProtocolMessage.LoadRequest(
                        first.requestId(), first.sequence(),
                        new SessionRevision(firstRevision + 1), firstMutation,
                        serverFile.toString()));
                ProtocolMessage.Error error = assertInstanceOf(
                        ProtocolMessage.Error.class, raw.read());
                assertEquals(ProtocolMessage.ErrorCode.INVALID_SCHEMA, error.code());
            }
            assertEquals(2, fixture.adapter().loads.get());
            assertEquals(2, fixture.adapter().reads.get());
        }
    }

    @Test
    void reloadRebuildsServerModulesAndReturnsRealInitializerProgress()
            throws Exception {
        Path module = serverRoot.resolve("m").resolve("answer.lyra");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "import std->io io->::println[\"answer-init\"]\n"
                + "let @pub @mut answer :I32 = 40\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            RemoteRequest importRequest = fixture.client().submit(EvaluationSource.of(
                    "import.lyra", "import m->answer as m"));
            pump(fixture.server(), importRequest.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    importRequest.result().get(5, TimeUnit.SECONDS).status());
            assertEquals("answer-init\n", output.toString(StandardCharsets.UTF_8));

            RemoteRequest read = fixture.client().submit(EvaluationSource.of(
                    "read.lyra", "m->:.answer"));
            pump(fixture.server(), read.result());
            assertEquals("40", assertInstanceOf(ProtocolMessage.Scalar.class,
                    read.result().get(5, TimeUnit.SECONDS).value().orElseThrow().data()).value());

            // Edit the server file; the pinned instance stays until reload.
            Files.writeString(module, "import std->io io->::println[\"answer-init-2\"]\n"
                    + "let @pub @mut answer :I32 = 77\n", StandardCharsets.UTF_8);
            RemoteRequest reload = fixture.client().reload("m->answer");
            pump(fixture.server(), reload.result());
            ProtocolMessage.Result reloadResult = reload.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, reloadResult.status());
            assertEquals(1, fixture.adapter().reloads.get());
            // Real initializer progress: the reloaded module was scheduled,
            // attempted and completed, and the rerun initializer output is
            // host-side evidence. Reload-qualified producers carry fresh
            // session identities, so the exact module spelling is asserted
            // structurally rather than by file path.
            assertTrue(reloadResult.initializers().stream().anyMatch(entry ->
                    entry.state() == ProtocolMessage.InitializerState.SCHEDULED),
                    reloadResult.initializers().toString());
            assertTrue(reloadResult.initializers().stream().anyMatch(entry ->
                    entry.state() == ProtocolMessage.InitializerState.ATTEMPTED),
                    reloadResult.initializers().toString());
            assertTrue(reloadResult.initializers().stream().anyMatch(entry ->
                    entry.state() == ProtocolMessage.InitializerState.COMPLETED),
                    reloadResult.initializers().toString());
            assertEquals("answer-init\nanswer-init-2\n",
                    output.toString(StandardCharsets.UTF_8));

            RemoteRequest after = fixture.client().submit(EvaluationSource.of(
                    "after.lyra", "m->:.answer"));
            pump(fixture.server(), after.result());
            assertEquals("77", assertInstanceOf(ProtocolMessage.Scalar.class,
                    after.result().get(5, TimeUnit.SECONDS).value().orElseThrow().data()).value());

            // Reload of an unknown target fails without effects.
            RemoteRequest missing = fixture.client().reload("m->missing");
            pump(fixture.server(), missing.result());
            assertEquals(ProtocolMessage.RemoteStatus.COMPILATION_FAILURE,
                    missing.result().get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void oversizedGraphSourcesAreRejectedBeforeInitializerEffects() throws Exception {
        Path module = serverRoot.resolve("big").resolve("module.lyra");
        Files.createDirectories(module.getParent());
        Files.writeString(module,
                "import std->io io->::println[\"MUST-NOT-RUN\"]\n"
                        + "let @pub answer :I32 = 1\n", StandardCharsets.UTF_8);

        // A tiny retention budget cannot hold the imported graph; the
        // initializer must never run.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions limited = SessionOptions.builder()
                .sourceRoots(List.of(serverRoot))
                .maxSourceCharacters(64)
                .maxSourceRecords(4)
                .ioEnvironment(new RuntimeIoEnvironment(
                        new ByteArrayInputStream(new byte[0]), output, output,
                        StandardCharsets.UTF_8))
                .build();
        try (LyraSession session = LyraSession.open(limited)) {
            CountingAdapter adapter = CountingAdapter.of(session);
            try (RemoteServer server = RemoteServer.open(adapter, new LyraOwnerController());
                 RemoteClient client = RemoteClient.connect(server.endpoint())) {
                RemoteRequest request = client.submit(EvaluationSource.of(
                        "graph.lyra", "import big->module"));
                pump(server, request.result());
                ProtocolMessage.Result result = request.result().get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.RemoteStatus.COMPILATION_FAILURE, result.status());
                assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                                diagnostic.summary().contains("source registry")),
                        result.diagnostics().toString());
                assertEquals("", output.toString(StandardCharsets.UTF_8));
            }
        }

        // With sufficient capacity the same graph runs exactly once.
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(full)) {
            RemoteRequest request = fixture.client().submit(EvaluationSource.of(
                    "graph.lyra", "import big->module"));
            pump(fixture.server(), request.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    request.result().get(5, TimeUnit.SECONDS).status());
            assertEquals("MUST-NOT-RUN\n", full.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void loadEnvelopeBoundIsCheckedBeforeTheRealSessionAdapterRuns() throws Exception {
        Path source = serverRoot.resolve("envelope.lyra");
        Files.writeString(source, "x".repeat(200), StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LyraSession session = LyraSession.open(options(output));
             RemoteServer server = RemoteServer.open(
                     LyraSessionAdapter.of(session), new LyraOwnerController(),
                     RemoteServerOptions.builder().maxFrameBytes(RemoteProtocol.MIN_FRAME_BYTES)
                             .build());
             RemoteClient client = RemoteClient.connect(server.endpoint(),
                     RemoteClientOptions.builder().maxFrameBytes(RemoteProtocol.MIN_FRAME_BYTES)
                             .build())) {
            RemoteRequest load = client.load(source.toString());
            pump(server, load.result());
            ProtocolMessage.Result result = load.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.REJECTED, result.status());
            assertTrue(result.failureSummary().orElse("").contains("envelope"),
                    result.failureSummary().toString());
            assertEquals(new SessionRevision(0), client.revision());
        }
    }

    @Test
    void oversizedLoadPayloadIsRejectedBeforeAnyEffect() throws Exception {
        Path huge = serverRoot.resolve("huge.lyra");
        Files.writeString(huge, "x".repeat(RemoteProtocol.MAX_SOURCE_CHARACTERS + 1),
                StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            RemoteRequest load = fixture.client().load(huge.toString());
            pump(fixture.server(), load.result());
            ProtocolMessage.Result result = load.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.REJECTED, result.status());
            assertTrue(result.failureSummary().orElse("")
                    .contains("exceeds the protocol source bound"));
            assertEquals(0, fixture.adapter().evaluations.get());
            // The request was admitted exactly once and rejected before any
            // session effect; no submission was executed.
            assertEquals(1, fixture.adapter().loads.get());
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void largeDynamicResultsRemainTerminalWithExplicitTruncation() throws Exception {
        StringBuilder elements = new StringBuilder();
        for (int index = 0; index < 120; index++) {
            if (index > 0) {
                elements.append(' ');
            }
            elements.append(index);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            RemoteRequest request = fixture.client().submit(EvaluationSource.of(
                    "large.lyra", "Array<I32>[" + elements + "]"));
            pump(fixture.server(), request.result());
            ProtocolMessage.Result result = request.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            ProtocolMessage.Aggregate aggregate = assertInstanceOf(
                    ProtocolMessage.Aggregate.class, result.value().orElseThrow().data());
            assertTrue(aggregate.truncation().isPresent(), "aggregate truncation marker");
            assertEquals(100, aggregate.elements().size());
        }
    }

    @Test
    void unicodeAndHostIoStayOnTheirOwnSides() throws Exception {
        Path serverFile = serverRoot.resolve("unicode.lyra");
        Files.writeString(serverFile, "let @pub note :String = \"héllo 😀 🚀\" note\n",
                StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            RemoteRequest load = fixture.client().load(serverFile.toString());
            pump(fixture.server(), load.result());
            ProtocolMessage.Result result = load.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals("héllo 😀 🚀", assertInstanceOf(ProtocolMessage.Scalar.class,
                    result.value().orElseThrow().data()).value());

            // Program output goes to the host stream, never into the wire.
            RemoteRequest print = fixture.client().submit(EvaluationSource.of(
                    "print.lyra", "import std->io io->::println[\"secret-output\"]"));
            pump(fixture.server(), print.result());
            ProtocolMessage.Result printed = print.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, printed.status());
            assertFalse(new String(ProtocolCodec.encode(printed), StandardCharsets.UTF_8)
                    .contains("secret-output"));
            assertEquals("secret-output\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void cancellationBeforeOwnerPollTargetsOnlyTheLoadRequest() throws Exception {
        Path serverFile = serverRoot.resolve("cancel-load.lyra");
        Files.writeString(serverFile, "let @pub @mut v :I32 = 1 v := 2",
                StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(output)) {
            RemoteRequest cancelled = fixture.client().load(serverFile.toString());
            ProtocolMessage.CancelResult cancel = cancelled.cancel().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.ControlStatus.REQUESTED, cancel.status());
            assertEquals(ProtocolMessage.RemoteStatus.CANCELLED,
                    cancelled.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(0, fixture.adapter().loads.get());
            assertEquals(0, fixture.adapter().evaluations.get());

            // Later work is unaffected by the cancelled identity.
            RemoteRequest next = fixture.client().submit(EvaluationSource.of(
                    "next.lyra", "1"));
            pump(fixture.server(), next.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    next.result().get(5, TimeUnit.SECONDS).status());
        }
    }

    /** Counts adapter invocations and underlying session file reads. */
    static final class CountingAdapter implements RemoteSessionAdapter {
        private final LyraSessionAdapter delegate;
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger reloads = new AtomicInteger();
        /** Distinct file reads observed through the delegate seam. */
        final AtomicInteger reads = new AtomicInteger();

        private CountingAdapter(LyraSessionAdapter delegate) {
            this.delegate = delegate;
        }

        static CountingAdapter of(LyraSession session) {
            return new CountingAdapter(LyraSessionAdapter.of(session));
        }

        @Override
        public java.util.UUID sessionId() {
            return delegate.sessionId();
        }

        @Override
        public SessionRevision revision() {
            return delegate.revision();
        }

        @Override
        public EvaluationResult evaluate(EvaluationRequest request,
                                         RemoteCancellation cancellation) {
            evaluations.incrementAndGet();
            return delegate.evaluate(request, cancellation);
        }

        @Override
        public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                     RemoteCancellation cancellation) {
            loads.incrementAndGet();
            try {
                return delegate.load(request, cancellation);
            } finally {
                reads.incrementAndGet();
            }
        }

        @Override
        public EvaluationResult reload(ProtocolMessage.ReloadRequest request,
                                       RemoteCancellation cancellation) {
            reloads.incrementAndGet();
            return delegate.reload(request, cancellation);
        }

        @Override
        public boolean cancel(EvaluationId evaluationId) {
            return delegate.cancel(evaluationId);
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public RemoteQuery.Result query(RemoteQuery query) {
            return delegate.query(query);
        }

        @Override
        public RemoteCompletion.Result complete(RemoteCompletion request) {
            return delegate.complete(request);
        }
    }

    /** Minimal raw v2 client for duplicate-identity control. */
    static final class RawClient implements AutoCloseable {
        private final java.net.Socket socket;
        private final FrameCodec frames = new FrameCodec();

        private RawClient(java.net.Socket socket) {
            this.socket = socket;
        }

        static RawClient open(RemoteEndpoint endpoint) throws Exception {
            java.net.Socket socket = new java.net.Socket(
                    endpoint.address().address(), endpoint.address().port());
            socket.setTcpNoDelay(true);
            RawClient client = new RawClient(socket);
            client.send(new ProtocolMessage.ClientHello(
                    RemoteProtocol.VERSION, java.util.UUID.randomUUID(),
                    endpoint.sessionId()));
            assertInstanceOf(ProtocolMessage.ServerHello.class, client.read());
            return client;
        }

        void send(ProtocolMessage message) throws java.io.IOException {
            ProtocolCodec.write(frames, socket.getOutputStream(), message);
        }

        ProtocolMessage read() throws Exception {
            return ProtocolCodec.decode(
                    frames.readFrame(socket.getInputStream()).orElseThrow());
        }

        ProtocolMessage.Result readResult() throws Exception {
            while (true) {
                ProtocolMessage message = read();
                if (message instanceof ProtocolMessage.Result result) {
                    return result;
                }
            }
        }

        @Override
        public void close() throws java.io.IOException {
            socket.close();
        }
    }

    private static void pump(RemoteServer server,
                             java.util.concurrent.CompletableFuture<?> result)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!result.isDone() && System.nanoTime() < deadline) {
            server.poll();
            Thread.sleep(1);
        }
        assertTrue(result.isDone(), "owner-dispatched operation did not complete");
    }
}
