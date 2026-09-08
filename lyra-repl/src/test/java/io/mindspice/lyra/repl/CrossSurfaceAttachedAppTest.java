package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 cross-surface conformance, surface 4: the live attached
 * application.  The corpus modules are borrowed or compiled as scratch
 * work against a real attachable root; application mutations before
 * evaluation and session mutations before resumed main/main-side calls
 * observe each other's exact storage, and root-held values survive
 * reset, service close and reopening until root close.
 */
class CrossSurfaceAttachedAppTest {
    private static final String ROOT_SOURCE = "import counter import values\n"
            + "let @pub @mut count :I32 = 1\n"
            + "let @pub @mut selected :Fn<I32;I32> = (=> |value| (+ value 1))\n"
            + "let @pub @mut items :Array<I32> = Array<I32>[1 2]\n"
            + "let @pub @mut pair :Tuple<I32,String> = Tuple[1 \"one\"]\n"
            + "let @pub readCount :Fn<;I32> = (=> | | count)\n"
            + "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n"
            + "let @pub boom :Fn<I32;I32> = (=> |x| (% 100 x))\n";

    private static AttachableCompileResult.Success compiledRoot(SourceResolver resolver) {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", ROOT_SOURCE)
                        .resolver(resolver)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    private static ApplicationAttachment open(AttachableCompileResult.Success compiled,
                                              ModuleHandle root,
                                              CrossSurfaceCorpus.Sources sources,
                                              java.io.ByteArrayOutputStream output) {
        return ApplicationAttachment.open(root, compiled.context(),
                CrossSurfaceCorpus.options(sources, output).build());
    }

    @Test
    void appAndSessionMutationsObserveTheSameExactRootStorage() throws Throwable {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root, sources, output)) {
            RootTypeRegistration registration = attachment.registration();
            // Application mutations before evaluation are the current state.
            registration.requireBinding("count").setter().orElseThrow().invoke(7);
            assertEquals("7", scalar(success(attachment, "count")));
            assertEquals("7", scalar(success(attachment, "(readCount)")));
            // Session mutations before resumed main/main-side calls write the
            // real application storage.
            success(attachment, "count := 41");
            assertEquals(41, registration.requireBinding("count").getter().invoke());
            // Callable replacement flows both directions through real
            // function-value instances.
            Object originalSelected = registration.requireBinding("selected")
                    .functionValue().orElseThrow().invoke();
            success(attachment, "selected := (=> :I32 |value :I32| (* value 10))");
            assertEquals(30, registration.requireBinding("selected").invocation().invoke(3));
            registration.requireBinding("selected").setter().orElseThrow()
                    .invoke(originalSelected);
            assertEquals("4", scalar(success(attachment, "(selected 3)")));
            // Root-level higher-order transfer with a session lambda.
            assertEquals("10", scalar(success(attachment,
                    "(apply (=> |value| (* value 5)) 2)")));
        } finally {
            if (!root.isClosed()) root.close();
            loaded.close();
        }
    }

    @Test
    void appOwnedCorpusModulesAreBorrowedWithOriginalStateAndRejectReload() throws Throwable {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root, sources, output)) {
            // The root initializer already ran the counter/values modules.
            assertEquals("counter-init\n", output.toString(StandardCharsets.UTF_8));
            // Duplicate borrows are idempotent and reuse the original state.
            success(attachment, "import counter import values");
            assertEquals("1", scalar(success(attachment, "counter->::bump[]")));
            assertEquals("2", scalar(success(attachment, "counter->::bump[]")));
            assertEquals("6", scalar(success(attachment, "values->::bump[]")));
            assertEquals("7", scalar(success(attachment, "(values->:.callables[0] 6)")));
            assertEquals("one", scalar(success(attachment, "values->:.pair:.1")));
            // Borrowed aggregate element mutation stays an ordinary
            // ownership diagnostic in the attached scope.
            var forbidden = assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "values->:.items[0] := 9"));
            assertEquals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                    forbidden.diagnostics().getFirst().code());
            // Application-owned modules are never reloadable.
            for (String target : new String[]{"counter", "values"}) {
                SessionCompileResult reloadAttempt = LyraCompiler.compileSession(
                        SessionCompileRequest.builder()
                                .source("reload.lyra", "")
                                .sourceId(io.mindspice.lyra.compiler.source.SourceId.path(
                                        "repl/reload-app.lyra"))
                                .snapshot(compiled.context().initialSnapshot())
                                .reloadModule(LogicalModuleId.parse(target))
                                .reloadImportAlias("__reloaded")
                                .build());
                var reloadFailure = assertInstanceOf(SessionCompileResult.Failure.class, reloadAttempt);
                assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                        reloadFailure.diagnostics().getFirst().code());
            }
        } finally {
            if (!root.isClosed()) root.close();
            loaded.close();
        }
    }

    @Test
    void scratchImportsAndHigherOrderCorpusWorkInsideTheAttachedScope() throws Throwable {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root, sources, output)) {
            // higher/rec/cyclea/errors are scratch modules compiled by the
            // session against the attached workspace.
            assertEquals("21", scalar(success(attachment,
                    "import higher (higher->:.apply (=> |x| (* x 3)) 7)")));
            assertEquals("18", scalar(success(attachment,
                    "let triple :Fn<I32;I32> = (=> :I32 |x| (* x 3)) "
                            + "((higher->:.compose triple triple) 2)")));
            assertEquals("720", scalar(success(attachment, "import rec rec->::fact[6]")));
            assertEquals("0", scalar(success(attachment,
                    "import cyclea cyclea->::ping[3]")));
            // The post-cycle submission exercises the certificate-only SCC
            // coverage repair inside the attached pipeline as well.
            assertEquals("0", scalar(success(attachment, "(cyclea->:.ping 2)")));
            // std->io writes land on the host output.
            success(attachment, "import std->io io->::println[\"attached-output\"]");
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("attached-output\n"));
            // Delayed imported and root diagnostics keep exact spans.
            var imported = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    attachment.submit("late.lyra", "import errors errors->::late[]"));
            assertTrue(imported.frames().stream().anyMatch(frame ->
                            frame.excerpt().orElse("").contains("(% 11 x)")
                                    && frame.span().startOffset()
                                    == CrossSurfaceCorpus.ERRORS_V1.indexOf("(%")),
                    imported.toString());
            var rootFailure = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    attachment.submit("boom.lyra", "(boom 0)"));
            assertTrue(rootFailure.frames().stream().anyMatch(frame ->
                            frame.excerpt().orElse("").contains("(% 100 x)")
                                    && frame.span().startOffset()
                                    == ROOT_SOURCE.indexOf("(%")),
                    rootFailure.toString());
        } finally {
            if (!root.isClosed()) root.close();
            loaded.close();
        }
    }

    @Test
    void rootHeldValuesSurviveResetServiceCloseAndReopenWithOneStructuralDomain()
            throws Throwable {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        ApplicationAttachment first = open(compiled, root, sources, output);
        RootTypeRegistration registration = first.registration();
        var lifetime = registration.rootLifetime();
        Object oldPair = registration.requireBinding("pair").getter().invoke();
        Object oldSelected = registration.requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        success(first, "pair := Tuple[7 \"seven\"]");
        success(first, "selected := (=> :I32 |value :I32| (* value 3))");
        success(first, "let scratch :I32 = 1");
        Object newPair = registration.requireBinding("pair").getter().invoke();
        Object newSelected = registration.requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        first.close();
        try {
            // Old and new structural values interoperate on the shared
            // root-lifetime domain even though the service is closed.
            assertSame(oldPair.getClass(), newPair.getClass());
            assertSame(oldSelected.getClass().getInterfaces()[0],
                    newSelected.getClass().getInterfaces()[0]);
            assertEquals(1, tupleComponent(oldPair, 0));
            assertEquals(7, tupleComponent(newPair, 0));
            assertEquals(9, invokeFunction(newSelected, 3));
            try (ApplicationAttachment reopened = open(compiled, root, sources, output)) {
                assertSame(lifetime, reopened.registration().rootLifetime());
                assertEquals("7", scalar(success(reopened, "pair:.0")));
                assertEquals("9", scalar(success(reopened, "(selected 3)")));
                assertInstanceOf(EvaluationResult.CompilationFailure.class,
                        reopened.submit("bad.lyra", "scratch"));
                success(reopened, "count := 44");
                success(reopened,
                        "selected := (=> :I32 |value :I32| (% 88 value))");
            }
            try (ApplicationAttachment reopenedAgain = open(compiled, root, sources, output)) {
                EvaluationResult.RuntimeFailure retainedFailure = assertInstanceOf(
                        EvaluationResult.RuntimeFailure.class,
                        reopenedAgain.submit("retained-after-reopen.lyra", "(selected 0)"));
                assertTrue(retainedFailure.frames().stream().anyMatch(frame ->
                                frame.excerpt().orElse("").contains("(% 88 value)")),
                        retainedFailure.toString());
            }
        } finally {
            root.close();
            assertTrue(lifetime.isClosed());
            assertThrows(LyraClosedException.class, () -> invokeFunction(newSelected, 3));
            assertInstanceOf(EvaluationResult.Closed.class,
                    first.submit("late.lyra", "count"));
            loaded.close();
        }
    }

    @Test
    void resetPreservesRootMutationsAndRootHeldSessionProducers() throws Throwable {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root, sources, output)) {
            RootTypeRegistration registration = attachment.registration();
            success(attachment, "let @mut cell :I32 = 21\n"
                    + "let reader :Fn<;I32> = (=> | | cell)\n"
                    + "selected := (=> :I32 |value :I32| (* (reader) value))\n");
            Object stored = registration.requireBinding("selected")
                    .functionValue().orElseThrow().invoke();
            attachment.reset();
            assertEquals(21 * 2, invokeFunction(stored, 2));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "(reader)"));
            EvaluationResult.RuntimeFailure rootFailure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class,
                    attachment.submit("boom-after-reset.lyra", "(boom 0)"));
            assertTrue(rootFailure.frames().stream().anyMatch(frame ->
                            frame.excerpt().orElse("").contains("(% 100 x)")),
                    rootFailure.toString());
            success(attachment, "let @mut cell :I32 = 31\n"
                    + "let reader :Fn<;I32> = (=> | | cell)\n");
            assertEquals(21 * 4, invokeFunction(stored, 4));
            assertEquals("31", scalar(success(attachment, "(reader)")));

            success(attachment,
                    "selected := (=> :I32 |value :I32| (% 77 value))");
            attachment.reset();
            EvaluationResult.RuntimeFailure retainedScratchFailure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class,
                    attachment.submit("retained-after-reset.lyra", "(selected 0)"));
            assertTrue(retainedScratchFailure.frames().stream().anyMatch(frame ->
                            frame.excerpt().orElse("").contains("(% 77 value)")),
                    retainedScratchFailure.toString());
            assertEquals(21 * 4, invokeFunction(stored, 4));
            assertEquals(1, registration.requireBinding("count").getter().invoke());
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void attachedRootSourceContextIsPreflightedBeforeServiceAdmission() {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        try {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> ApplicationAttachment.open(root, compiled.context(),
                            CrossSurfaceCorpus.options(sources, output)
                                    .maxSourceRecords(1).build()));
            assertTrue(rejected.getMessage().contains("root source context"),
                    rejected.getMessage());
            try (ApplicationAttachment attachment = open(compiled, root, sources, output)) {
                assertEquals("1", scalar(success(attachment, "count")));
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void duplicateSimultaneousServiceIsRejectedAndIndependentRootsStayIndependent()
            throws Throwable {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        AttachableCompileResult.Success compiled = compiledRoot(sources);
        var loaded = LyraRuntime.load(compiled.artifact(),
                new LoadOptions(CrossSurfaceCorpus.io(output)));
        var root = loaded.instantiate();
        try (ApplicationAttachment first = open(compiled, root, sources, output)) {
            assertThrows(io.mindspice.lyra.runtime.LyraLifecycleException.class,
                    () -> ApplicationAttachment.open(root, compiled.context(),
                            CrossSurfaceCorpus.options(sources, output).build()));
            var secondRoot = loaded.instantiate();
            try (ApplicationAttachment second = open(compiled, secondRoot, sources, output)) {
                success(first, "count := 100");
                assertEquals("1", scalar(success(second, "count")));
                success(second, "count := 200");
                assertEquals("100", scalar(success(first, "count")));
            } finally {
                secondRoot.close();
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    private static int invokeFunction(Object closure, int value) throws Throwable {
        Class<?> interfaceType = closure.getClass().getInterfaces()[0];
        try {
            return (int) interfaceType.getMethod("invoke", int.class).invoke(closure, value);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object tupleComponent(Object tuple, int index) throws Throwable {
        try {
            return tuple.getClass().getMethod("$lyra$get$" + index).invoke(tuple);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static EvaluationResult.Success success(ApplicationAttachment attachment, String source) {
        EvaluationResult result = attachment.submit("corpus-attached.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
