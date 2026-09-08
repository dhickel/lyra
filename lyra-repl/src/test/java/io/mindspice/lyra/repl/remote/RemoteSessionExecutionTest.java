package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.*;
import io.mindspice.lyra.runtime.LyraOwnerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionExecutionTest {
    @TempDir Path temporary;

    @Test
    void realSessionQueriesExecuteOnOwnerAndRuntimeFramesSurviveTheWire() throws Exception {
        try (var session = LyraSession.open(); var controller = new LyraOwnerController();
             var server = RemoteServer.open(LyraSessionAdapter.of(session), controller,
                     RemoteServerOptions.defaults());
             var client = RemoteClient.connect(server.endpoint())) {
            var first = client.submit(EvaluationSource.of("binding.lyra", "let @mut count :I32 = 1"));
            pump(server, first.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, first.result().get().status());

            var bindings = client.queryBindings();
            pump(server, bindings);
            assertEquals(ProtocolMessage.QueryStatus.OK, bindings.get().status());
            assertTrue(bindings.get().bindings().stream().anyMatch(value -> value.name().equals("count")));

            var query = client.queryType(EvaluationSource.of("type.lyra", "count := 42"));
            pump(server, query);
            assertEquals(ProtocolMessage.QueryStatus.OK, query.get().status());
            var value = client.submit(EvaluationSource.of("read.lyra", "count"));
            pump(server, value.result());
            assertEquals("1", assertInstanceOf(ProtocolMessage.Scalar.class,
                    value.result().get().value().orElseThrow().data()).value());

            String source = "let note = \"😀\" let zero :I32 = 0 (% 1 zero)";
            var origin = new SourceOrigin("remote-selection", Optional.of(URI.create("memory:/remote.lyra")),
                    Optional.of(5L), 25, 25 + source.length());
            var failed = client.submit(new EvaluationSource(origin, source));
            pump(server, failed.result());
            var failure = failed.result().get();
            assertEquals(ProtocolMessage.RemoteStatus.RUNTIME_FAILURE, failure.status());
            var diagnostic = failure.diagnostics().stream().filter(item -> item.code().equals("LYR-ARITH"))
                    .findFirst().orElseThrow();
            assertEquals("memory:/remote.lyra", diagnostic.primarySpan().sourceId());
            assertEquals(25 + source.indexOf("(% 1 zero)"), diagnostic.primarySpan().startOffset());
            assertTrue(diagnostic.relatedSpans().stream().anyMatch(item -> item.label().contains("(% 1 zero)")));
            assertTrue(diagnostic.relatedSpans().stream().anyMatch(item -> item.label().equals("source version 5")));
        }
    }

    @Test
    void adapterReportsExistingAndOwnerSideRevisionsThroughNonExecutingOwnerQueries() throws Exception {
        try (var session = LyraSession.open(); var controller = new LyraOwnerController()) {
            assertInstanceOf(EvaluationResult.Success.class, session.submit("existing.lyra", "let @mut count :I32 = 1"));
            try (var server = RemoteServer.open(LyraSessionAdapter.of(session), controller,
                    RemoteServerOptions.defaults());
                 var client = RemoteClient.connect(server.endpoint())) {
                assertEquals(new SessionRevision(1), client.revision());
                assertInstanceOf(EvaluationResult.Success.class, session.submit("owner.lyra", "count := 41"));
                var query = client.queryBindings();
                pump(server, query);
                assertEquals(new SessionRevision(2), client.revision());
                var request = client.submit(EvaluationSource.of("remote.lyra", "(+ count 1)"));
                pump(server, request.result());
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, request.result().get().status());
                assertEquals("42", assertInstanceOf(ProtocolMessage.Scalar.class,
                        request.result().get().value().orElseThrow().data()).value());
            }
        }
    }

    private static void pump(RemoteServer server, CompletableFuture<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!result.isDone() && System.nanoTime() < deadline) {
            server.poll();
            Thread.sleep(1);
        }
        assertTrue(result.isDone(), "owner-dispatched operation did not complete");
    }
}
