package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LyraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 08 attachment Java consumer: a plain external Java program proves
 * explicit open/poll/close/reopen, busy/stale/closed admission, exact
 * cancellation identities, and single-lease owner execution without an
 * application executor.
 */
class ApplicationAttachmentJavaConsumerTest {
    @TempDir Path directory;

    @Test
    void externalJavaConsumerProvesExplicitOpenPollCloseReopenAndCancellationIdentities()
            throws Exception {
        Path source = directory.resolve("AttachmentConsumer.java");
        Files.writeString(source, """
                import io.mindspice.lyra.repl.*;
                import io.mindspice.lyra.compiler.api.*;
                import io.mindspice.lyra.runtime.*;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.*;

                public final class AttachmentConsumer {
                    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
                    static String scalar(EvaluationResult.Success result) {
                        return ((ValueSnapshot.Scalar) result.value().orElseThrow().data()).value();
                    }
                    public static void main(String[] args) throws Exception {
                        var compiled = (AttachableCompileResult.Success) LyraCompiler.compileAttachable(
                                CompileRequest.builder().source("main.lyra",
                                    "let @pub @mut count :I32 = 1\\n"
                                  + "let @pub readCount :Fn<;I32> = (=> | | count)\\n"
                                  + "let @pub add :Fn<I32,I32;I32> = (=> |a b| (+ a b))\\n")
                                .profile(CompileProfile.ATTACHABLE).build());
                        var loaded = LyraRuntime.load(compiled.artifact());
                        var failure = new AtomicReference<Throwable>();
                        var ready = new CountDownLatch(1);
                        var commands = new LinkedBlockingQueue<String>();
                        var evidence = new LinkedBlockingQueue<Object>();
                        var rootRef = new AtomicReference<ModuleHandle>();
                        var attachmentRef = new AtomicReference<ApplicationAttachment>();
                        var lifetimes = new Object[1];

                        Thread owner = new Thread(() -> {
                            try {
                                ModuleHandle root = loaded.instantiate();
                                rootRef.set(root);
                                ApplicationAttachment attachment = ApplicationAttachment.open(
                                        root, compiled.context(), SessionOptions.defaults());
                                attachmentRef.set(attachment);
                                lifetimes[0] = attachment.registration().rootLifetime();
                                ready.countDown();
                                boolean running = true;
                                while (running) {
                                    String command = commands.poll(10, TimeUnit.SECONDS);
                                    require(command != null);
                                    switch (command) {
                                        case "poll" -> require(attachment.poll());
                                        case "poll-empty" -> {
                                            require(!attachment.poll());
                                            require(!attachment.registration().controller().hasLiveWork());
                                            evidence.add("idle");
                                        }
                                        case "sync" -> evidence.add(attachment.submit("sync.lyra", "(readCount)"));
                                        case "scratch" -> evidence.add(attachment.submit("scratch.lyra", "let scratch :I32 = 1"));
                                        case "check-scratch" -> evidence.add(attachment.submit("check-scratch.lyra", "scratch"));
                                        case "close" -> {
                                            attachment.close();
                                            evidence.add("closed");
                                        }
                                        case "reopen" -> {
                                            attachment = ApplicationAttachment.open(
                                                    root, compiled.context(), SessionOptions.defaults());
                                            attachmentRef.set(attachment);
                                            require(attachment.registration().rootLifetime() == lifetimes[0]);
                                            evidence.add("reopened");
                                        }
                                        case "exit" -> {
                                            attachment.close();
                                            root.close();
                                            loaded.close();
                                            evidence.add("exited");
                                            running = false;
                                        }
                                        default -> throw new AssertionError("unknown command: " + command);
                                    }
                                }
                            } catch (Throwable problem) {
                                failure.set(problem);
                                ready.countDown();
                            }
                        }, "attachment-owner");
                        owner.start();
                        require(ready.await(10, TimeUnit.SECONDS));
                        ApplicationAttachment attachment = attachmentRef.get();

                        // 1. Explicit open/poll: one owner-dispatched operation,
                        // never queued behind a busy request.
                        DispatchedEvaluation dispatched = attachment.submitDispatch("poll.lyra", "count := 42");
                        require(!dispatched.isDone());
                        DispatchedEvaluation busy = attachment.submitDispatch("busy.lyra", "count := 7");
                        require(busy.isDone());
                        require(busy.result().orElseThrow() instanceof EvaluationResult.Busy);
                        boolean wrongThread = false;
                        try { attachment.poll(); } catch (LyraThreadException expected) { wrongThread = true; }
                        require(wrongThread);
                        commands.add("poll");
                        EvaluationResult outcome = dispatched.awaitResult();
                        require(outcome instanceof EvaluationResult.Success);
                        require(outcome.evaluationId().equals(dispatched.evaluationId()));
                        require(!dispatched.cancel());
                        require(!attachment.cancel(dispatched.evaluationId()));
                        commands.add("sync");
                        EvaluationResult read = (EvaluationResult) evidence.poll(10, TimeUnit.SECONDS);
                        require(read instanceof EvaluationResult.Success);
                        require(scalar((EvaluationResult.Success) read).equals("42"));
                        commands.add("poll-empty");
                        require("idle".equals(evidence.poll(10, TimeUnit.SECONDS)));

                        // 2. Identity-specific cancellation of an infinite
                        // dispatch, then clean reuse with no leaked lease.
                        DispatchedEvaluation spin = attachment.submitDispatch("spin.lyra",
                                "let spin :Fn<;I32> = (=> || (spin)) (spin)");
                        commands.add("poll");
                        Thread.sleep(200);
                        require(spin.cancel());
                        EvaluationResult cancelled = spin.awaitResult();
                        require(cancelled instanceof EvaluationResult.Cancelled);
                        require(cancelled.evaluationId().equals(spin.evaluationId()));
                        commands.add("poll-empty");
                        require("idle".equals(evidence.poll(10, TimeUnit.SECONDS)));
                        DispatchedEvaluation after = attachment.submitDispatch("after.lyra", "count := 43");
                        require(!after.isDone());
                        commands.add("poll");
                        require(after.awaitResult() instanceof EvaluationResult.Success);
                        require(!after.cancel());

                        // 3. Explicit close rejects late work; reopen reuses the
                        // retained root-lifetime domain with a fresh workspace.
                        commands.add("scratch");
                        require(((EvaluationResult) evidence.poll(10, TimeUnit.SECONDS))
                                instanceof EvaluationResult.Success);
                        commands.add("close");
                        require("closed".equals(evidence.poll(10, TimeUnit.SECONDS)));
                        DispatchedEvaluation late = attachment.submitDispatch("late.lyra", "count := 1");
                        require(late.isDone());
                        require(late.result().orElseThrow() instanceof EvaluationResult.Closed);
                        commands.add("reopen");
                        require("reopened".equals(evidence.poll(10, TimeUnit.SECONDS)));
                        ApplicationAttachment reopened = attachmentRef.get();
                        require(reopened != attachment);
                        DispatchedEvaluation probe = reopened.submitDispatch("probe.lyra", "(readCount)");
                        commands.add("poll");
                        require(scalar((EvaluationResult.Success) probe.awaitResult()).equals("43"));
                        commands.add("check-scratch");
                        require(((EvaluationResult) evidence.poll(10, TimeUnit.SECONDS))
                                instanceof EvaluationResult.CompilationFailure);

                        // 4. Teardown runs on the original owner without an
                        // application executor or leaked worker threads.
                        commands.add("exit");
                        require("exited".equals(evidence.poll(10, TimeUnit.SECONDS)));
                        owner.join(15_000);
                        require(!owner.isAlive());
                        if (failure.get() != null) throw new AssertionError(failure.get());
                    }
                }
                """);
        String classpath = String.join(java.io.File.pathSeparator, List.of(
                location(LyraSession.class), location(LyraCompiler.class), location(LyraRuntime.class)));
        run(List.of(tool("javac"), "-cp", classpath, "-d", directory.toString(), source.toString()),
                "javac");
        run(List.of(tool("java"), "-Xverify:all",
                        "-cp", directory + java.io.File.pathSeparator + classpath, "AttachmentConsumer"),
                "consumer");
    }

    private void run(List<String> command, String label) throws Exception {
        Path log = directory.resolve(label + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                    label + " did not terminate:\n" + (Files.exists(log) ? Files.readString(log) : ""));
            assertEquals(0, process.exitValue(),
                    Files.exists(log) ? Files.readString(log) : label + " failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String tool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
