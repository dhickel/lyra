package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.CrossSurfaceCorpus;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.SessionOptions;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.runtime.LyraOwnerController;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 cross-surface conformance, surface 3: the remote standalone v2
 * adapter.  A real LyraSession executes the common corpus through the
 * credential-free loopback protocol with revision/mutation-sequence
 * freshness, reconnect without replay, reset freshness, bounded dynamic
 * results, and host-side I/O that never enters the wire.
 */
class CrossSurfaceRemoteStandaloneTest {
    private record Fixture(
            LyraSession session,
            RemoteFileModuleTest.CountingAdapter adapter,
            RemoteServer server,
            RemoteClient client) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            client.close();
            server.close();
            session.close();
        }
    }

    private Fixture fixture(CrossSurfaceCorpus.Sources sources,
                            ByteArrayOutputStream output) throws Exception {
        LyraSession session = LyraSession.open(
                CrossSurfaceCorpus.options(sources, output).build());
        RemoteFileModuleTest.CountingAdapter adapter =
                RemoteFileModuleTest.CountingAdapter.of(session);
        RemoteServer server = RemoteServer.open(adapter, new LyraOwnerController());
        RemoteClient client = RemoteClient.connect(server.endpoint());
        return new Fixture(session, adapter, server, client);
    }

    @Test
    void commonCorpusExecutesThroughTheRemoteStandaloneAdapter() throws Exception {
        var sources = new CrossSurfaceCorpus.Sources();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(sources, output)) {
            long revision = 0;
            for (CrossSurfaceCorpus.Step step : CrossSurfaceCorpus.program()) {
                if (CrossSurfaceCorpus.isMutation(step)) {
                    sources.mutate(CrossSurfaceCorpus.mutation(step));
                    continue;
                }
                RemoteRequest request;
                if (CrossSurfaceCorpus.isReload(step)) {
                    request = fixture.client().reload(CrossSurfaceCorpus.reloadTarget(step));
                } else {
                    request = fixture.client().submit(EvaluationSource.of(
                            step.label() + ".lyra", step.source()));
                }
                pump(fixture.server(), request.result());
                ProtocolMessage.Result result = request.result().get(10, TimeUnit.SECONDS);
                assertExpectation(step, result, revision);
                if (result.status() == ProtocolMessage.RemoteStatus.SUCCESS) {
                    assertTrue(result.revision() >= revision, step.label());
                    revision = result.revision();
                }
            }
            // Initializer markers ran once per generation; program I/O stayed
            // on the host and never entered the wire.
            String host = output.toString(StandardCharsets.UTF_8);
            assertEquals(1, occurrences(host, "counter-init\n"), host);
            assertEquals(1, occurrences(host, "counter-init-2\n"), host);
            assertTrue(host.contains("héllo 😀\n"), host);
            assertEquals(0, fixture.adapter().loads.get());
            assertEquals(3, fixture.adapter().reloads.get());
        }
    }

    @Test
    void reconnectReportsRetainedStatusWithoutReplayOrSourceResubmission() throws Exception {
        var sources = new CrossSurfaceCorpus.Sources();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(sources, output)) {
            RemoteRequest first = fixture.client().submit(EvaluationSource.of(
                    "counter.lyra", "import counter counter->::bump[]"));
            pump(fixture.server(), first.result());
            ProtocolMessage.Result result = first.result().get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            int evaluations = fixture.adapter().evaluations.get();
            long revision = result.revision();
            long mutation = result.mutationSequence();

            fixture.client().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.server().controllerCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            try (RemoteClient reconnected = RemoteClient.connect(fixture.server().endpoint())) {
                assertEquals(new SessionRevision(revision), reconnected.revision());
                assertEquals(mutation, reconnected.mutationSequence());
                // Retained terminal status is reported without resubmitting
                // the source and without any further evaluation.
                CompletableFuture<ProtocolMessage.QueryResult> status =
                        reconnected.queryRequest(first.requestId());
                pump(fixture.server(), status);
                ProtocolMessage.QueryResult query = status.get(10, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.QueryStatus.OK, query.status());
                assertEquals(evaluations, fixture.adapter().evaluations.get());
            }
        }
    }

    @Test
    void resetAdvancesMutationSequenceWithoutReplayingAndKeepsFreshness() throws Exception {
        var sources = new CrossSurfaceCorpus.Sources();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(sources, output)) {
            RemoteRequest first = fixture.client().submit(EvaluationSource.of(
                    "first.lyra", "let @pub scratch :I32 = 1"));
            pump(fixture.server(), first.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    first.result().get(10, TimeUnit.SECONDS).status());
            long revision = fixture.client().revision().value();
            long sequence = fixture.client().mutationSequence();

            CompletableFuture<ProtocolMessage.ResetResult> reset =
                    fixture.client().reset(revision);
            pump(fixture.server(), reset);
            assertEquals(ProtocolMessage.ControlStatus.OK, reset.get(10, TimeUnit.SECONDS).status());
            // Reset never rewinds the revision but advances the mutation
            // sequence, so stale metadata cannot describe the fresh state.
            assertEquals(new SessionRevision(revision), fixture.client().revision());
            assertTrue(fixture.client().mutationSequence() > sequence,
                    "reset advances the wire mutation sequence");

            CompletableFuture<ProtocolMessage.QueryResult> bindings =
                    fixture.client().queryBindings();
            pump(fixture.server(), bindings);
            ProtocolMessage.QueryResult query = bindings.get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.OK, query.status());
            assertTrue(query.bindings().isEmpty(), query.bindings().toString());

            RemoteRequest second = fixture.client().submit(EvaluationSource.of(
                    "second.lyra", "let @pub fresh :I32 = 2"));
            pump(fixture.server(), second.result());
            ProtocolMessage.Result after = second.result().get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, after.status());
            assertTrue(after.revision() > revision);
        }
    }

    @Test
    void delayedFailuresCarryExactImportedAndReloadedSpansOverTheWire() throws Exception {
        var sources = new CrossSurfaceCorpus.Sources();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(sources, output)) {
            RemoteRequest capture = fixture.client().submit(EvaluationSource.of(
                    "capture.lyra", "import errors import errors->{fail as oldFail}"));
            pump(fixture.server(), capture.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    capture.result().get(10, TimeUnit.SECONDS).status());

            RemoteRequest late = fixture.client().submit(EvaluationSource.of(
                    "late.lyra", "errors->::late[]"));
            pump(fixture.server(), late.result());
            ProtocolMessage.Result first = late.result().get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.RUNTIME_FAILURE, first.status());
            String uri = "memory://corpus/errors.lyra";
            assertEquals(CrossSurfaceCorpus.ERRORS_V1.indexOf("(%"),
                    span(first, uri).startOffset());

            sources.mutate("errors->v2");
            RemoteRequest reload = fixture.client().reload("errors");
            pump(fixture.server(), reload.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    reload.result().get(10, TimeUnit.SECONDS).status());

            RemoteRequest old = fixture.client().submit(EvaluationSource.of(
                    "old.lyra", "(oldFail 0)"));
            pump(fixture.server(), old.result());
            assertEquals(CrossSurfaceCorpus.ERRORS_V1.indexOf("(%"),
                    span(old.result().get(10, TimeUnit.SECONDS), uri).startOffset());
            RemoteRequest fresh = fixture.client().submit(EvaluationSource.of(
                    "new.lyra", "errors->::late[]"));
            pump(fixture.server(), fresh.result());
            ProtocolMessage.Span freshSpan = span(fresh.result().get(10, TimeUnit.SECONDS), uri);
            assertEquals(CrossSurfaceCorpus.ERRORS_V2.indexOf("(%"), freshSpan.startOffset(),
                    "the astral comment shifts the UTF-16 span exactly");
        }
    }

    @Test
    void ownershipAndBoundedResultsStayTerminalOverTheWire() throws Exception {
        var sources = new CrossSurfaceCorpus.Sources();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(sources, output)) {
            RemoteRequest importValues = fixture.client().submit(EvaluationSource.of(
                    "import.lyra", "import values"));
            pump(fixture.server(), importValues.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    importValues.result().get(10, TimeUnit.SECONDS).status());

            RemoteRequest ownership = fixture.client().submit(EvaluationSource.of(
                    "ownership.lyra", "values->:.items[0] := 9"));
            pump(fixture.server(), ownership.result());
            ProtocolMessage.Result rejected = ownership.result().get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.COMPILATION_FAILURE, rejected.status());
            assertTrue(rejected.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals("LYC-RESOLVE-022")),
                    rejected.diagnostics().toString());

            RemoteRequest large = fixture.client().submit(EvaluationSource.of(
                    "large.lyra", CrossSurfaceCorpus.LARGE_ARRAY_SOURCE));
            pump(fixture.server(), large.result());
            ProtocolMessage.Result dynamic = large.result().get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, dynamic.status());
            ProtocolMessage.Aggregate aggregate = assertInstanceOf(ProtocolMessage.Aggregate.class,
                    dynamic.value().orElseThrow().data());
            assertTrue(aggregate.truncation().isPresent(), "explicit truncation marker");
            assertEquals(100, aggregate.elements().size());
            assertEquals("Array<I32>", dynamic.value().orElseThrow().canonicalType());
        }
    }

    @Test
    void endpointAndFramesCarryNoCredentialMaterial() throws Exception {
        var sources = new CrossSurfaceCorpus.Sources();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Fixture fixture = fixture(sources, output)) {
            assertTrue(fixture.server().endpoint().display().contains("no authentication"),
                    fixture.server().endpoint().display());
            assertTrue(fixture.server().endpoint().address().address().isLoopbackAddress());
            RemoteRequest request = fixture.client().submit(EvaluationSource.of(
                    "hello.lyra", "1"));
            pump(fixture.server(), request.result());
            ProtocolMessage.Result result = request.result().get(10, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            // The real wire codec output for a terminal result carries only
            // schema data; no credential/token field or value exists.
            String encoded = new String(ProtocolCodec.encode(result), StandardCharsets.UTF_8);
            assertFalse(encoded.toLowerCase().contains("token"), encoded);
            assertFalse(encoded.toLowerCase().contains("credential"), encoded);
            assertFalse(encoded.toLowerCase().contains("password"), encoded);
            assertFalse(encoded.contains("counter-init"), encoded);
        }
    }

    /* ---- expectation mapping ---- */

    private static void assertExpectation(CrossSurfaceCorpus.Step step,
                                          ProtocolMessage.Result result, long revision) {
        switch (step.expectation()) {
            case SUCCESS_NO_VALUE -> assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    result.status(), step.label() + " => " + result);
            case SUCCESS_SCALAR -> {
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
                ProtocolMessage.Scalar scalar = assertInstanceOf(ProtocolMessage.Scalar.class,
                        result.value().orElseThrow().data(), step.label() + " => " + result);
                assertEquals(step.expected(), scalar.value(), step.label());
            }
            case SUCCESS_FUNCTION -> {
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
                ProtocolMessage.Function function = assertInstanceOf(ProtocolMessage.Function.class,
                        result.value().orElseThrow().data(), step.label() + " => " + result);
                assertFalse(function.identity().isBlank(), step.label());
            }
            case SUCCESS_AGGREGATE_TRUNCATED -> {
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
                ProtocolMessage.Aggregate aggregate = assertInstanceOf(ProtocolMessage.Aggregate.class,
                        result.value().orElseThrow().data(), step.label() + " => " + result);
                assertTrue(aggregate.truncation().isPresent(), step.label());
                assertEquals(100, aggregate.elements().size(), step.label());
            }
            case OWNERSHIP_DIAGNOSTIC -> {
                assertEquals(ProtocolMessage.RemoteStatus.COMPILATION_FAILURE,
                        result.status(), step.label() + " => " + result);
                assertTrue(result.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals(step.expected())),
                        step.label() + " => " + result);
            }
            case RUNTIME_DIVISION -> {
                assertEquals(ProtocolMessage.RemoteStatus.RUNTIME_FAILURE,
                        result.status(), step.label() + " => " + result);
                assertTrue(result.failureSummary().orElse("").contains("division by zero"),
                        step.label() + " => " + result);
                assertTrue(result.diagnostics().stream()
                                .anyMatch(diagnostic -> diagnostic.summary().contains("(%")
                                        || diagnostic.summary().contains("division")),
                        step.label() + " => " + result);
            }
        }
    }

    private static ProtocolMessage.Span span(ProtocolMessage.Result result, String sourceId) {
        return result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.primarySpan().sourceId().equals(sourceId))
                .filter(diagnostic -> diagnostic.primarySpan().endOffset()
                        > diagnostic.primarySpan().startOffset())
                .map(ProtocolMessage.Diagnostic::primarySpan)
                .filter(span -> span.startOffset() > 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError(result.toString()));
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
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
