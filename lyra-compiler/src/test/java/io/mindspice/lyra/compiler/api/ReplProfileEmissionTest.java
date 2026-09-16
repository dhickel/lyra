package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.runtime.ArtifactHook;
import io.mindspice.lyra.runtime.ArtifactMetadataReader;
import io.mindspice.lyra.runtime.ArtifactProfile;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplProfileEmissionTest {
    private static final String SOURCE = "let @pub answer :I32 = 42\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n";

    @Test
    void normalIsTheDefaultAndContainsNoAttachmentHooks() {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        CompiledArtifact artifact = result.artifact();
        assertEquals(ArtifactProfile.NORMAL, artifact.metadata().artifactProfile());
        assertTrue(artifact.metadata().hookRequirements().isEmpty());
        assertTrue(artifact.metadata().dependencyRequirements().isEmpty());
        assertTrue(artifact.metadata().attachmentContext().isEmpty());
        assertTrue(artifact.metadata().imports().isEmpty());
        assertTrue(artifact.metadata().reproducibleOptions().isEmpty());
        assertFalse(artifact.metadata().canonicalJson().contains("executionProfile"));
        assertTrue(artifact.metadata().exports().stream()
                .allMatch(export -> export.declarationIdentity() == -1L
                        && export.originDeclarationIdentity() == -1L));
        assertFalse(artifact.classes().values().stream()
                .anyMatch(bytes -> contains(bytes, "$lyra$attachment")));
    }

    @Test
    void attachableCompilationDeclaresContextAndRealHookMembers() {
        CompileRequest request = CompileRequest.builder().source("main.lyra", SOURCE)
                .includeSources(true)
                .semanticOptions(Map.of("checked-arithmetic", "on", "debug-level", "source"))
                .profile(CompileProfile.ATTACHABLE).build();
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request));
        CompiledArtifact artifact = result.artifact();
        assertEquals(ArtifactProfile.ATTACHABLE, artifact.metadata().artifactProfile());
        assertEquals(List.of(new ArtifactHook("$lyra$attachmentLifecycle",
                        "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                new ArtifactHook("$lyra$attachmentSafePoint", "()V")),
                artifact.metadata().hookRequirements());
        assertEquals(Map.of("checked-arithmetic", "on", "debug-level", "source"),
                artifact.metadata().reproducibleOptions());
        assertTrue(artifact.metadata().attachmentContext().isPresent());
        assertEquals(artifact.metadata().rootModuleId(), artifact.metadata().attachmentContext().orElseThrow().rootModule());
        assertEquals(artifact.metadata().rootModuleRevision(), artifact.metadata().attachmentContext().orElseThrow().rootRevision());
        assertEquals(artifact.metadata().javaPackage(), artifact.metadata().attachmentContext().orElseThrow().javaPackage());
        assertEquals(artifact.metadata(), ArtifactMetadataReader.read(artifact.metadata().canonicalUtf8()));
        assertTrue(artifact.metadata().sources().stream().allMatch(source -> source.entryName().isPresent()));
        assertTrue(artifact.metadata().exports().stream()
                .allMatch(export -> export.declarationIdentity() >= 0
                        && export.originDeclarationIdentity() >= 0));
        assertTrue(artifact.classes().values().stream().anyMatch(bytes -> contains(bytes,
                "$lyra$attachmentSafePoint")));
        assertTrue(artifact.classes().values().stream().anyMatch(bytes -> contains(bytes,
                "$lyra$attachmentLifecycle")));
    }

    @Test
    void attachableReachableGraphsHaveOneRootRegistrationHook() throws Throwable {
        CompileRequest request = CompileRequest.builder()
                .source("main.lyra", "import dep let @pub answer :I32 = dep->:.value")
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep", URI.create("memory:dep.lyra"),
                        "let @pub value :I32 = 41")))
                .profile(CompileProfile.ATTACHABLE).build();
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request));
        assertEquals(2, result.artifact().metadata().modules().size());
        assertTrue(result.artifact().metadata().sources().stream()
                .allMatch(source -> source.entryName().isPresent()));
        var loaded = io.mindspice.lyra.runtime.LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        var registration = io.mindspice.lyra.runtime.LyraRuntime.registerRoot(root);
        try {
            assertEquals(41, registration.requireBinding("answer").getter().invoke());
        } finally {
            registration.close();
            root.close();
            loaded.close();
        }
    }

    @Test
    void attachableMetadataRejectsMissingOrWrongHookContext() {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.builder().source("main.lyra", SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
        String json = result.artifact().metadata().canonicalJson();
        int contextStart = json.indexOf(",\"attachmentContext\":");
        int importsStart = json.indexOf(",\"imports\":", contextStart);
        String missingContext = json.substring(0, contextStart) + json.substring(importsStart);
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(missingContext));
        String wrongHook = json.replace("$lyra$attachmentSafePoint\",\"descriptor\":\"()V",
                "$lyra$attachmentSafePoint\",\"descriptor\":\"(I)V");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(wrongHook));
    }

    @Test
    void sessionProfileIsNotAcceptedByTheOrdinaryCompilerEntryPoint() {
        CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                .source("main.lyra", SOURCE).profile(CompileProfile.SESSION).build());
        assertInstanceOf(CompileResult.Failure.class, result);
    }

    @Test
    void attachableSemanticBoundaryRejectsPublicMutableAggregateMutation() {
        String source = "let @pub @mut values :Array<I32> = Array[1 2]\n"
                + "let @pub bump :Fn<;Unit> = (=> | | (values[0] := 9))\n";
        CompileResult normal = LyraCompiler.compile(
                CompileRequest.source("main.lyra", source));
        assertInstanceOf(CompileResult.Success.class, normal,
                "ordinary compilation must preserve its own-array mutation semantics");
        CompileResult attachable = LyraCompiler.compile(CompileRequest.builder()
                .source("main.lyra", source).profile(CompileProfile.ATTACHABLE).build());
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class, attachable);
        assertEquals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                failure.diagnostics().getFirst().code());
        assertTrue(failure.diagnostics().getFirst().summary()
                .contains("attachment safe points"));
    }

    @Test
    void attachableSemanticBoundaryDoesNotPoisonInitializationFacts() {
        String source = "let @pub @mut values :Array<I32> = Array[1 2]\n"
                + "let @mut alias :Array<I32> = values\n"
                + "let done :Unit = (alias[0] := 9)\n";
        assertInstanceOf(CompileResult.Success.class, LyraCompiler.compile(
                CompileRequest.builder().source("main.lyra", source)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    @Test
    void attachableSemanticBoundaryPropagatesThroughReturnedAggregates() {
        String source = "let @pub @mut values :Array<I32> = Array[1 2]\n"
                + "let @pub get :Fn<;Array<I32>> = (=> | | values)\n"
                + "let invalid :Fn<;Unit> = (=> | | { "
                + "let @mut alias :Array<I32> = (get) alias[0] := 9 })\n";
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("main.lyra", source)
                        .profile(CompileProfile.ATTACHABLE).build()));
        assertEquals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                failure.diagnostics().getFirst().code());
    }

    @Test
    void attachableSemanticBoundaryPropagatesThroughCallsAndCaptures() {
        String source = "let @pub @mut values :Array<I32> = Array[1 2]\n"
                + "let @pub bump :Fn<;Unit> = (=> | | (values[0] := 9))\n"
                + "let @pub callBump :Fn<;Unit> = (=> | | (bump))\n"
                + "let @pub topLevel :Unit = (bump)\n";
        CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("main.lyra", source)
                        .profile(CompileProfile.ATTACHABLE).build()));
        assertEquals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                failure.diagnostics().getFirst().code());
        assertInstanceOf(CompileResult.Success.class, LyraCompiler.compile(
                CompileRequest.source("main.lyra", source)));
    }

    @Test
    void attachableSemanticBoundaryPreservesRequiredMutableAndHigherOrderBehavior() {
        String source = "let @pub @mut values :Array<I32> = Array[1 2]\n"
                + "let @mut hidden :Array<I32> = Array[1 2]\n"
                + "let @pub bumpHidden :Fn<;Unit> = (=> | | (hidden[0] := 9))\n"
                + "let @pub @mut count :I32 = 1\n"
                + "let @pub setCount :Fn<;Unit> = (=> | | (count := 9))\n"
                + "let @pub readFirst :Fn<;I32> = (=> | | values[0])\n"
                + "let @pub replaceValues :Fn<;Unit> = (=> | | (values := Array[3 4]))\n"
                + "let @pub @mut selected :Fn<I32;I32> = (=> |value| (+ value 1))\n"
                + "let @pub @mut replacement :Fn<I32;I32> = (=> |value| (+ value 2))\n"
                + "let @pub callSelected :Fn<I32;I32> = (=> |value| (selected value))\n"
                + "let @pub passFunction :Fn<I32;I32> = (=> |value| (apply selected value))\n"
                + "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n";
        CompileResult.Success attachable = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("main.lyra", source)
                        .profile(CompileProfile.ATTACHABLE).build()));
        assertEquals(ArtifactProfile.ATTACHABLE, attachable.artifact().metadata().artifactProfile());
        assertInstanceOf(CompileResult.Success.class, LyraCompiler.compile(
                CompileRequest.source("main.lyra", source)));
    }

    @Test
    void attachableSemanticBoundaryKeepsOrdinaryImportOwnershipRules() {
        String source = "import dep let @pub read :Fn<;I32> = (=> | | dep->:.values[0])\n"
                + "let @pub bump :Fn<;Unit> = (=> | | (dep->:.values[0] := 9))\n";
        String dep = "let @pub values :Array<I32> = Array[1 2]\n";
        for (CompileProfile profile : new CompileProfile[]{
                CompileProfile.NORMAL, CompileProfile.ATTACHABLE}) {
            CompileResult.Failure failure = assertInstanceOf(CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("main.lyra", source)
                            .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                                    URI.create("memory:dep.lyra"), dep)))
                            .profile(profile).build()));
            assertEquals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                    failure.diagnostics().getFirst().code(),
                    "imported aggregate mutation stays protected in " + profile);
        }
    }

    @Test
    void attachableImportTopologySourcesAndOptionsRoundTrip() {
        CompileRequest request = CompileRequest.builder()
                .source("main.lyra", "import dep let @pub answer :I32 = dep->:.value")
                .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                        URI.create("memory:dep.lyra"), "let @pub value :I32 = 41")))
                .semanticOptions(Map.of("checked-arithmetic", "on"))
                .profile(CompileProfile.ATTACHABLE).build();
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request));
        var metadata = result.artifact().metadata();
        assertEquals(1, metadata.imports().size());
        var imported = metadata.imports().getFirst();
        assertEquals(metadata.rootModuleId(), imported.fromModule());
        assertEquals("dep", imported.logicalTarget());
        assertEquals(metadata.modules().stream()
                        .filter(module -> !module.id().equals(metadata.rootModuleId()))
                        .findFirst().orElseThrow().id(),
                imported.targetModule());
        assertEquals(7, imported.startOffset());
        assertEquals(10, imported.endOffset());
        assertEquals(Map.of("checked-arithmetic", "on"), metadata.reproducibleOptions());
        assertEquals(2, metadata.sources().size());
        assertTrue(metadata.sources().stream().allMatch(source -> source.entryName().isPresent()));
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalJson()));
    }

    @Test
    void attachableMetadataRejectsMalformedProfileContext() {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.builder().source("main.lyra", SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
        var metadata = result.artifact().metadata();
        var context = metadata.attachmentContext().orElseThrow();
        String json = metadata.canonicalJson();
        // A context that names a different root revision is not the recorded root.
        String wrongRevision = json.replace(context.rootRevision().value(),
                io.mindspice.lyra.runtime.ModuleRevision.of(
                        "0".repeat(64)).value());
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(wrongRevision));
        String wrongPackage = json.replace(context.javaPackage(), "other.package");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(wrongPackage));
        String nonCanonicalProfile = json.replace(
                "\"executionProfile\":\"attachable\"",
                "\"executionProfile\":\"ATTACHABLE\"");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(nonCanonicalProfile));
        String duplicateHook = json.replace(
                "{\"name\":\"$lyra$attachmentLifecycle\",\"descriptor\":\"()Lio/mindspice/lyra/runtime/ModuleLifecycle;\"},",
                "{\"name\":\"$lyra$attachmentLifecycle\",\"descriptor\":\"()Lio/mindspice/lyra/runtime/ModuleLifecycle;\"},"
                        + "{\"name\":\"$lyra$attachmentLifecycle\",\"descriptor\":\"()Lio/mindspice/lyra/runtime/ModuleLifecycle;\"},");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(duplicateHook));
        // A normal artifact must not accept profile extension fields.
        CompileResult.Success normal = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        String normalJson = normal.artifact().metadata().canonicalJson();
        String polluted = normalJson.substring(0, normalJson.length() - 1)
                + ",\"executionProfile\":\"attachable\"}";
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(polluted));
    }

    @Test
    void sessionProfileMetadataRequiresNoAttachmentSurface() {
        CompileResult.Success attachable = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.builder().source("main.lyra", SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
        String attachableJson = attachable.artifact().metadata().canonicalJson();
        // A session profile cannot carry attachment hooks, dependencies or root context.
        String sessionWithHooks = attachableJson.replace("\"executionProfile\":\"attachable\"",
                "\"executionProfile\":\"session\"");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(sessionWithHooks));
        // A session profile also cannot carry attachment context.
        String attachableWithSessionHooks = attachableJson
                .replace("\"executionProfile\":\"attachable\"",
                        "\"executionProfile\":\"session\"")
                .replace("{\"name\":\"$lyra$attachmentLifecycle\",\"descriptor\":\"()Lio/mindspice/lyra/runtime/ModuleLifecycle;\"},", "")
                .replace("{\"name\":\"$lyra$attachmentSafePoint\",\"descriptor\":\"()V\"}", "")
                .replace("{\"groupId\":\"io.mindspice\",\"artifactId\":\"lyra-runtime\",\"version\":\"0.1.1\",\"profile\":\"attachable\"}", "");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(attachableWithSessionHooks),
                "session metadata must not accept an attachment context");
    }

    @Test
    void attachableBuildsAreByteIdenticalAcrossCompilations() {
        CompileRequest request = CompileRequest.builder()
                .source("main.lyra", SOURCE)
                .semanticOptions(Map.of("checked-arithmetic", "on"))
                .profile(CompileProfile.ATTACHABLE).build();
        CompileResult.Success first = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request));
        CompileResult.Success second = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(request));
        assertEquals(first.artifact().metadata().canonicalJson(),
                second.artifact().metadata().canonicalJson());
        assertEquals(first.artifact().metadata().attachmentContext(),
                second.artifact().metadata().attachmentContext());
        assertEquals(first.artifact().classes().keySet(),
                second.artifact().classes().keySet());
        first.artifact().classes().forEach((name, bytes) -> assertTrue(
                java.util.Arrays.equals(bytes, second.artifact().classes().get(name)),
                "class bytes differ across repeat attachable builds: " + name));
        CompileResult.Success normal = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("main.lyra", SOURCE)));
        assertFalse(normal.artifact().metadata().canonicalJson()
                .contains("attachmentContext"));
    }

    @Test
    void normalArtifactsRemainOrdinaryLoadableAot() throws Throwable {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("main.lyra", "let @pub add :Fn<I32,I32;I32> = (=> |a b| (+ a b))\n")
                        .build()));
        assertEquals(ArtifactProfile.NORMAL, result.artifact().metadata().artifactProfile());
        var loaded = io.mindspice.lyra.runtime.LyraRuntime.load(result.artifact());
        var root = loaded.instantiate();
        try {
            assertEquals(11, (int) root.export("add", "Fn<I32,I32;I32>")
                    .methodHandle().invokeExact(5, 6));
        } finally {
            root.close();
            loaded.close();
        }
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }
}
