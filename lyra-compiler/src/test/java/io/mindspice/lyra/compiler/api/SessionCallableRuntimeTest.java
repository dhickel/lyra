package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical callable bridge tests, not evidence for named REPL callable persistence.
 * Sources compile independently; Java supplies values to exact generated signatures.
 * Object adaptation below is confined to this test's tooling boundary.
 */
class SessionCallableRuntimeTest {
    @Test
    void initializedGenerationsShareExactClosuresAndTheirOriginalMutableCells() throws Throwable {
        var producer = compile("producer.lyra", """
                let @mut count :I32 = 1
                let @pub add :Fn<I32;I32> = (=> |n| { count := (+ count n) count })
                let @pub read :Fn<;I32> = (=> || count)
                let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f n| (f n))
                """);
        var consumer = compile("consumer.lyra", """
                let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f n| (f n))
                let @pub wrap :Fn<Fn<I32;I32>;Fn<I32;I32>> = (=> |f| (=> |n| (f n)))
                let @pub keep :Fn<Fn<I32;I32>;Fn<I32;I32>> = (=> |f| f)
                """);
        var change = assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                new SessionCompileRequest("change.lyra", "count := 40", scalarSnapshot(producer))));
        try (var domain = new SessionStorageDomain();
             var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object add = export(ma, "add", "Fn<I32;I32>").functionValue();
            var apply = export(mb, "apply", "Fn<Fn<I32;I32>,I32;I32>");
            assertSame(export(ma, "add", "Fn<I32;I32>").functionValueHandle().type().returnType(),
                    apply.methodType().parameterType(0));
            assertEquals(3, callInt(apply.handle(), add, 2));
            Object kept = callObject(export(mb, "keep", "Fn<Fn<I32;I32>;Fn<I32;I32>>").handle(), add);
            assertSame(add, kept, "the bridge does not wrap or copy a function value");
            Object wrapped = callObject(export(mb, "wrap", "Fn<Fn<I32;I32>;Fn<I32;I32>>").handle(), add);
            assertNotSame(add, wrapped);
            assertEquals(7, callInt(apply.handle(), wrapped, 4));
            assertEquals(12, callInt(export(ma, "apply", "Fn<Fn<I32;I32>,I32;I32>").handle(), wrapped, 5),
                    "an older generated consumer can invoke a newer closure retaining the original producer");

            var required = requirement(producer, "count");
            var binding = domain.register(ma, required);
            domain.commit(0, List.of(binding));
            try (var c = LyraRuntime.loadSubmission(change.artifact(), LoadOptions.defaults(),
                    domain.link(change.artifact(), 1, List.of(required), List.of(binding)));
                 var mc = c.instantiate()) {
                assertEquals(42, callInt(apply.handle(), wrapped, 2),
                        "ordinary assignment from new bytecode updates the original captured cell");
                assertEquals(42, (int) export(ma, "read", "Fn<;I32>").handle().invokeExact());
            }
        }
    }

    @Test
    void laterLexicalReplacementAndInitializerFailureDoNotRetargetOrCloseOldCaptures() throws Throwable {
        var producer = compile("retained-producer.lyra", """
                let @mut count :I32 = 1
                let @pub read :Fn<;I32> = (=> || count)
                """);
        var consumer = compile("retained-consumer.lyra",
                "let @pub apply :Fn<Fn<;I32>;I32> = (=> |f| (f))");
        var replaced = assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                new SessionCompileRequest("replacement.lyra", "let count :String = \"different type\"", scalarSnapshot(producer))));
        var failed = assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                new SessionCompileRequest("failed-initializer.lyra", "count := 9 (+ count 2147483647)", scalarSnapshot(producer))));
        try (var domain = new SessionStorageDomain(); var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object read = export(ma, "read", "Fn<;I32>").functionValue();
            var invoke = export(mb, "apply", "Fn<Fn<;I32>;I32>").handle()
                    .asType(MethodType.methodType(int.class, Object.class));
            var required = requirement(producer, "count");
            var binding = domain.register(ma, required);
            domain.commit(0, List.of(binding));
            try (var c = LyraRuntime.loadSubmission(replaced.artifact(), LoadOptions.defaults(),
                    domain.link(replaced.artifact(), 1, List.of(required), List.of(binding))); var mc = c.instantiate()) {
                assertEquals("String", replaced.stagedSnapshot().binding("count").orElseThrow().type().canonicalSpelling());
                assertEquals(1, (int) invoke.invokeExact(read));
            }
            try (var c = LyraRuntime.loadSubmission(failed.artifact(), LoadOptions.defaults(),
                    domain.link(failed.artifact(), 1, List.of(required), List.of(binding)))) {
                assertThrows(LyraInitializationException.class, c::instantiate);
            }
            assertEquals(9, (int) invoke.invokeExact(read),
                    "a failed new initializer preserves its write to the old producer cell");
            assertTrue(((LyraClosure) read).isValid());
        }
    }

    @Test
    void callableArraysTuplesAndAggregateSignaturesUseSharedPhysicalTypes() throws Throwable {
        var producer = compile("aggregate-producer.lyra", """
                let one :Fn<I32;I32> = (=> |n| (+ n 1))
                let two :Fn<I32;I32> = (=> |n| (+ n 2))
                let @pub identity :Fn<Array<I32>;Array<I32>> = (=> |items| items)
                Tuple[one Array[one two]]
                """);
        var consumer = compile("aggregate-consumer.lyra", """
                let @pub apply :Fn<Tuple<Fn<I32;I32>,Array<Fn<I32;I32>>>,I32;I32> =
                    (=> |pair n| (+ (pair:.0 n) (pair:.1[1] n)))
                let @pub data :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> =
                    (=> |f items| (f items))
                """);
        try (var domain = new SessionStorageDomain(); var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object pair = LyraRuntime.readSubmissionResult(ma,
                    LyraType.parse("Tuple<Fn<I32;I32>,Array<Fn<I32;I32>>>"));
            var apply = export(mb, "apply", "Fn<Tuple<Fn<I32;I32>,Array<Fn<I32;I32>>>,I32;I32>");
            assertSame(pair.getClass(), apply.methodType().parameterType(0));
            assertEquals(13, callInt(apply.handle(), pair, 5));
            Object identity = export(ma, "identity", "Fn<Array<I32>;Array<I32>>").functionValue();
            int[] items = {1, 2};
            var handle = export(mb, "data", "Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>>").handle()
                    .asType(MethodType.methodType(int[].class, Object.class, int[].class));
            assertSame(items, (int[]) handle.invokeExact(identity, items));
        }
    }

    @Test
    void runtimeFailurePreservesCompletedCellEffectsAndProducerSourceFrames() throws Throwable {
        String producerSource = """
                let @mut count :I32 = 1
                let @pub fail :Fn<;I32> = (=> || { count := 9 (+ count 2147483647) })
                let @pub read :Fn<;I32> = (=> || count)
                """;
        var producer = compile("producer-failure.lyra", producerSource);
        var consumer = compile("consumer-failure.lyra",
                "let @pub apply :Fn<Fn<;I32>;I32> = (=> |f| (f))");
        try (var domain = new SessionStorageDomain(); var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object fail = export(ma, "fail", "Fn<;I32>").functionValue();
            var invoke = export(mb, "apply", "Fn<Fn<;I32>;I32>").handle()
                    .asType(MethodType.methodType(int.class, Object.class));
            var failure = assertThrows(LyraArithmeticException.class, () -> { int ignored = (int) invoke.invokeExact(fail); });
            assertTrue(failure.frames().stream().anyMatch(frame ->
                    frame.span().sourceId().value().contains("producer-failure")
                            && frame.span().startOffset() == producerSource.indexOf("(+ count")
                            && frame.span().endOffset() == producerSource.indexOf("(+ count") + "(+ count 2147483647)".length()),
                    failure.render());
            assertTrue(failure.frames().stream().anyMatch(frame -> frame.span().sourceId().value().contains("consumer-failure")), failure.render());
            assertEquals(9, (int) export(ma, "read", "Fn<;I32>").handle().invokeExact());
            assertTrue(((LyraClosure) fail).isValid(), "ordinary call failure does not retire an initialized producer");
        }
    }

    @Test
    void cancellationTraversesTheProducerSafePointsAndLeavesBothGenerationsUsable() throws Throwable {
        var producer = compile("producer-cancel.lyra", """
                let @mut count :I32 = 0
                let spin :Fn<;I32> = (=> || (spin))
                let @pub loop :Fn<;I32> = (=> || { count := 7 (spin) })
                let @pub read :Fn<;I32> = (=> || count)
                """);
        var consumer = compile("consumer-cancel.lyra",
                "let @pub apply :Fn<Fn<;I32>;I32> = (=> |f| (f))");
        try (var domain = new SessionStorageDomain(); var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object loop = export(ma, "loop", "Fn<;I32>").functionValue();
            var invoke = export(mb, "apply", "Fn<Fn<;I32>;I32>").handle()
                    .asType(MethodType.methodType(int.class, Object.class));
            Thread owner = Thread.currentThread();
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            try (var lease = domain.beginEvaluation()) {
                Thread cancel = new Thread(() -> {
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        boolean executing = false;
                        while (System.nanoTime() < deadline) {
                            executing = java.util.Arrays.stream(owner.getStackTrace()).anyMatch(frame ->
                                    frame.getClassName().equals(loop.getClass().getName()) && frame.getMethodName().equals("invoke"));
                            if (executing) break;
                            Thread.sleep(1);
                        }
                        if (!executing) throw new AssertionError("producer did not reach its generated loop");
                    } catch (Throwable failure) { controlFailure.set(failure); }
                    finally { lease.requestCancellation(); }
                }, "callable-cancel");
                cancel.start();
                try {
                    assertThrows(LyraCancellationException.class, () -> { int ignored = (int) invoke.invokeExact(loop); });
                } finally { cancel.join(11000); }
                assertFalse(cancel.isAlive());
                assertNull(controlFailure.get());
            }
            assertEquals(7, (int) export(ma, "read", "Fn<;I32>").handle().invokeExact());
            Object read = export(ma, "read", "Fn<;I32>").functionValue();
            assertEquals(7, (int) invoke.invokeExact(read));
        }
    }

    @Test
    void resetCloseWrongThreadAndForeignSamsRemainFailClosed() throws Throwable {
        var producer = compile("producer-boundaries.lyra", "let @pub add :Fn<I32;I32> = (=> |n| (+ n 1))");
        var consumer = compile("consumer-boundaries.lyra",
                "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f n| (f n))");
        try (var domain = new SessionStorageDomain(); var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object add = export(ma, "add", "Fn<I32;I32>").functionValue();
            var apply = export(mb, "apply", "Fn<Fn<I32;I32>,I32;I32>");
            Class<?> function = apply.methodType().parameterType(0);
            Object sam = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
                    (ignored, method, arguments) -> 123);
            assertThrows(LyraLinkException.class, () -> callInt(apply.handle(), sam, 1));
            var wrongThread = new AtomicReference<Throwable>();
            var producerThread = new AtomicReference<Throwable>();
            Thread thread = new Thread(() -> {
                try { callInt(apply.handle(), add, 1); }
                catch (Throwable failure) { wrongThread.set(failure); }
                try { ((LyraClosure) add).checkInvocation(); }
                catch (Throwable failure) { producerThread.set(failure); }
            });
            thread.start(); thread.join();
            assertInstanceOf(LyraThreadException.class, wrongThread.get());
            assertInstanceOf(LyraThreadException.class, producerThread.get());
            assertEquals(2, callInt(apply.handle(), add, 1));
            domain.reset();
            assertFalse(((LyraClosure) add).isValid());
            assertThrows(LyraLinkException.class, () -> callInt(apply.handle(), add, 1));
        }
        try (var domain = new SessionStorageDomain(); var a = load(domain, producer); var b = load(domain, consumer);
             var ma = a.instantiate(); var mb = b.instantiate()) {
            Object add = export(ma, "add", "Fn<I32;I32>").functionValue();
            var apply = export(mb, "apply", "Fn<Fn<I32;I32>,I32;I32>");
            ma.close();
            assertFalse(((LyraClosure) add).isValid());
            assertThrows(LyraClosedException.class, () -> callInt(apply.handle(), add, 1));
            domain.reset();
            assertThrows(LyraClosedException.class, ((LyraClosure) add)::checkInvocation,
                    "module closure keeps precedence over later epoch retirement");
        }
    }

    @Test
    void compilerCallableProofIsRequiredAndDoesNotReplaceRuntimeCertification() {
        var producer = compile("guard-producer.lyra", "let f :Fn<I32;I32> = (=> |n| n)");
        assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                new SessionCompileRequest("guard-consumer.lyra", "(f 1)", producer.stagedSnapshot())));
        var snapshot = producer.stagedSnapshot();
        var typeOnly = new SessionSnapshot(snapshot.revision(), snapshot.bindings(), snapshot.imports(),
                snapshot.pinnedModules(), snapshot.allocator());
        var result = LyraCompiler.compileSession(new SessionCompileRequest("guard-consumer.lyra", "(f 1)", typeOnly));
        assertInstanceOf(SessionCompileResult.Failure.class, result);
        assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                result.diagnostics().getFirst().code());
    }

    private static SessionSnapshot scalarSnapshot(SessionCompileResult.Success producer) {
        var snapshot = producer.stagedSnapshot();
        return new SessionSnapshot(snapshot.revision(), java.util.Map.of("count", snapshot.binding("count").orElseThrow()),
                snapshot.pinnedModules(), snapshot.allocator());
    }

    private static SessionCompileResult.Success compile(String label, String source) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(label, source, SessionSnapshot.empty()));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }

    private static LoadedArtifact load(SessionStorageDomain domain, SessionCompileResult.Success compiled) {
        return LyraRuntime.loadSubmission(compiled.artifact(), LoadOptions.defaults(),
                domain.link(compiled.artifact(), 0, List.of(), List.of()));
    }

    private static ExportHandle export(ModuleHandle module, String name, String signature) {
        return module.export(name, LyraSignature.parse(signature));
    }

    private static int callInt(MethodHandle handle, Object value, int argument) throws Throwable {
        return (int) handle.asType(MethodType.methodType(int.class, Object.class, int.class)).invokeExact(value, argument);
    }

    private static Object callObject(MethodHandle handle, Object value) throws Throwable {
        return (Object) handle.asType(MethodType.methodType(Object.class, Object.class)).invokeExact(value);
    }

    private static SessionStorageDomain.Requirement requirement(SessionCompileResult.Success compiled, String name) {
        var binding = compiled.stagedSnapshot().binding(name).orElseThrow();
        return new SessionStorageDomain.Requirement(binding.declarationId().ordinal(),
                binding.storageIdentity().map(value -> value.ordinal()).orElse(-1L), name,
                binding.type().canonicalSpelling(), binding.allowsRebinding());
    }
}
