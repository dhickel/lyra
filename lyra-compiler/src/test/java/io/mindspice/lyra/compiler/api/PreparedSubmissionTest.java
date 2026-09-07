package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PreparedSubmissionTest {
    @Test
    void preparationDoesNotExecuteAndEveryBindingHasItsOwnInitializationGuard() throws Throwable {
        var compiled = compile("guarded.lyra", """
                let @pub ready :I32 = 42
                let @pub read :Fn<;I32> = (=> || ready)
                let zero :I32 = 0
                (% 1 zero)
                let @pub later :I32 = 99
                """);
        try (var domain = new SessionStorageDomain(); var loaded = load(domain, compiled);
             var module = LyraRuntime.prepareSubmission(loaded)) {
            assertThrows(LyraInitializationException.class, () -> domain.register(module, requirement(compiled, "ready")));
            assertThrows(LyraLifecycleException.class, () -> LyraRuntime.readSubmissionResult(module, LyraType.parse("Unit")));
            assertThrows(LyraArithmeticException.class, () -> LyraRuntime.executeSubmission(module));
            assertEquals(42, (int) module.export("read", LyraSignature.parse("Fn<;I32>")).handle().invokeExact());
            assertThrows(LyraInitializationException.class,
                    () -> domain.register(module, requirement(compiled, "later")),
                    "a later unexecuted scalar must never expose its JVM default");
            assertThrows(LyraLifecycleException.class, () -> LyraRuntime.executeSubmission(module));
            assertThrows(LyraInitializationException.class,
                    () -> domain.register(module, requirement(compiled, "ready")),
                    "completed bindings retain escaped values, not failed namespace publication");
            assertFalse(module.isClosed());
        }
        try (var ordinary = LyraRuntime.load(compiled.artifact())) {
            assertThrows(LyraInitializationException.class, ordinary::instantiate,
                    "ordinary construction still fails terminally and returns no partial facade");
        }
    }

    @Test
    void exactCallableStorageAuthenticatesReplacementsAndRetainsFailedExecutionValues() throws Throwable {
        var original = compile("original.lyra", """
                let @pub @mut selected :Fn<I32;I32> = (=> |n| (+ n 1))
                let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f n| (f n))
                """);
        var failed = compile("retained.lyra", """
                let @mut count :I32 = 40
                let @pub next :Fn<I32;I32> = (=> |n| { count := (+ count n) count })
                let zero :I32 = 0
                (% 1 zero)
                """);
        try (var domain = new SessionStorageDomain(); var a = load(domain, original); var b = load(domain, failed);
             var ma = LyraRuntime.prepareSubmission(a); var mb = LyraRuntime.prepareSubmission(b)) {
            LyraRuntime.executeSubmission(ma);
            assertThrows(LyraArithmeticException.class, () -> LyraRuntime.executeSubmission(mb));
            Object next = mb.export("next", LyraSignature.parse("Fn<I32;I32>")).functionValue();
            var binding = domain.register(ma, requirement(original, "selected"));
            domain.commit(0, List.of(binding));
            var apply = ma.export("apply", LyraSignature.parse("Fn<Fn<I32;I32>,I32;I32>"));
            var invoke = apply.handle().asType(MethodType.methodType(int.class, Object.class, int.class));
            assertEquals(42, (int) invoke.invokeExact(next, 2));
            assertTrue(((LyraClosure) next).isValid());
            Object foreign = Proxy.newProxyInstance(apply.methodType().parameterType(0).getClassLoader(),
                    new Class<?>[]{apply.methodType().parameterType(0)}, (proxy, method, args) -> 123);
            assertThrows(LyraLinkException.class, () -> { int ignored = (int) invoke.invokeExact(foreign, 1); });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { LyraRuntime.executeSubmission(mb); }
                catch (Throwable caught) { failure.set(caught); }
            });
            thread.start(); thread.join();
            assertInstanceOf(LyraThreadException.class, failure.get());
            domain.reset();
            assertFalse(((LyraClosure) next).isValid());
            assertThrows(LyraLinkException.class, () -> { int ignored = (int) invoke.invokeExact(next, 1); });
        }
    }

    private static SessionCompileResult.Success compile(String label, String source) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(label, source, SessionSnapshot.empty()));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }

    private static LoadedArtifact load(SessionStorageDomain domain, SessionCompileResult.Success compiled) {
        return LyraRuntime.loadSubmission(compiled.artifact(), LoadOptions.defaults(),
                domain.link(compiled.artifact(), 0, List.of(), List.of()));
    }

    private static SessionStorageDomain.Requirement requirement(SessionCompileResult.Success compiled, String name) {
        var binding = compiled.stagedSnapshot().binding(name).orElseThrow();
        return new SessionStorageDomain.Requirement(binding.declarationId().ordinal(),
                binding.storageIdentity().map(value -> value.ordinal()).orElse(-1L), name,
                binding.type().canonicalSpelling(), binding.allowsRebinding());
    }
}
