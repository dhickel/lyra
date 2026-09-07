package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.runtime.ArtifactProfile;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootTypeRegistrationTest {
    private static final String SOURCE = "let hidden :I32 = 99\n"
            + "let @pub @mut count :I32 = 1\n"
            + "let @pub readCount :Fn<;I32> = (=> | | count)\n"
            + "let @pub @mut selected :Fn<I32;I32> = (=> |value| (+ value 1))\n"
            + "let @pub replacement :Fn<I32;I32> = (=> |value| (+ value 2))\n"
            + "let @pub @mut values :Array<I32> = Array<I32>[1 2]\n"
            + "let @pub alias :Array<I32> = values\n"
            + "let @pub readFirst :Fn<;I32> = (=> | | values[0])\n"
            + "let @pub pair :Tuple<I32,String> = Tuple[1 \"one\"]\n"
            + "let @pub nested :Tuple<Fn<;I32>,Tuple<I32,String>> = "
            + "Tuple[(=> | | 7) Tuple[2 \"two\"]]\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n";

    @Test
    void registrationUsesTheExactOpenRootAccessors() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        try {
            RootTypeRegistration.Binding count = registration.requireBinding("count");
            assertEquals(1, count.getter().invoke());
            count.setter().orElseThrow().invoke(9);
            assertEquals(9, count.getter().invoke());
            assertEquals(9, registration.requireBinding("readCount").invocation().invoke());
            RootTypeRegistration.Binding selected = registration.requireBinding("selected");
            RootTypeRegistration.Binding replacement = registration.requireBinding("replacement");
            assertEquals(4, selected.invocation().invoke(3));
            Object replacementValue = replacement.functionValue().orElseThrow().invoke();
            selected.setter().orElseThrow().invoke(replacementValue);
            assertEquals(5, selected.invocation().invoke(3));
            assertEquals(replacementValue, replacement.functionValue().orElseThrow().invoke());
            int[] values = (int[]) registration.requireBinding("values").getter().invoke();
            assertEquals(2, values.length);
            values[0] = 8;
            assertEquals(8, registration.requireBinding("readFirst").invocation().invoke());
            int[] alias = (int[]) registration.requireBinding("alias").getter().invoke();
            assertEquals(8, alias[0]);
            assertTrue(registration.requireBinding("alias").setter().isEmpty());
            Object pair = registration.requireBinding("pair").getter().invoke();
            assertEquals(1, pair.getClass().getMethod("$lyra$get$0").invoke(pair));
            Object nested = registration.requireBinding("nested").getter().invoke();
            Object nestedFunction = nested.getClass().getMethod("$lyra$get$0").invoke(nested);
            Object nestedTuple = nested.getClass().getMethod("$lyra$get$1").invoke(nested);
            Class<?> nestedFunctionInterface = nestedFunction.getClass().getInterfaces()[0];
            assertEquals(7, nestedFunctionInterface.getMethod("invoke").invoke(nestedFunction));
            assertEquals(2, nestedTuple.getClass().getMethod("$lyra$get$0").invoke(nestedTuple));
            assertEquals(5, registration.structuralTypes().size());
            assertTrue(registration.binding("hidden").isEmpty());
            AtomicBoolean serviced = new AtomicBoolean();
            var dispatch = registration.dispatch(() -> serviced.set(true));
            assertFalse(serviced.get());
            assertTrue(registration.poll());
            assertEquals(io.mindspice.lyra.runtime.LyraOwnerController.DispatchStatus.COMPLETED,
                    dispatch.status());
            assertEquals(11, registration.requireBinding("add").invocation().invoke(5, 6));
            assertEquals(ArtifactProfile.ATTACHABLE, registration.metadata().artifactProfile());
            assertTrue(serviced.get());
        } finally {
            registration.close();
            root.close();
            loaded.close();
        }
    }

    @Test
    void serviceCloseLeavesRootOpenAndReopeningReusesTheRootLifetime() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        RootTypeRegistration first = LyraRuntime.registerRoot(root);
        var lifetime = first.rootLifetime();
        var retainedGetter = first.requireBinding("count").getter();
        first.close();
        try {
            assertTrue(first.isClosed());
            assertThrows(LyraClosedException.class, first::bindings);
            assertFalse(root.isClosed());
            RootTypeRegistration reopened = LyraRuntime.registerRoot(root);
            try {
                assertSame(lifetime, reopened.rootLifetime());
                reopened.requireBinding("count").setter().orElseThrow().invoke(12);
                assertEquals(12, reopened.requireBinding("count").getter().invoke());
            } finally {
                reopened.close();
            }
        } finally {
            if (!root.isClosed()) root.close();
            assertTrue(lifetime.isClosed());
            assertThrows(LyraClosedException.class, retainedGetter::invoke);
            loaded.close();
        }
    }

    @Test
    void normalRootsAndDuplicateRegistrationsAreRejected() {
        CompileResult.Success normal = (CompileResult.Success) LyraCompiler.compile(
                CompileRequest.source("main.lyra", SOURCE));
        var normalLoaded = LyraRuntime.load(normal.artifact());
        var normalRoot = normalLoaded.instantiate();
        assertThrows(LyraLinkException.class, () -> LyraRuntime.registerRoot(normalRoot));
        normalRoot.close();
        normalLoaded.close();

        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        var first = LyraRuntime.registerRoot(root);
        try {
            assertThrows(RuntimeException.class, () -> LyraRuntime.registerRoot(root));
        } finally {
            first.close();
            root.close();
            loaded.close();
        }
    }

    @Test
    void rootsFromOneLoadedArtifactHaveIndependentLifetimes() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var firstRoot = loaded.instantiate();
        var secondRoot = loaded.instantiate();
        var first = LyraRuntime.registerRoot(firstRoot);
        var second = LyraRuntime.registerRoot(secondRoot);
        try {
            assertNotSame(first.rootLifetime(), second.rootLifetime());
            first.requireBinding("count").setter().orElseThrow().invoke(17);
            assertEquals(17, first.requireBinding("count").getter().invoke());
            assertEquals(1, second.requireBinding("count").getter().invoke());
            first.close();
            firstRoot.close();
            assertFalse(second.rootLifetime().isClosed());
            second.requireBinding("count").setter().orElseThrow().invoke(23);
            assertEquals(23, second.requireBinding("count").getter().invoke());
        } finally {
            if (!first.isClosed()) first.close();
            if (!firstRoot.isClosed()) firstRoot.close();
            second.close();
            secondRoot.close();
            loaded.close();
        }
    }

    @Test
    void separateLoadedRootsHaveIndependentStructuralDomains() throws Throwable {
        CompileResult.Success result = attachable();
        var firstLoaded = LyraRuntime.load(result.artifact());
        var secondLoaded = LyraRuntime.load(result.artifact());
        var firstRoot = firstLoaded.instantiate();
        var secondRoot = secondLoaded.instantiate();
        var first = LyraRuntime.registerRoot(firstRoot);
        var second = LyraRuntime.registerRoot(secondRoot);
        try {
            assertNotSame(first.rootLifetime(), second.rootLifetime());
            assertNotSame(first.structuralTypes().stream().findFirst().orElseThrow(),
                    second.structuralTypes().stream().findFirst().orElseThrow());
            first.requireBinding("count").setter().orElseThrow().invoke(17);
            assertEquals(1, second.requireBinding("count").getter().invoke());
        } finally {
            first.close();
            second.close();
            firstRoot.close();
            secondRoot.close();
            firstLoaded.close();
            secondLoaded.close();
        }
    }

    @Test
    void reExportedPublicsAreRegisteredWithOriginProvenanceAndPlainImportsStayPrivate()
            throws Throwable {
        CompileResult.Success result = (CompileResult.Success) LyraCompiler.compile(
                CompileRequest.builder()
                        .source("main.lyra",
                                "import @pub dep->{value as reexported}\n"
                                        + "import dep->{value}\n"
                                        + "let @pub @mut count :I32 = 1\n")
                        .resolver(io.mindspice.lyra.compiler.api.SourceResolver.memory(
                                io.mindspice.lyra.compiler.source.ResolvedSource.memory(
                                        "dep", java.net.URI.create("memory:dep.lyra"),
                                        "let @pub value :I32 = 41 let hidden :I32 = 9\n")))
                        .profile(CompileProfile.ATTACHABLE).build());
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        try {
            RootTypeRegistration.Binding reexported = registration.requireBinding("reexported");
            assertEquals(41, reexported.getter().invoke());
            assertTrue(reexported.metadata().declarationIdentity() >= 0);
            assertTrue(reexported.metadata().originDeclarationIdentity() >= 0);
            assertTrue(reexported.metadata().originDeclarationIdentity()
                    != reexported.metadata().declarationIdentity());
            assertTrue(registration.binding("value").isEmpty(),
                    "a plain import is not a public root export");
            assertTrue(registration.binding("hidden").isEmpty());
            assertTrue(registration.binding("dep").isEmpty());
        } finally {
            registration.close();
            root.close();
            loaded.close();
        }
    }

    @Test
    void immutableExportsHaveNoSetterAndMissingNamesAreRejected() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        try {
            RootTypeRegistration.Binding pair = registration.requireBinding("pair");
            assertTrue(pair.setter().isEmpty());
            assertFalse(pair.mutable());
            assertTrue(pair.metadata().bindingMutability()
                    == io.mindspice.lyra.runtime.BindingMutability.IMMUTABLE);
            assertThrows(IllegalArgumentException.class,
                    () -> registration.requireBinding("absent"));
            assertTrue(registration.binding("absent").isEmpty());
        } finally {
            registration.close();
            root.close();
            loaded.close();
        }
    }

    @Test
    void functionValueGettersExposeTheExactLiveClosure() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        try {
            RootTypeRegistration.Binding selected = registration.requireBinding("selected");
            RootTypeRegistration.Binding replacement = registration.requireBinding("replacement");
            Object original = selected.functionValue().orElseThrow().invoke();
            Class<?> fnInterface = original.getClass().getInterfaces()[0];
            assertEquals(4, (int) fnInterface.getMethod("invoke", int.class)
                    .invoke(original, 3));
            Object next = replacement.functionValue().orElseThrow().invoke();
            selected.setter().orElseThrow().invoke(next);
            Object live = selected.functionValue().orElseThrow().invoke();
            assertEquals(5, (int) fnInterface.getMethod("invoke", int.class)
                    .invoke(live, 3));
            assertEquals(5, (int) selected.invocation().invoke(3));
        } finally {
            registration.close();
            root.close();
            loaded.close();
        }
    }

    @Test
    void hooksStayInertWithoutAnActiveRegistration() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        // No service is registered yet: an attachable root runs as an ordinary
        // module and exposes no controller dispatch surface.
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        io.mindspice.lyra.runtime.ModuleLifecycle lifecycle = registration.lifecycle();
        try {
            // With a registered service the safe-point hook polls pending work.
            java.util.concurrent.atomic.AtomicBoolean serviced =
                    new java.util.concurrent.atomic.AtomicBoolean();
            var dispatch = registration.dispatch(() -> serviced.set(true));
            lifecycle.applicationSafePoint();
            assertTrue(serviced.get());
            assertEquals(io.mindspice.lyra.runtime.LyraOwnerController.DispatchStatus.COMPLETED,
                    dispatch.status());
            registration.controller().close();
            assertDoesNotThrow(lifecycle::applicationSafePoint);
        } finally {
            registration.close();
        }
        // After service close the same hook surface is inert again: it neither
        // dispatches nor manufactures a controller on the owner thread, and
        // the externally owned root remains open.
        lifecycle.applicationSafePoint();
        assertFalse(root.isClosed());
        root.close();
        loaded.close();
    }

    @Test
    void registrationIsOwnerThreadConfinedAndRejectsClosedRoots() throws Throwable {
        CompileResult.Success result = attachable();
        var loaded = LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                registration.binding("count");
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        other.start();
        other.join();
        assertInstanceOf(io.mindspice.lyra.runtime.LyraThreadException.class, failure.get());
        registration.close();
        root.close();
        assertThrows(io.mindspice.lyra.runtime.LyraClosedException.class,
                () -> LyraRuntime.registerRoot(root));
        loaded.close();
    }

    private static CompileResult.Success attachable() {
        return (CompileResult.Success) LyraCompiler.compile(CompileRequest.builder()
                .source("main.lyra", SOURCE).profile(CompileProfile.ATTACHABLE).build());
    }
}
