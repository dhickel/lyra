package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.repl.ApplicationAttachment;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionOptions;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraOwnerController;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.RootTypeRegistration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential-free v2 attachment to a real, explicitly registered
 * attachable application root. Every evaluation executes through the real
 * root workspace on the owner thread; no token, credential file or
 * challenge exists anywhere in the attach/start/close path.
 */
class NoAuthAttachmentTest {
    private static final String ROOT_SOURCE = "let @pub @mut count :I32 = 1\n"
            + "let @pub readCount :Fn<;I32> = (=> | | count)\n"
            + "let @pub @mut values :Array<I32> = Array<I32>[1 2]\n";

    @TempDir
    Path temporaryDirectory;

    private AttachableCompileResult.Success compiledRoot() {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", ROOT_SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    private ApplicationAttachment open(AttachableCompileResult.Success compiled,
                                       ModuleHandle root) {
        SessionOptions options = SessionOptions.builder()
                .sourceRoots(java.util.List.of(temporaryDirectory))
                .build();
        return ApplicationAttachment.open(root, compiled.context(), options);
    }

    @Test
    void attachExecutesRealRootStateWithoutAnyCredentialSurface() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ModuleHandle root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root);
             RemoteServer server = RemoteServer.open(
                     ApplicationAttachmentAdapter.of(attachment), new LyraOwnerController());
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            // The endpoint carries an address and session identity only.
            assertEquals(2, RemoteEndpoint.class.getRecordComponents().length);

            // Real root execution: the remote evaluation mutates the actual
            // public root storage.
            RemoteRequest write = client.submit(EvaluationSource.of("write.lyra", "count := 41"));
            pump(server, write.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    write.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(new SessionRevision(1), client.revision());
            RootTypeRegistration registration = attachment.registration();
            assertEquals(41, registration.requireBinding("count").getter().invoke());

            // Reads see the real mutated storage through the root function.
            RemoteRequest read = client.submit(EvaluationSource.of("read.lyra", "(readCount)"));
            pump(server, read.result());
            ProtocolMessage.Result result = read.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals("41", assertInstanceOf(ProtocolMessage.Scalar.class,
                    result.value().orElseThrow().data()).value());

            // Private/immutable names stay ordinary diagnostics.
            RemoteRequest hidden = client.submit(EvaluationSource.of("hidden.lyra", "hidden"));
            pump(server, hidden.result());
            assertEquals(ProtocolMessage.RemoteStatus.COMPILATION_FAILURE,
                    hidden.result().get(5, TimeUnit.SECONDS).status());

            // Metadata queries return the committed public root scope.
            java.util.concurrent.CompletableFuture<ProtocolMessage.QueryResult> bindingsFuture =
                    client.queryBindings();
            pump(server, bindingsFuture);
            ProtocolMessage.QueryResult bindings = bindingsFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.OK, bindings.status());
            assertTrue(bindings.bindings().stream()
                    .anyMatch(binding -> binding.name().equals("count")));

            // Bounded member completion over committed metadata only.
            java.util.concurrent.CompletableFuture<ProtocolMessage.CompletionResult> membersFuture =
                    client.completeBindingMembers("values");
            pump(server, membersFuture);
            ProtocolMessage.CompletionResult members = membersFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.OK, members.status());
            assertTrue(members.items().stream().anyMatch(item ->
                    item.name().equals("length")
                            && item.typeSpelling().orElse("").equals("I32")));
            assertTrue(members.items().stream().anyMatch(item ->
                    item.kind() == ProtocolMessage.CompletionItemKind.MEMBER));

            // Bounded server-side file listing under the configured root.
            Files.createDirectories(temporaryDirectory.resolve("game"));
            Files.writeString(temporaryDirectory.resolve("game").resolve("math.lyra"),
                    "", StandardCharsets.UTF_8);
            java.util.concurrent.CompletableFuture<ProtocolMessage.CompletionResult> filesFuture =
                    client.completeModuleFiles(Optional.empty());
            pump(server, filesFuture);
            ProtocolMessage.CompletionResult files = filesFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.OK, files.status());
            assertTrue(files.items().stream().anyMatch(item ->
                    item.name().equals("game/math")
                            && item.kind() == ProtocolMessage.CompletionItemKind.MODULE));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void serverSideLoadExecutesOnTheRootAndReloadIsExplicitlyUnavailable()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ModuleHandle root = loaded.instantiate();
        Path loadFile = temporaryDirectory.resolve("load-target.lyra");
        Files.writeString(loadFile, "(+ count 1)", StandardCharsets.UTF_8);
        try (ApplicationAttachment attachment = open(compiled, root);
             RemoteServer server = RemoteServer.open(
                     ApplicationAttachmentAdapter.of(attachment), new LyraOwnerController());
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            // LOAD reads the server-side file exactly once and submits the
            // captured file-URI source against the real root.
            RemoteRequest load = client.load(loadFile.toString());
            pump(server, load.result());
            ProtocolMessage.Result result = load.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals("2", assertInstanceOf(ProtocolMessage.Scalar.class,
                    result.value().orElseThrow().data()).value());
            RootTypeRegistration registration = attachment.registration();
            assertEquals(1, registration.requireBinding("count").getter().invoke());

            // A missing server-side file is rejected before any effect.
            RemoteRequest missing = client.load(
                    temporaryDirectory.resolve("missing.lyra").toString());
            pump(server, missing.result());
            assertEquals(ProtocolMessage.RemoteStatus.REJECTED,
                    missing.result().get(5, TimeUnit.SECONDS).status());

            // Application-owned modules are never reloadable; the wire
            // reports the outcome explicitly instead of replaying anything.
            RemoteRequest reload = client.reload("main");
            pump(server, reload.result());
            ProtocolMessage.Result reloadResult = reload.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.UNAVAILABLE, reloadResult.status());
            assertTrue(reloadResult.failureSummary().orElse("")
                    .contains("cannot reload modules"));
            assertEquals(1, registration.requireBinding("count").getter().invoke());
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void resetPreservesRootStateAndBumpsTheMutationSequence() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ModuleHandle root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root);
             RemoteServer server = RemoteServer.open(
                     ApplicationAttachmentAdapter.of(attachment), new LyraOwnerController());
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest write = client.submit(EvaluationSource.of("write.lyra", "count := 41"));
            pump(server, write.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    write.result().get(5, TimeUnit.SECONDS).status());

            java.util.concurrent.CompletableFuture<ProtocolMessage.ResetResult> resetFuture =
                    client.reset(client.revision().value());
            pump(server, resetFuture);
            ProtocolMessage.ResetResult reset = resetFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.ControlStatus.OK, reset.status());
            // Root state and revision survive; the mutation sequence makes
            // the reset observable to stale console metadata.
            assertEquals(1, reset.revision());
            assertEquals(2, reset.mutationSequence());
            RootTypeRegistration registration = attachment.registration();
            assertEquals(41, registration.requireBinding("count").getter().invoke());

            // Work continues against the real root after the reset.
            RemoteRequest read = client.submit(EvaluationSource.of("read.lyra", "(readCount)"));
            pump(server, read.result());
            assertEquals("41", assertInstanceOf(ProtocolMessage.Scalar.class,
                    read.result().get(5, TimeUnit.SECONDS).value().orElseThrow().data()).value());
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void duplicateServiceOnTheSameRootIsRejectedAndDisconnectPreservesRoot()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ModuleHandle root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root);
             RemoteServer server = RemoteServer.open(
                     ApplicationAttachmentAdapter.of(attachment), new LyraOwnerController());
             RemoteClient first = RemoteClient.connect(server.endpoint())) {
            // One controlling connection at a time; a second service on the
            // same root would fail attachment registration before listening.
            assertThrows(RemoteOperationException.class,
                    () -> RemoteClient.connect(server.endpoint()));

            RemoteRequest write = first.submit(EvaluationSource.of("write.lyra", "count := 41"));
            pump(server, write.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    write.result().get(5, TimeUnit.SECONDS).status());

            // Disconnect preserves committed root state and workspace.
            first.close();
            java.util.concurrent.CountDownLatch released =
                    new java.util.concurrent.CountDownLatch(1);
            Thread waitForRelease = new Thread(() -> {
                try {
                    long deadline = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);
                    while (server.controllerCount() != 0
                            && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                    }
                    released.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "no-auth-release-wait");
            waitForRelease.start();
            assertTrue(released.await(5, TimeUnit.SECONDS));

            try (RemoteClient reconnected = RemoteClient.connect(server.endpoint())) {
                RemoteRequest read = reconnected.submit(
                        EvaluationSource.of("read.lyra", "(readCount)"));
                pump(server, read.result());
                assertEquals("41", assertInstanceOf(ProtocolMessage.Scalar.class,
                        read.result().get(5, TimeUnit.SECONDS).value().orElseThrow().data()).value());
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void cancellationTargetsTheExactRequestAndNeverLaterWork() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        LoadedArtifact loaded = LyraRuntime.load(compiled.artifact());
        ModuleHandle root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root);
             RemoteServer server = RemoteServer.open(
                     ApplicationAttachmentAdapter.of(attachment), new LyraOwnerController());
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            // Cancel before the owner poll: the request is terminal CANCELLED
            // and the subsequent request is unaffected.
            RemoteRequest cancelled = client.submit(
                    EvaluationSource.of("cancel.lyra", "count := 999"));
            ProtocolMessage.CancelResult cancel = cancelled.cancel().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.ControlStatus.REQUESTED, cancel.status());
            assertEquals(ProtocolMessage.RemoteStatus.CANCELLED,
                    cancelled.result().get(5, TimeUnit.SECONDS).status());

            RemoteRequest next = client.submit(EvaluationSource.of("next.lyra", "count := 42"));
            pump(server, next.result());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    next.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(42, attachment.registration()
                    .requireBinding("count").getter().invoke());
        } finally {
            root.close();
            loaded.close();
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
