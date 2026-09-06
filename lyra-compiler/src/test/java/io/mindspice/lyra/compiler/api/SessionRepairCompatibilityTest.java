package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.backend.jvm.JvmBytecodeArtifact;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.ir.IrModule;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.IrValidator;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.SessionRevision;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.session.StorageIdentity;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.runtime.ArtifactSource;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LyraInitializationException;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraSignature;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionRepairCompatibilityTest {
    @Test
    void scalarSubmissionHasASeparateSealedTypedResultAndKeepsUnitInitialization() {
        String source = "let @mut count :I32 = 40\ncount := (+ count 2)\ncount";
        SessionCompileResult.Success success = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(SessionCompileRequest.builder()
                        .source("counter.lyra", source).javaBasePackage("lyra.generated").build()));
        TypedIr ir = success.typedIr();
        IrModule root = ir.rootModule();
        assertTrue(IrValidator.validate(ir).isEmpty());
        assertEquals(PrimitiveType.UNIT, root.body().type());
        assertEquals(List.of(PrimitiveType.UNIT, PrimitiveType.UNIT, PrimitiveType.I32.mutable()),
                root.body().forms().stream().map(IrNode::type).toList());
        assertEquals(root.state().eagerDeclarations(), success.stagedDeclarations());
        assertEquals(1, success.stagedDeclarations().size());
        assertEquals(1, success.stagedSnapshot().bindings().size());
        assertEquals(PrimitiveType.I32, root.submissionResult().orElseThrow().type());
        assertTrue(ir.cells().isEmpty(), "uncaptured @mut storage is a module field, not a session cell");

        IrNode.Sequence retypedBody = new IrNode.Sequence(root.body().span(), PrimitiveType.I32,
                root.body().forms(), root.body().siteId());
        TypedIr retyped = new TypedIr(success.typedGraph(), List.of(new IrModule(root.moduleId(),
                root.rootScope(), root.span(), retypedBody, root.state())), ir.metadata());
        assertFalse(retyped.isValidated());
        assertTrue(IrValidator.validate(retyped).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(CompilerDiagnosticCodes.IR_INVALID_GRAPH)
                        && diagnostic.summary().equals("module initialization sequences must have Unit type")));
        assertThrows(IllegalStateException.class,
                () -> JvmBytecodeArtifact.emit(retyped, "lyra.generated"));

        var state = success.artifact().classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$state$"))
                .map(entry -> ClassFile.of().parse(entry.getValue())).findFirst().orElseThrow();
        assertEquals("()V", state.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("$lyra$checkOpen"))
                .findFirst().orElseThrow().methodType().stringValue());
        assertTrue(state.fields().stream().anyMatch(field ->
                field.fieldName().stringValue().startsWith("$lyra$binding$")
                        && field.fieldType().stringValue().equals("I")));
        CompiledArtifact ordinary = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("counter.lyra", source))).artifact();
        assertEquals(ordinary.classes().keySet(), success.artifact().classes().keySet());
        ordinary.classes().forEach((name, bytes) -> assertFalse(
                new String(bytes, StandardCharsets.ISO_8859_1).contains("$lyra$session"), name));
        assertEquals("()I", state.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("$lyra$sessionExecute"))
                .findFirst().orElseThrow().methodType().stringValue());
        try (var loaded = LyraRuntime.load(success.artifact());
             var module = loaded.instantiate()) {
            assertTrue(module.metadata().exports().isEmpty());
            assertEquals(42, LyraRuntime.readSubmissionResult(module, io.mindspice.lyra.runtime.PrimitiveType.I32));
        }
    }

    @Test
    void stagedMutationPermissionsCannotAuthenticateUninitializedExternalStorage() {
        SessionCompileResult.Success staged = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest("uninitialized.lyra",
                        "let zero :I32 = 0\nlet @pub @mut count :I32 = (% 1 zero)",
                        SessionSnapshot.empty())));
        ExternalBinding count = staged.stagedSnapshot().binding("count").orElseThrow();
        for (ExternalBinding.AssignmentAuthority authority : ExternalBinding.AssignmentAuthority.values()) {
            ExternalBinding advertised = new ExternalBinding(count.name(), count.declarationId(),
                    count.contract(), count.visibility(), authority, count.storageIdentity(), count.origin());
            SessionSnapshot snapshot = staged.stagedSnapshot().withBinding(advertised);
            for (String text : List.of("count", "count := (+ count 2)")) {
                SessionCompileResult compiled = LyraCompiler.compileSession(new SessionCompileRequest("use.lyra", text, snapshot));
                if (text.contains(":=") && !authority.allowsRebinding()) {
                    var failure = assertInstanceOf(SessionCompileResult.Failure.class, compiled);
                    assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED, failure.diagnostics().getFirst().code());
                    assertEquals(SourceSpan.of(SourceId.path("use.lyra"), 0, 5), failure.diagnostics().getFirst().primarySpan());
                } else {
                    var linked = assertInstanceOf(SessionCompileResult.Success.class, compiled);
                    try (var loaded = LyraRuntime.load(linked.artifact())) {
                        assertThrows(LyraLinkException.class, loaded::instantiate);
                    }
                }
                assertEquals(snapshot.revision(), compiled.baseRevision());
                assertEquals(advertised, snapshot.binding("count").orElseThrow());
            }
        }
        // Compilation really did stage metadata for an initializer that cannot succeed.
        try (var loaded = LyraRuntime.load(staged.artifact())) {
            assertThrows(LyraInitializationException.class, loaded::instantiate);
        }
    }

    @Test
    void artifactDefinitionLinkFailureExecutesNoInitializer() {
        SessionCompileResult.Success compiled = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest("effects.lyra",
                        "import std->io\nio->::println[\"initializer executed\"]", SessionSnapshot.empty())));
        CompiledArtifact artifact = compiled.artifact();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        LoadOptions options = LoadOptions.defaults().withIoEnvironment(new RuntimeIoEnvironment(
                InputStream.nullInputStream(), output, error, StandardCharsets.UTF_8));
        Map<String, byte[]> entries = new LinkedHashMap<>(artifact.entries());
        String facade = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$facade$")).findFirst().orElseThrow();
        // Deliberately break class definition, not the source or its initializer.
        entries.put(facade.replace('.', '/') + ".class", ClassFile.of().build(ClassDesc.of(facade),
                builder -> builder.withVersion(ClassFile.JAVA_25_VERSION, 0)
                        .withSuperclass(ClassDesc.of("lyra.missing.SessionStorage"))));
        LyraLinkException failure = assertThrows(LyraLinkException.class,
                () -> LyraRuntime.load(ArtifactSource.fromEntries(artifact.metadata(), entries), options));
        assertEquals("LYR-LINK", failure.code());
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertEquals("", error.toString(StandardCharsets.UTF_8));

        // Positive control: loading is inert; only genuine generated initialization prints.
        try (var loaded = LyraRuntime.load(artifact, options)) {
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            try (var module = loaded.instantiate()) {
                assertEquals("initializer executed\n", output.toString(StandardCharsets.UTF_8));
                assertEquals(artifact.metadata().rootModuleId(), module.moduleId());
            }
        }
    }

    @Test
    void ordinaryCompilationKeepsTypedIndependentModuleInstances() throws Throwable {
        String source = "let @mut count :I32 = 1\n"
                + "let @pub bump :Fn<;I32> = (=> | | { count := (+ count 1) count })\n";
        CompiledArtifact artifact = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("counter.lyra", source))).artifact();
        try (var loaded = LyraRuntime.load(artifact);
             var first = loaded.instantiate();
             var second = loaded.instantiate()) {
            var firstBump = first.export("bump", LyraSignature.parse("Fn<;I32>"));
            var secondBump = second.export("bump", LyraSignature.parse("Fn<;I32>"));
            assertEquals(MethodType.methodType(int.class), firstBump.methodType());
            assertEquals(2, (int) firstBump.methodHandle().invokeExact());
            assertEquals(3, (int) firstBump.methodHandle().invokeExact());
            assertEquals(2, (int) secondBump.methodHandle().invokeExact());
        }
    }

    @Test
    void ordinaryArtifactsHaveNoAttemptedSessionEntryPointOrResultStorage() {
        for (String source : new String[] {"", "let @pub unit :Unit = ()",
                "let @pub maybe :@nil I32 = #NIL", "let @pub value :I32 = 42\n(+ value 1)"}) {
            CompiledArtifact artifact = assertInstanceOf(CompileResult.Success.class,
                    LyraCompiler.compile(CompileRequest.source("ordinary.lyra", source))).artifact();
            artifact.classes().forEach((name, bytes) -> {
                var model = ClassFile.of().parse(bytes);
                assertEquals(ClassFile.JAVA_25_VERSION, model.majorVersion(), name);
                assertTrue(model.methods().stream().noneMatch(method ->
                        method.methodName().stringValue().equals("$lyra$evaluate")
                                || method.methodName().stringValue().contains("$session$")), name);
                assertTrue(model.fields().stream().noneMatch(field ->
                        field.fieldName().stringValue().contains("$session$")), name);
            });
        }
    }

    @Test
    void retainedSessionMetadataIsImmutableAndDoesNotInjectOrdinaryBindings() {
        var allocation = IdentityAllocator.initial().allocateDeclaration();
        ExternalBinding binding = new ExternalBinding("prior", allocation.id(),
                BindingContract.mutable(PrimitiveType.I32), ExternalBinding.Visibility.PRIVATE,
                ExternalBinding.AssignmentAuthority.ALL,
                Optional.of(StorageIdentity.forDeclaration(allocation.id())),
                SourceOrigin.forText("previous.lyra", 5));
        Map<String, ExternalBinding> inputs = new LinkedHashMap<>(Map.of("prior", binding));
        SessionSnapshot snapshot = new SessionSnapshot(SessionRevision.initial(), inputs,
                Map.of(), Map.of(), allocation.next());
        inputs.clear();
        assertEquals(binding, snapshot.binding("prior").orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.bindings().clear());
        assertThrows(IllegalArgumentException.class, () -> new SessionSnapshot(
                snapshot.revision(), snapshot.bindings(), Map.of(), Map.of(), IdentityAllocator.initial()));
        assertThrows(IllegalArgumentException.class, () -> new ExternalBinding("prior", allocation.id(),
                binding.contract(), ExternalBinding.Visibility.IMPORTED,
                ExternalBinding.AssignmentAuthority.ALL, binding.storageIdentity(), binding.origin()));

        SessionSnapshot next = snapshot.nextRevision(Map.of(), Map.of(), allocation.next());
        assertEquals(0, snapshot.revision().value());
        assertEquals(1, next.revision().value());
        assertTrue(next.bindings().isEmpty());
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.source("current.lyra", "prior")));
        assertEquals(CompilerDiagnosticCodes.RESOLVE_UNRESOLVED_NAME,
                failure.diagnostics().getFirst().code());
    }

    @Test
    void ordinaryMutationAndTypeErrorsStillFailBeforeArtifactPublication() {
        CompileResult.Failure immutable = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.source("immutable.lyra",
                        "let count :I32 = 1\ncount := 2")));
        assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                immutable.diagnostics().getFirst().code());
        CompileResult.Failure mismatch = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.source("type.lyra",
                        "let value :I32 = \"wrong\"")));
        assertEquals(CompilerDiagnosticCodes.TYPE_MISMATCH, mismatch.diagnostics().getFirst().code());
    }

    @Test
    void retainedSourceOriginsMapUtf16OffsetsWithoutExecutingSource() {
        String text = "\"\uD83D\uDE00\"";
        URI uri = URI.create("file:///workspace/source.lyra");
        SourceOrigin origin = new SourceOrigin("selection", Optional.of(uri), Optional.of(7L),
                10, 10 + text.length());
        EvaluationSource source = new EvaluationSource(origin, text);
        SourceId local = SourceId.path("submission.lyra");
        assertEquals(4, source.utf16Length());
        assertEquals(SourceSpan.of(SourceId.uri(uri), 11, 13),
                origin.map(SourceSpan.of(local, 1, 3), local));
        assertEquals(14, source.mapOriginOffset(source.utf16Length()));
        assertThrows(IllegalArgumentException.class, () -> source.mapOriginOffset(5));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationSource(origin, "short"));
    }
}
