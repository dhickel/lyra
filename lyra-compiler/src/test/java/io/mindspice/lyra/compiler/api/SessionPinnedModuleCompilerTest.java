package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.backend.jvm.JvmBytecodeArtifact;
import io.mindspice.lyra.compiler.session.PinnedModule;
import io.mindspice.lyra.compiler.session.SessionModuleEnvironment;
import io.mindspice.lyra.compiler.session.SessionExecutionPlan;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.RevisionOptions;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceResolver;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class SessionPinnedModuleCompilerTest {
    @Test
    void retainedSelectiveImportUsesTheCapturedSourceWithoutResolverAccess() {
        LogicalModuleId logical = LogicalModuleId.parse("virtual->math");
        ResolvedSource source = ResolvedSource.memory(
                logical,
                URI.create("memory://session/virtual/math.lyra"),
                "let @pub value :I32 = 2".getBytes(StandardCharsets.UTF_8));
        AtomicInteger resolverCalls = new AtomicInteger();
        SourceResolver resolver = requested -> {
            resolverCalls.incrementAndGet();
            return requested.equals(logical) ? Optional.of(source) : Optional.empty();
        };

        SessionCompileResult.Success first = success(SessionCompileRequest.builder()
                .source("first.lyra", "import virtual->math->{value}")
                .resolver(resolver)
                .snapshot(SessionSnapshot.empty())
                .build());
        assertEquals(1, resolverCalls.get());
        SessionExecutionPlan.ModuleWork firstWork = first.executionPlan().module(
                source.sourceId().isUri()
                        ? io.mindspice.lyra.compiler.source.ModuleId.uri(source.sourceId().asUri())
                        : io.mindspice.lyra.compiler.source.ModuleId.path(source.sourceId().value()))
                .orElseThrow();
        assertEquals(SessionExecutionPlan.WorkKind.NEW, firstWork.kind());
        assertTrue(firstWork.hasInitializerWork());

        SessionCompileResult.Success second = success(SessionCompileRequest.builder()
                .source("second.lyra", "value")
                .snapshot(first.stagedSnapshot())
                .build());

        assertEquals(1, resolverCalls.get(), "pin-first discovery must not query the resolver");
        var retained = second.stagedSnapshot().moduleEnvironment().module(logical).orElseThrow();
        var original = first.stagedSnapshot().moduleEnvironment().module(logical).orElseThrow();
        assertEquals(original.generationId(), retained.generationId());
        assertEquals(original.producerId(), retained.producerId());
        SessionExecutionPlan.ModuleWork secondWork = second.executionPlan().module(retained.moduleId())
                .orElseThrow();
        assertEquals(SessionExecutionPlan.WorkKind.REUSED, secondWork.kind());
        assertTrue(!secondWork.hasInitializerWork());
        assertEquals(first.stagedSnapshot().pinnedModules().get(logical).revision(),
                second.moduleGraph().module(retained.moduleId()).orElseThrow().revision());
    }

    @Test
    void retainedUnaliasedNamespaceRemainsQualifiedAcrossSubmissions() {
        LogicalModuleId logical = LogicalModuleId.parse("game->math->vector");
        ResolvedSource source = ResolvedSource.memory(logical,
                URI.create("memory://phase02/game/math/vector.lyra"),
                "let @pub value :I32 = 2".getBytes(StandardCharsets.UTF_8));
        SessionCompileResult.Success first = success(SessionCompileRequest.builder()
                .source("first.lyra", "import game->math->vector")
                .resolver(SourceResolver.single(source)).build());

        SessionCompileResult.Success second = success(SessionCompileRequest.builder()
                .source("second.lyra", "game->math->vector->:.value")
                .snapshot(first.stagedSnapshot()).build());

        assertEquals("I32", second.typedIr().rootModule().submissionResult()
                .orElseThrow().type().canonicalSpelling());
        assertTrue(second.resolvedGraph().references().stream()
                .anyMatch(reference -> reference.targetModule().isPresent()
                        && reference.targetExport().isEmpty()));
        assertEquals(logical, first.snapshot().imports().get("vector").logicalModule());
    }

    @Test
    void retainedIntrinsicNamespaceRemainsQualifiedAcrossSubmissions() {
        SessionCompileResult.Success first = success(SessionCompileRequest.builder()
                .source("first.lyra", "import std->io").build());
        SessionCompileResult.Success second = success(SessionCompileRequest.builder()
                .source("second.lyra", "std->io->::println[\"retained\"]")
                .snapshot(first.stagedSnapshot()).build());
        assertEquals("Unit", second.typedIr().rootModule().submissionResult()
                .orElseThrow().type().canonicalSpelling());
    }

    @Test
    void retainedNamespaceAliasRemainsAQualifiedTypedLink() {
        LogicalModuleId logical = LogicalModuleId.parse("virtual->math");
        ResolvedSource source = ResolvedSource.memory(
                logical,
                URI.create("memory://session/virtual/math.lyra"),
                "let @pub value :I32 = 2".getBytes(StandardCharsets.UTF_8));
        SessionCompileResult.Success first = success(SessionCompileRequest.builder()
                .source("first.lyra", "import virtual->math")
                .resolver(SourceResolver.single(source))
                .build());

        SessionCompileResult.Success second = success(SessionCompileRequest.builder()
                .source("second.lyra", "math->:.value")
                .snapshot(first.stagedSnapshot())
                .build());

        assertEquals("I32", second.typedIr().rootModule().submissionResult()
                .orElseThrow().type().canonicalSpelling());
        assertTrue(second.resolvedGraph().references().stream()
                .anyMatch(reference -> reference.targetModule().isPresent()
                        && reference.targetExport().isEmpty()));
    }

    @Test
    void preservesEveryProducerIdentityAndEmitsOnlyNewCodeWithExactExternalReads() {
        var library = source("library", """
                let @pub create :Fn<I32;Fn<;I32>> = (=> |initial| {
                    let @mut count :I32 = initial
                    (=> || { count := (+ count 1) count })
                })
                let @pub next :Fn<;I32> = (create 2)
                let @pub values :Array<I32> = Array<I32>[1 2]
                """);
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import library")
                .resolver(SourceResolver.single(library)).build());
        var second = success(SessionCompileRequest.builder().source("second.lyra", """
                let fresh :Array<I32> = Array<I32>[4]
                let f :Fn<;I32> = library->:.next
                (f)
                """).snapshot(first.snapshot()).build());
        var original = first.snapshot().environment().module(library.logicalModule()).orElseThrow();
        var id = original.moduleId();
        assertSame(first.moduleGraph().module(id).orElseThrow().program(), second.moduleGraph().module(id).orElseThrow().program());
        assertSame(original.resolvedModule(), second.resolvedGraph().module(id).orElseThrow());
        assertSame(original.typedModule(), second.typedGraph().module(id).orElseThrow());
        for (var declaration : original.resolvedModule().declarations()) {
            assertSame(first.resolvedGraph().declaration(declaration).orElseThrow(),
                    second.resolvedGraph().declaration(declaration).orElseThrow());
        }
        for (var reference : original.resolvedModule().references()) {
            assertSame(first.resolvedGraph().reference(reference).orElseThrow(), second.resolvedGraph().reference(reference).orElseThrow());
            assertEquals(first.typedGraph().flowSiteId(reference), second.typedGraph().flowSiteId(reference));
        }
        for (var lambda : original.resolvedModule().lambdas()) {
            assertSame(first.resolvedGraph().lambda(lambda).orElseThrow(), second.resolvedGraph().lambda(lambda).orElseThrow());
            assertSame(first.typedGraph().semanticFlowFacts().callableSummaries().summary(lambda).orElseThrow(),
                    second.typedGraph().semanticFlowFacts().callableSummaries().summary(lambda).orElseThrow());
        }
        for (var capture : first.resolvedGraph().captures()) {
            assertSame(capture, second.resolvedGraph().capture(capture.id()).orElseThrow());
            assertEquals(first.typedGraph().flowSiteId(capture.id()), second.typedGraph().flowSiteId(capture.id()));
        }
        first.typedGraph().expressions().stream().filter(value -> value.span().sourceId().equals(id.sourceId()))
                .forEach(expression -> assertEquals(first.typedGraph().flowSiteId(expression), second.typedGraph().flowSiteId(expression)));
        assertFalse(second.typedIr().module(id).isPresent());
        assertTrue(second.typedIr().lambdas().isEmpty());
        assertFalse(second.typedIr().initializationOrder().contains(id));
        var emitted = JvmBytecodeArtifact.emit(second.typedIr(), "verify.pinned").optionalValue().orElseThrow();
        assertTrue(emitted.emittedMethods().stream().noneMatch(method -> method.moduleId().equals(id)),
                "reused producers must have no newly emitted initialization or callable bodies");
        assertFalse(second.typedIr().sessionExecution().orElseThrow().externalAccesses().isEmpty());
        assertTrue(second.typedIr().imports().stream().allMatch(value -> value.producerContract().isPresent()));
        second.artifact().classes().values().forEach(bytes -> assertTrue(ClassFile.of().verify(bytes).isEmpty()));
        // Phase 02 does not replace the runtime's whole-graph facade/link guard.
        assertThrows(io.mindspice.lyra.runtime.LyraCompatibilityException.class,
                () -> io.mindspice.lyra.runtime.LyraRuntime.load(second.artifact()));
        var classes = second.artifact().classes();
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        classes.keySet().forEach(name -> assertDoesNotThrow(() -> loader.loadClass(name).getDeclaredMethods()));
    }

    @Test
    void applicationOwnedProducerIsBorrowedWithoutInitializerOrCallableEmission() {
        var library = source("application", "let @pub value :Fn<;I32> = (=> || 42)");
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import application")
                .resolver(SourceResolver.single(library)).build());
        var env = first.snapshot().environment();
        var original = env.module(library.logicalModule()).orElseThrow();
        var borrowed = new SessionModuleEnvironment.ModuleRecord(original.logicalModule(), original.moduleId(),
                original.source(), original.revision(), original.generationId(), original.producerId(),
                SessionModuleEnvironment.Ownership.APPLICATION, original.resolvedModule(), original.typedModule(),
                original.exports(), original.imports(), original.dependencies(), original.initializerDeclarations(),
                original.finalState(), original.attemptedState(), original.callableSummaries(), original.producerGraph(),
                original.sourceRoots(), original.revisionOptions());
        var environment = new SessionModuleEnvironment(List.of(borrowed), env.sourceRoots(), env.revisionOptions(),
                env.sourceInventory(), env.resolvedInputs(), env.resolutionTopology(), env.resolvedGraph(), env.typedGraph(), env.flowFacts());
        var second = success(SessionCompileRequest.builder().source("second.lyra", "application->::value[]")
                .snapshot(first.snapshot().withModuleEnvironment(environment)).build());
        var work = second.executionPlan().module(original.moduleId()).orElseThrow();
        assertEquals(SessionExecutionPlan.WorkKind.BORROWED, work.kind());
        assertEquals(original.producerId(), work.producerId());
        assertTrue(work.initializerDeclarations().isEmpty());
        assertSame(original.typedModule(), second.typedGraph().module(original.moduleId()).orElseThrow());
        assertTrue(second.typedIr().module(original.moduleId()).isEmpty());
        assertTrue(second.typedIr().lambdas().isEmpty());
    }

    @Test
    void attemptedSnapshotNeverPublishesNewModulesAsReusableAndRetryAllocatesFreshProducers() {
        var library = source("retry", "let @pub answer :I32 = 42");
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import retry")
                .resolver(SourceResolver.single(library)).build());
        assertTrue(first.attemptedSnapshot().environment().modules().isEmpty());
        assertTrue(first.attemptedSnapshot().pinnedModules().isEmpty());
        assertTrue(first.attemptedSnapshot().imports().isEmpty());
        var retry = success(SessionCompileRequest.builder().source("retry-submission.lyra", "import retry")
                .snapshot(first.attemptedSnapshot()).resolver(SourceResolver.single(library)).build());
        var original = first.snapshot().environment().module(library.logicalModule()).orElseThrow();
        var replacement = retry.snapshot().environment().module(library.logicalModule()).orElseThrow();
        assertNotEquals(original.producerId(), replacement.producerId());
        assertNotEquals(original.generationId(), replacement.generationId());
        assertNotEquals(original.resolvedModule().declarations(), replacement.resolvedModule().declarations());
        assertEquals(SessionExecutionPlan.WorkKind.NEW, retry.executionPlan().module(replacement.moduleId()).orElseThrow().kind());
        assertTrue(retry.typedIr().module(replacement.moduleId()).isPresent());
        var afterCommitted = success(SessionCompileRequest.builder().source("third.lyra", "retry->:.answer")
                .snapshot(first.snapshot()).build());
        assertSame(first.snapshot().environment(), afterCommitted.attemptedSnapshot().environment());
    }

    @Test
    void pinsValidateCapturedSourceAndOriginalOptionsInsteadOfCurrentOptions() {
        var source = source("options", "let @pub value :I32 = 1");
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import options")
                .revisionOptions(Map.of("test.revision", "old")).resolver(SourceResolver.single(source)).build());
        var pin = first.snapshot().pinnedModules().get(source.logicalModule());
        assertEquals(ModuleRevision.compute(pin.snapshot(), pin.revisionOptions()), pin.revision());
        assertThrows(IllegalArgumentException.class, () -> new PinnedModule(pin.logicalModule(), pin.snapshot(), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PinnedModule(pin.logicalModule(), pin.snapshot(), pin.revision(), RevisionOptions.empty()));
        var second = success(SessionCompileRequest.builder().source("second.lyra", "options->:.value")
                .snapshot(first.snapshot()).revisionOptions(Map.of("test.revision", "new"))
                .resolver(request -> { fail("a retained source must bypass resolver access"); return Optional.empty(); }).build());
        assertEquals(pin, second.snapshot().pinnedModules().get(pin.logicalModule()));
        assertEquals(first.snapshot().environment().module(pin.logicalModule()).orElseThrow().revisionOptions(),
                second.snapshot().environment().module(pin.logicalModule()).orElseThrow().revisionOptions());
    }

    @Test
    void executionOrderIsCanonicalUniqueAndIncludesEffectOnlyModules() {
        var z = source("z", "import std->io io->::println[\"z\"]");
        var a = source("a", "import z let @pub value :I32 = 1");
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import a")
                .resolver(id -> id.equals(a.logicalModule()) ? Optional.of(a) : id.equals(z.logicalModule()) ? Optional.of(z) : Optional.empty()).build());
        var plan = first.executionPlan();
        assertEquals(2, plan.initializationOrder().size());
        var duplicate = new ArrayList<>(plan.initializationOrder());
        duplicate.add(duplicate.getFirst());
        assertThrows(IllegalArgumentException.class, () -> new SessionExecutionPlan(plan.modules(), duplicate, first.typedGraph()));
        var reversed = plan.initializationOrder().reversed();
        assertThrows(IllegalArgumentException.class, () -> new SessionExecutionPlan(plan.modules(), reversed, first.typedGraph()));
        assertThrows(IllegalArgumentException.class, () -> new SessionExecutionPlan(plan.modules(), plan.initializationOrder(), plan.topologyRevision()));
        assertEquals(1, plan.borrowedModules().size());
    }

    @Test
    void compatibilityResultConstructorPreservesTheSealedPlan() {
        var source = source("compat", "let @pub value :I32 = 1");
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import compat")
                .resolver(SourceResolver.single(source)).build());
        var copy = new SessionCompileResult.Success(first.baseRevision(), first.revision(), first.stagedSnapshot(),
                first.attemptedSnapshot(), first.artifact(), first.moduleGraph(), first.resolvedGraph(), first.typedGraph(),
                first.typedIr(), first.stagedDeclarations(), first.stagedImports(), first.diagnostics());
        assertSame(first.executionPlan(), copy.executionPlan());
        assertFalse(copy.executionPlan().initializationOrder().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SessionCompileResult.Success(first.baseRevision(), first.revision(), first.stagedSnapshot(),
                first.attemptedSnapshot(), first.artifact(), first.moduleGraph(), first.resolvedGraph(), first.typedGraph(),
                first.typedIr(), first.stagedDeclarations(), first.stagedImports(), SessionExecutionPlan.empty(first.moduleGraph().revision()), first.diagnostics()));
    }

    @Test
    void environmentRejectsMissingSourcesInputsAndMixedCanonicalFacts() {
        var source = source("coverage", "let @pub value :I32 = 1");
        var first = success(SessionCompileRequest.builder().source("first.lyra", "import coverage")
                .resolver(SourceResolver.single(source)).build());
        var env = first.snapshot().environment();
        assertThrows(IllegalArgumentException.class, () -> new SessionModuleEnvironment(List.of(), env.sourceRoots(), env.revisionOptions(),
                env.sourceInventory(), env.resolvedInputs(), env.resolutionTopology(), env.resolvedGraph(), env.typedGraph(), env.flowFacts()));
        assertThrows(IllegalArgumentException.class, () -> new SessionModuleEnvironment(env.modules(), env.sourceRoots(), env.revisionOptions(),
                List.of(), env.resolvedInputs(), env.resolutionTopology(), env.resolvedGraph(), env.typedGraph(), env.flowFacts()));
        assertThrows(IllegalArgumentException.class, () -> new SessionModuleEnvironment(env.modules(), env.sourceRoots(), env.revisionOptions(),
                env.sourceInventory(), List.of(), env.resolutionTopology(), env.resolvedGraph(), env.typedGraph(), env.flowFacts()));
        var other = success(SessionCompileRequest.builder().source("different.lyra", "2").build()).snapshot().environment();
        assertThrows(IllegalArgumentException.class, () -> new SessionModuleEnvironment(env.modules(), env.sourceRoots(), env.revisionOptions(),
                env.sourceInventory(), env.resolvedInputs(), env.resolutionTopology(), env.resolvedGraph(), env.typedGraph(), other.flowFacts()));
        var copy = new SessionModuleEnvironment(env.modules(), env.sourceRoots(), env.revisionOptions(), env.sourceInventory(),
                env.resolvedInputs(), env.resolutionTopology(), env.resolvedGraph(), env.typedGraph(), env.flowFacts());
        assertEquals(env, copy);
        assertEquals(env.hashCode(), copy.hashCode());
        assertNotEquals(env, other);
        var secondRequest = SessionCompileRequest.builder().source("second.lyra", "coverage->:.value").snapshot(first.snapshot()).build();
        var second = success(secondRequest);
        var reused = second.snapshot().environment();
        assertThrows(IllegalArgumentException.class, () -> new SessionModuleEnvironment(reused.modules(), reused.sourceRoots(), reused.revisionOptions(),
                reused.sourceInventory().stream().filter(value -> !value.sourceId().equals(first.moduleGraph().root().sourceId())).toList(),
                reused.resolvedInputs(), reused.resolutionTopology(), reused.resolvedGraph(), reused.typedGraph(), reused.flowFacts()));
        assertEquals(reused, SessionModuleEnvironment.from(second.moduleGraph(), second.resolvedGraph(), second.typedGraph(),
                secondRequest.sourceConfiguration(), reused.modules()));
    }

    private static ResolvedSource source(String name, String text) {
        return ResolvedSource.memory(LogicalModuleId.parse(name), URI.create("memory://phase02/" + name + ".lyra"), text.getBytes(StandardCharsets.UTF_8));
    }

    private static SessionCompileResult.Success success(SessionCompileRequest request) {
        var result = LyraCompiler.compileSession(request);
        return assertInstanceOf(SessionCompileResult.Success.class, result, () -> result.diagnostics().toString());
    }
}
