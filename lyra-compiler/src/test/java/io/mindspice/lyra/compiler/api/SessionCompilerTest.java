package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.SessionRevision;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.runtime.LyraRuntime;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionCompilerTest {
    @Test
    void nonConstructorSelfAggregateMutationsRemainStructuredResolutionFailures() {
        List<String> sources = List.of("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub mutate :Fn<;Unit> = (=> || { self:.values[0] := 7 })
                }
                """, """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut mutate :Fn<;Unit> = (=> || {})
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| {
                    box:.mutate := (=> || { self:.values[0] := 7 })
                })
                """);
        for (int index = 0; index < sources.size(); index++) {
            String source = sources.get(index);
            SessionCompileResult.Failure failure = assertInstanceOf(
                    SessionCompileResult.Failure.class,
                    LyraCompiler.compileSession(new SessionCompileRequest(
                            "non-constructor-self-" + index + ".lyra", source,
                            SessionSnapshot.empty())));
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code());
            int target = source.indexOf("self:.values[0]");
            assertEquals(SourceSpan.of(SourceId.path(
                            "non-constructor-self-" + index + ".lyra"),
                    target, target + "self:.values[0]".length()),
                    failure.diagnostics().getFirst().primarySpan());
        }
    }

    /**
     * Session artifacts emit one shared structural route-delegate class per
     * callable nominal member; ordinary AOT artifacts keep their exact class
     * inventory with no delegate classes, since only session generations need
     * occurrence-scoped cross-artifact read evidence.
     */
    @Test
    void callableNominalMembersEmitSessionOnlyRouteDelegateClasses() {
        String source = """
                import std->io
                class Holder { let @pub printer :Fn<String;Unit> = io->:.println }
                """;
        var session = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "delegate-inventory.lyra", source, SessionSnapshot.empty())));
        Map<String, byte[]> sessionClasses = session.artifact().classes();
        List<String> delegates = sessionClasses.keySet().stream()
                .filter(name -> name.contains(".$lyra$delegate$")).toList();
        assertEquals(1, delegates.size(),
                "one callable schema field must emit exactly one route delegate");
        String nominalHash = session.artifact().metadata().nominalSchemas().schemas().getFirst()
                .type().id().stableHash();
        assertTrue(delegates.getFirst().endsWith(".$lyra$delegate$" + nominalHash + "$0"));
        var delegate = ClassFile.of().parse(sessionClasses.get(delegates.getFirst()));
        assertEquals("io/mindspice/lyra/runtime/LyraNominalMemberDelegate",
                delegate.superclass().orElseThrow().asInternalName());
        assertEquals(1, delegate.interfaces().size());
        assertEquals("(Ljava/lang/Object;)V", delegate.methods().stream()
                .filter(method -> method.methodName().equalsString("<init>"))
                .findFirst().orElseThrow().methodType().stringValue());
        assertEquals("(Ljava/lang/String;)V", delegate.methods().stream()
                .filter(method -> method.methodName().equalsString("invoke"))
                .findFirst().orElseThrow().methodType().stringValue());
        assertTrue(sessionClasses.keySet().stream().anyMatch(name ->
                        name.endsWith(".$lyra$nominal$" + nominalHash)),
                "session artifact must emit the exact nominal representation");
        assertTrue(sessionClasses.keySet().stream()
                        .anyMatch(name -> name.contains(".$lyra$fn$")),
                "session artifact must emit the callable member's function interface");

        var ordinary = LyraCompiler.compile(CompileRequest.source(
                "delegate-inventory-aot.lyra", source));
        var artifact = assertInstanceOf(CompileResult.Success.class, ordinary,
                ordinary::toString).artifact();
        assertTrue(artifact.classes().keySet().stream()
                        .noneMatch(name -> name.contains(".$lyra$delegate$")),
                "ordinary AOT artifacts must not emit route delegate classes");
    }

    @Test
    void emptySnapshotCompilesARealExpressionArtifact() {
        SessionCompileResult result = LyraCompiler.compileSession(
                new SessionCompileRequest("expression.lyra", "42", SessionSnapshot.empty()));
        SessionCompileResult.Success success = assertInstanceOf(
                SessionCompileResult.Success.class, result);

        assertEquals(SessionRevision.initial(), success.baseRevision());
        assertEquals(new SessionRevision(1), success.revision());
        assertEquals(success.revision(), success.stagedSnapshot().revision());
        assertTrue(success.stagedSnapshot().bindings().isEmpty());
        assertTrue(success.stagedDeclarations().isEmpty());
        assertTrue(success.resolvedGraph().declarations().isEmpty());
        assertTrue(success.typedIr().isValidated());
        assertNotNull(success.artifact());
        assertFalse(success.artifact().classes().isEmpty());

        try (var loaded = LyraRuntime.load(success.artifact());
             var module = loaded.instantiate()) {
            assertEquals(1, module.metadata().modules().size());
        }
    }

    @Test
    void emptySnapshotStagesPublicDeclarationMetadataAndContinuesIdentityAllocation() {
        SessionCompileResult.Success first = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "first.lyra", "let @pub answer :I32 = 42", SessionSnapshot.empty())));
        DeclarationId binding = first.stagedSnapshot().binding("answer").orElseThrow().declarationId();
        assertEquals(PrimitiveType.I32,
                first.stagedSnapshot().binding("answer").orElseThrow().type());
        assertTrue(first.stagedDeclarations().contains(binding));
        assertEquals(binding, first.resolvedGraph().exports().getFirst().declarationId());
        assertEquals(first.typedGraph().allocator(), first.stagedSnapshot().allocator());

        SessionCompileResult.Success second = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "second.lyra", "let next :I32 = 7", first.stagedSnapshot())));
        DeclarationId secondId = second.stagedDeclarations().getFirst();
        assertTrue(secondId.ordinal() > binding.ordinal());
        assertEquals(new SessionRevision(1), second.baseRevision());
        assertEquals(new SessionRevision(2), second.revision());
        assertEquals(first.stagedSnapshot().binding("answer").orElseThrow(),
                second.stagedSnapshot().binding("answer").orElseThrow());
    }

    @Test
    void explicitSourceIdentitiesSeparateRepeatedOriginsAcrossStagedRevisions() {
        String text = "let value :I32 = 1";
        for (Optional<URI> uri : java.util.List.of(Optional.<URI>empty(),
                Optional.of(URI.create("file:///workspace/repeated.lyra")))) {
            EvaluationSource source = new EvaluationSource(
                    new SourceOrigin("repeated.lyra", uri, Optional.of(4L),
                            20, 20 + text.length()), text);
            SourceId firstId = SourceId.path("repl/first.lyra");
            SourceId secondId = SourceId.path("repl/second.lyra");
            SessionSnapshot empty = SessionSnapshot.empty();
            SessionCompileResult.Success first = assertInstanceOf(
                    SessionCompileResult.Success.class,
                    LyraCompiler.compileSession(SessionCompileRequest.builder()
                            .source(source).sourceId(firstId).snapshot(empty).build()));
            SessionCompileResult.Success second = assertInstanceOf(
                    SessionCompileResult.Success.class,
                    LyraCompiler.compileSession(SessionCompileRequest.builder()
                            .source(source).sourceId(secondId)
                            .snapshot(first.stagedSnapshot()).build()));

            assertEquals(firstId, first.moduleGraph().rootModule().sourceId());
            assertEquals(secondId, second.moduleGraph().rootModule().sourceId());
            assertEquals(firstId, first.resolvedGraph().declarations().getFirst()
                    .nameSpan().sourceId());
            assertEquals(secondId, second.resolvedGraph().declarations().getFirst()
                    .nameSpan().sourceId());
            assertEquals("path:" + firstId.value(),
                    first.artifact().metadata().rootModuleId().canonicalSpelling());
            assertEquals("path:" + secondId.value(),
                    second.artifact().metadata().rootModuleId().canonicalSpelling());
            assertEquals(SessionRevision.initial(), empty.revision());
            assertEquals(IdentityAllocator.initial(), empty.allocator());
            assertEquals(empty.revision(), first.baseRevision());
            assertEquals(new SessionRevision(1), first.revision());
            assertEquals(first.revision(), first.stagedSnapshot().revision());
            assertEquals(first.revision(), second.baseRevision());
            assertEquals(new SessionRevision(2), second.revision());
            assertEquals(second.revision(), second.stagedSnapshot().revision());
            assertTrue(second.stagedDeclarations().getFirst().ordinal()
                    > first.stagedDeclarations().getFirst().ordinal());
            assertEquals(first.typedGraph().allocator(), first.stagedSnapshot().allocator());
            assertEquals(second.typedGraph().allocator(), second.stagedSnapshot().allocator());
        }
    }

    @Test
    void requestSourceIdentityIsImmutableAndParticipatesInValueEquality() {
        EvaluationSource source = EvaluationSource.of("selection", "42");
        SourceId firstId = SourceId.path("repl/first.lyra");
        SourceId secondId = SourceId.path("repl/second.lyra");
        SessionCompileRequest.Builder builder = SessionCompileRequest.builder()
                .source(source).sourceId(firstId);
        SessionCompileRequest first = builder.build();
        SessionCompileRequest second = builder.sourceId(secondId).build();
        SessionCompileRequest equal = SessionCompileRequest.builder()
                .sourceId(SourceId.of(firstId.value())).source(source).build();

        assertEquals(firstId, first.sourceId());
        assertEquals(secondId, second.sourceId());
        assertEquals(source, first.source());
        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, second);
        assertThrows(NullPointerException.class, () -> builder.sourceId(null));
    }

    @Test
    void omittedSourceIdentityPreservesLegacyOriginDefaults() {
        URI uri = URI.create("file:///workspace/legacy.lyra");
        EvaluationSource uriSource = new EvaluationSource(
                new SourceOrigin("selection", Optional.of(uri), Optional.empty(), 20, 22), "42");
        SessionCompileRequest.Builder builder = SessionCompileRequest.builder()
                .source("legacy.lyra", "42");
        SessionCompileRequest labelRequest = builder.build();
        SessionCompileRequest uriRequest = builder.source(uriSource).build();
        SessionCompileRequest anonymousRequest = builder.source("/absolute/label.lyra", "42").build();

        assertEquals(SourceId.path("legacy.lyra"), labelRequest.sourceId());
        assertEquals(new SessionCompileRequest("legacy.lyra", "42", SessionSnapshot.empty()),
                labelRequest);
        assertEquals(SourceId.uri(uri), uriRequest.sourceId());
        assertEquals(new SessionCompileRequest(uriSource, SessionSnapshot.empty()), uriRequest);
        assertEquals(SourceId.path("repl/submission-anonymous.lyra"), anonymousRequest.sourceId());
    }

    @Test
    void compiledExternalReferencesStillRequireSeparateLiveStorageAuthority() {
        SessionCompileResult.Success first = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "prior.lyra", "let @pub count :I32 = 1", SessionSnapshot.empty())));

        SessionCompileResult.Success linked = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "use-prior.lyra", "count", first.stagedSnapshot())));
        assertEquals(first.stagedSnapshot().revision(), linked.baseRevision());
        assertTrue(linked.typedIr().declarations().getFirst().externalBinding().isPresent());
        try (var loaded = LyraRuntime.load(linked.artifact())) {
            assertThrows(io.mindspice.lyra.runtime.LyraLinkException.class, loaded::instantiate);
        }
    }

    @Test
    void sourceLocalSubmissionsHaveTypedResultsWithoutChangingOrdinaryBytecode() {
        for (String text : List.of("", "()", "42", "\"text\"", "Array<I32>[1 2]",
                "Tuple[1 #T]", "let @pub @mut count :I32 = 40\ncount := (+ count 2)")) {
            SessionCompileResult result = LyraCompiler.compileSession(SessionCompileRequest.builder()
                    .source("local.lyra", text).javaBasePackage("lyra.generated").build());
            SessionCompileResult.Success session = assertInstanceOf(
                    SessionCompileResult.Success.class, result, result.diagnostics().toString());
            CompileResult.Success ordinary = assertInstanceOf(CompileResult.Success.class,
                    LyraCompiler.compile(CompileRequest.source("local.lyra", text)));
            assertEquals(ordinary.artifact().classes().keySet(), session.artifact().classes().keySet());
            ordinary.artifact().classes().forEach((name, bytes) ->
                    assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                            .contains("$lyra$session"), name));
            assertNotEquals(ordinary.artifact().metadata().artifactRevision(),
                    session.artifact().metadata().artifactRevision());
            assertTrue(session.typedIr().rootModule().submissionResult().isPresent());
            try (var loaded = LyraRuntime.load(session.artifact());
                 var module = loaded.instantiate()) {
                assertEquals(1, module.metadata().modules().size());
            }
        }
    }

    @Test
    void compositeSessionResultReusesExistingGeneratedTypeDependencies() {
        String text = "let a :Array<I32> = Array<I32>[3 1 2] "
                + "let f :Fn<;I32> = (=> || 4) Tuple[a a f f ()]";
        SessionCompileResult.Success success = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "composite-result.lyra", text, SessionSnapshot.empty())));

        assertEquals("Tuple<Array<I32>,Array<I32>,Fn<;I32>,Fn<;I32>,Unit>",
                success.typedIr().rootModule().submissionResult().orElseThrow().type()
                        .canonicalSpelling());
        assertTrue(success.artifact().classes().keySet().stream()
                .anyMatch(name -> name.contains("$lyra$tuple$")));
        assertTrue(success.artifact().classes().keySet().stream()
                .anyMatch(name -> name.contains("$lyra$fn$")));
    }

    @Test
    void unusedNonScalarMetadataDoesNotRestrictLaterSourceLocalSubmissions() {
        String text = "let @pub items :Array<I32> = Array<I32>[1]\n"
                + "let @pub maybe :@nil I32 = #NIL\n"
                + "let @pub fn :Fn<;I32> = (=> | | 42)";
        SessionCompileResult.Success first = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "metadata.lyra", text, SessionSnapshot.empty())));
        SessionCompileResult result = LyraCompiler.compileSession(new SessionCompileRequest(
                "next.lyra", "42", first.stagedSnapshot()));
        SessionCompileResult.Success next = assertInstanceOf(
                SessionCompileResult.Success.class, result, result.diagnostics().toString());
        assertEquals(first.stagedSnapshot().bindings(), next.stagedSnapshot().bindings());
        assertEquals(List.of("items", "maybe", "fn"), next.resolvedGraph().declarations().stream().map(value -> value.name()).toList());
        assertTrue(next.typedIr().declarations().getFirst().externalBinding().isPresent());
        assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                new SessionCompileRequest("read-items.lyra", "items", next.stagedSnapshot())));
        for (String source : List.of("fn", "(fn)")) {
            assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                    new SessionCompileRequest("read.lyra", source, next.stagedSnapshot())));
        }
        SessionSnapshot typeOnly = new SessionSnapshot(next.stagedSnapshot().revision(),
                next.stagedSnapshot().bindings(), next.stagedSnapshot().imports(),
                next.stagedSnapshot().pinnedModules(), next.stagedSnapshot().allocator());
        SessionCompileResult.Failure failure = assertInstanceOf(SessionCompileResult.Failure.class,
                LyraCompiler.compileSession(new SessionCompileRequest("read.lyra", "fn", typeOnly)));
        assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                failure.diagnostics().getFirst().code());
        assertEquals(SourceSpan.of(SourceId.path("read.lyra"), 0, 2),
                failure.diagnostics().getFirst().primarySpan());
    }

    @Test
    void unsupportedCallableBearingExternalReadsAssignmentsAndCapturesKeepExactOriginSpans() {
        var allocation = IdentityAllocator.initial().allocateDeclaration();
        URI uri = URI.create("file:///workspace/prior-state.lyra");
        for (ExternalBinding.Visibility visibility : ExternalBinding.Visibility.values()) {
            ExternalBinding binding = new ExternalBinding("count", allocation.id(),
                    BindingContract.immutable(io.mindspice.lyra.compiler.types.ArrayType.of(
                            io.mindspice.lyra.compiler.types.FunctionType.of(List.of(), PrimitiveType.I32))), visibility,
                    ExternalBinding.AssignmentAuthority.NONE, Optional.empty(),
                    SourceOrigin.forText("prior.lyra", 5));
            SessionSnapshot snapshot = new SessionSnapshot(new SessionRevision(1),
                    Map.of("count", binding), Map.of(), Map.of(), allocation.next());
            for (String text : List.of("\ncount", "count := (+ count 2)",
                    "let f :Fn<;I32> = (=> | | count)")) {
                EvaluationSource source = new EvaluationSource(
                        new SourceOrigin("selection", Optional.of(uri), Optional.empty(),
                                100, 100 + text.length()), text);
                SessionCompileResult.Failure failure = assertInstanceOf(
                        SessionCompileResult.Failure.class,
                        LyraCompiler.compileSession(SessionCompileRequest.builder()
                                .source(source).sourceId(SourceId.path("repl/current.lyra"))
                                .snapshot(snapshot).build()));
                assertEquals(1, failure.diagnostics().size());
                var diagnostic = failure.diagnostics().getFirst();
                assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                        diagnostic.code());
                assertEquals(Phase.SESSION, diagnostic.phase());
                int start = 100 + text.indexOf("count");
                assertEquals(SourceSpan.of(SourceId.uri(uri), start, start + 5),
                        diagnostic.primarySpan());
                assertEquals(snapshot.revision(), failure.baseRevision());
                assertEquals(allocation.next(), snapshot.allocator());
                assertEquals(binding, snapshot.binding("count").orElseThrow());
            }
        }
    }

    @Test
    void syntaxFailureIsStructuredAndOriginMapped() {
        String text = "let =";
        URI uri = URI.create("file:///workspace/repl.lyra");
        EvaluationSource source = new EvaluationSource(
                new SourceOrigin("selection", Optional.of(uri), Optional.of(4L), 11, 11 + text.length()),
                text);
        SessionCompileResult.Failure failure = assertInstanceOf(
                SessionCompileResult.Failure.class,
                LyraCompiler.compileSession(SessionCompileRequest.builder()
                        .source(source).sourceId(SourceId.path("repl/reserved-syntax.lyra")).build()));

        assertFalse(failure.diagnostics().isEmpty());
        assertEquals(Phase.PARSE, failure.diagnostics().getFirst().phase());
        assertEquals(SourceId.uri(uri), failure.diagnostics().getFirst().primarySpan().sourceId());
        assertEquals(15, failure.diagnostics().getFirst().primarySpan().startOffset());
        assertEquals(SessionRevision.initial(), failure.baseRevision());
    }

    @Test
    void explicitIdentityMapsRootDiagnosticsAndRelatedSpansExactlyOnce() {
        String text = "let @pub value :I32 = 1\nlet @pub value :I32 = 2";
        SourceId compilerId = SourceId.path("repl/reserved-diagnostic.lyra");
        for (Optional<URI> uri : java.util.List.of(Optional.<URI>empty(),
                Optional.of(URI.create("file:///workspace/selection.lyra")))) {
            SourceId displayId = uri.map(SourceId::uri).orElse(compilerId);
            EvaluationSource source = new EvaluationSource(
                    new SourceOrigin("selection", uri, Optional.of(4L),
                            100, 100 + text.length()), text);
            SessionCompileRequest.Builder builder = SessionCompileRequest.builder()
                    .source(source).sourceId(compilerId);
            SessionCompileResult.Failure failure = assertInstanceOf(
                    SessionCompileResult.Failure.class, LyraCompiler.compileSession(builder.build()));
            var diagnostic = failure.diagnostics().getFirst();
            assertEquals(CompilerDiagnosticCodes.RESOLVE_PUBLIC_REDECLARATION, diagnostic.code());
            int primary = 100 + text.lastIndexOf("value");
            int related = 100 + text.indexOf("value");
            assertEquals(SourceSpan.of(displayId, primary, primary + "value".length()),
                    diagnostic.primarySpan());
            assertEquals(1, diagnostic.relatedSpans().size());
            assertEquals(SourceSpan.of(displayId, related, related + "value".length()),
                    diagnostic.relatedSpans().getFirst().span());
            assertEquals(SessionRevision.initial(), failure.baseRevision());

            SessionCompileResult.Failure invalidConfiguration = assertInstanceOf(
                    SessionCompileResult.Failure.class,
                    LyraCompiler.compileSession(builder.javaTarget(24).build()));
            assertEquals(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                    invalidConfiguration.diagnostics().getFirst().code());
            assertEquals(SourceSpan.at(displayId, 100),
                    invalidConfiguration.diagnostics().getFirst().primarySpan());
        }
    }

    @Test
    void originMappingLeavesImportedDiagnosticsUntouchedEvenWhenTheUriMatches() {
        URI uri = URI.create("file:///workspace/dependency.lyra");
        String importedText = "let @pub value :I32 = 1\nlet @pub value :I32 = 2";
        String text = "import dependency";
        EvaluationSource source = new EvaluationSource(
                new SourceOrigin("selection", Optional.of(uri), Optional.of(4L),
                        100, 100 + text.length()), text);
        SessionCompileResult.Failure failure = assertInstanceOf(
                SessionCompileResult.Failure.class,
                LyraCompiler.compileSession(SessionCompileRequest.builder()
                        .source(source).sourceId(SourceId.path("repl/reserved-import.lyra"))
                        .resolver(SourceResolver.single(ResolvedSource.memory(
                                "dependency", uri, importedText)))
                        .build()));
        var diagnostic = failure.diagnostics().getFirst();
        assertEquals(CompilerDiagnosticCodes.RESOLVE_PUBLIC_REDECLARATION, diagnostic.code());
        int primary = importedText.lastIndexOf("value");
        int related = importedText.indexOf("value");
        assertEquals(SourceSpan.of(SourceId.uri(uri), primary, primary + "value".length()),
                diagnostic.primarySpan());
        assertEquals(1, diagnostic.relatedSpans().size());
        assertEquals(SourceSpan.of(SourceId.uri(uri), related, related + "value".length()),
                diagnostic.relatedSpans().getFirst().span());
    }

    @Test
    void sessionRootIdentityCollisionIsAValidationFailureAtTheImportSpan() {
        URI rootUri = URI.create("memory://repl/root.lyra");
        EvaluationSource source = new EvaluationSource(
                new SourceOrigin("root", Optional.of(rootUri), Optional.empty(), 0, 10),
                "import dep");
        ResolvedSource imported = ResolvedSource.memory(
                "dep", rootUri, "let value = 1".getBytes(StandardCharsets.UTF_8));

        SessionCompileResult.Failure failure = assertInstanceOf(
                SessionCompileResult.Failure.class,
                LyraCompiler.compileSession(SessionCompileRequest.builder()
                        .source(source)
                        .sourceId(SourceId.uri(rootUri))
                        .resolvers(List.of(SourceResolver.single(imported)))
                        .snapshot(SessionSnapshot.empty())
                        .build()));

        assertEquals(CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                failure.diagnostics().getFirst().code());
        assertEquals(SourceId.uri(rootUri), failure.diagnostics().getFirst().primarySpan().sourceId());
        assertTrue(failure.diagnostics().getFirst().primarySpan().startOffset() > 0);
    }

    @Test
    void requestRequiresBaseRevisionToMatchSnapshot() {
        SessionSnapshot snapshot = new SessionSnapshot(
                new SessionRevision(1), Map.of(), Map.of(), IdentityAllocator.initial());
        assertThrows(IllegalArgumentException.class,
                () -> SessionCompileRequest.builder()
                        .source("mismatch.lyra", "42")
                        .sourceId(SourceId.path("repl/reserved-mismatch.lyra"))
                        .baseRevision(SessionRevision.initial())
                        .snapshot(snapshot)
                        .build());
    }

    @Test
    void snapshotsRemainImmutableAcrossSessionCompilation() {
        SessionSnapshot empty = SessionSnapshot.empty();
        SessionCompileResult.Success result = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "immutable.lyra", "let @pub value :I32 = 1", empty)));

        assertTrue(empty.bindings().isEmpty());
        assertEquals(SessionRevision.initial(), empty.revision());
        assertThrows(UnsupportedOperationException.class,
                () -> result.stagedSnapshot().bindings().clear());
        assertEquals(result.typedGraph().allocator(), result.stagedSnapshot().allocator());
    }

    @Test
    void normalCompilerStillUsesItsIndependentInitialIdentitySpace() {
        CompileResult.Success normal = assertInstanceOf(
                CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source(
                        "normal.lyra", "let @pub value :I32 = 1")));
        assertEquals("path:normal.lyra", normal.artifact().metadata()
                .rootModuleId().canonicalSpelling());
    }
}
