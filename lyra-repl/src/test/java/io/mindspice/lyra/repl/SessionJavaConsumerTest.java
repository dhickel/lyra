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

class SessionJavaConsumerTest {
    @TempDir Path directory;

    @Test
    void externalJavaConsumerProvesPersistenceCancellationBusyClosedAndVerification() throws Exception {
        Path source = directory.resolve("Consumer.java");
        Files.writeString(source, """
                import io.mindspice.lyra.repl.*;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.*;
                public final class Consumer {
                    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
                    static EvaluationResult.Success success(EvaluationResult result) {
                        if (!(result instanceof EvaluationResult.Success value)) throw new AssertionError(result);
                        return value;
                    }
                    public static void main(String[] args) throws Exception {
                        var sessionRef = new AtomicReference<LyraSession>();
                        var failure = new AtomicReference<Throwable>();
                        var ready = new CountDownLatch(1);
                        var id = EvaluationId.create();
                        Thread owner = new Thread(() -> {
                            try (var session = LyraSession.open()) {
                                success(session.submit("first", "let @mut count :I32 = 1 let @mut cancelledData :Array<Array<I32>> = Array<Array<I32>>[Array<I32>[1]]"));
                                sessionRef.set(session);
                                ready.countDown();
                                var request = new EvaluationRequest(id, new SessionRevision(1), EvaluationSource.of("loop",
                                    "let loop :Fn<;I32> = (=> || { cancelledData[0] := Array<I32>[7] count := 7 (loop) }) (loop)"));
                                require(session.submit(request) instanceof EvaluationResult.Cancelled);
                                var result = success(session.submit("last", "count"));
                                require(result.value().orElseThrow().canonicalType().equals("I32"));
                                require(((ValueSnapshot.Scalar) result.value().orElseThrow().data()).value().equals("7"));
                                require(session.submit("missing", "loop") instanceof EvaluationResult.CompilationFailure);
                                var cancelledData = success(session.submit("cancelled-data", "cancelledData[0][0]"));
                                require(((ValueSnapshot.Scalar) cancelledData.value().orElseThrow().data()).value().equals("7"));
                                success(session.submit("data", "let @mut data :Array<Tuple<I32,String>> = Array<Tuple<I32,String>>[Tuple[1 \\\"one\\\"]]"));
                                success(session.submit("alias", "let alias = data"));
                                success(session.submit("write", "data[0] := Tuple[42 \\\"changed\\\"]"));
                                var aggregateResult = success(session.submit("read", "alias[0]:.0"));
                                require(((ValueSnapshot.Scalar) aggregateResult.value().orElseThrow().data()).value().equals("42"));
                                var old = success(session.submit("snapshot", "data")).value().orElseThrow();
                                session.reset();
                                require(session.submit("retired", "data") instanceof EvaluationResult.CompilationFailure);
                                require(old.data() instanceof ValueSnapshot.Aggregate);
                            } catch (Throwable problem) { failure.set(problem); ready.countDown(); }
                        }, "consumer-owner");
                        owner.setDaemon(true);
                        owner.start();
                        require(ready.await(10, TimeUnit.SECONDS));
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        boolean executing = false;
                        while (owner.isAlive() && System.nanoTime() < deadline) {
                            executing = java.util.Arrays.stream(owner.getStackTrace())
                                .anyMatch(frame -> frame.getClassName().contains("$lyra$closure$") && frame.getMethodName().equals("invoke"));
                            if (executing) break;
                            Thread.sleep(1);
                        }
                        require(executing);
                        require(sessionRef.get().submit("busy", "1") instanceof EvaluationResult.Busy);
                        require(sessionRef.get().cancel(id));
                        owner.join(10000);
                        require(!owner.isAlive());
                        if (failure.get() != null) throw new AssertionError(failure.get());
                        require(sessionRef.get().submit("closed", "1") instanceof EvaluationResult.Closed);
                    }
                }
                """);
        String classpath = String.join(java.io.File.pathSeparator, List.of(
                location(LyraSession.class), location(LyraCompiler.class), location(LyraRuntime.class)));
        run(List.of(tool("javac"), "-cp", classpath, "-d", directory.toString(), source.toString()), "javac");
        run(List.of(tool("java"), "-Xverify:all", "-cp", directory + java.io.File.pathSeparator + classpath, "Consumer"), "consumer");
    }

    private void run(List<String> command, String label) throws Exception {
        Path log = directory.resolve(label + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), label + " did not terminate");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
    private static String location(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    private static String tool(String name) { return Path.of(System.getProperty("java.home"), "bin", name).toString(); }
}
