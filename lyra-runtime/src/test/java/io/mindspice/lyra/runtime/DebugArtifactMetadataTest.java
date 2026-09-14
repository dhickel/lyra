package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonical metadata coverage for the versioned debug REPL capability and
 * the schema-1 normal compatibility it must not disturb.
 */
final class DebugArtifactMetadataTest {
    private static final String SHA = "0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void replCapabilityRecordIsVersionedAndRejectsUnknownSchemas() {
        assertEquals(1, ReplCapability.CURRENT.schema());
        assertEquals("{\"schema\":1}", ReplCapability.CURRENT.canonicalJson());
        assertThrows(IllegalArgumentException.class, () -> new ReplCapability(2));
        assertThrows(IllegalArgumentException.class, () -> ReplCapability.parse("{\"schema\":2}"));
        assertEquals(ReplCapability.CURRENT, ReplCapability.parse("{\"schema\":1}"));
    }

    @Test
    void legacySchema1NormalMetadataIsRejectedAsAnIncompatibleLanguageContract() {
        byte[] legacy = readResourceBytes("legacy/artifact-v1-normal.json");
        LyraCompatibilityException failure = assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(legacy));
        assertTrue(failure.getMessage().contains("unsupported language contract version: 1"),
                "unexpected compatibility diagnostic: " + failure.getMessage());
    }

    @Test
    void currentSchema1NormalMetadataReadsWithoutAnyDebugContext() {
        ArtifactMetadata metadata = ordinaryMetadata();
        assertFalse(metadata.replCapable());
        assertEquals(Optional.empty(), metadata.replCapability());
        assertEquals(ArtifactProfile.NORMAL, metadata.executionProfile());
        assertTrue(metadata.hookRequirements().isEmpty());
        assertTrue(metadata.dependencyRequirements().isEmpty());
        assertTrue(metadata.imports().isEmpty());
        assertTrue(metadata.reproducibleOptions().isEmpty());
        assertFalse(metadata.canonicalJson().contains("replCapability"));
        assertFalse(metadata.canonicalJson().contains("executionProfile"));
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
    }

    @Test
    void debugCapableNormalMetadataRoundTripsCanonically() {
        ArtifactMetadata metadata = debugMetadata(ArtifactProfile.NORMAL, Optional.empty());
        assertTrue(metadata.replCapable());
        assertEquals(Optional.of(ReplCapability.CURRENT), metadata.replCapability());
        String json = metadata.canonicalJson();
        assertTrue(json.contains("\"replCapability\":{\"schema\":1}"));
        // NORMAL generated code keeps the ordinary encoding default: no
        // executionProfile spelling, no attachment context.
        assertFalse(json.contains("\"executionProfile\""));
        assertFalse(json.contains("attachmentContext"));
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
    }

    @Test
    void debugCapableAttachableMetadataRequiresHooksContextAndClosure() {
        ArtifactMetadata metadata = debugMetadata(ArtifactProfile.ATTACHABLE,
                Optional.of(new AttachmentContext(
                        ModuleId.path("root.lyra"),
                        ModuleRevision.of(SHA),
                        SHA, SHA, SHA, "lyra.generated")));
        assertTrue(metadata.replCapable());
        assertEquals(List.of(
                new ArtifactHook("$lyra$attachmentLifecycle",
                        "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                new ArtifactHook("$lyra$attachmentSafePoint", "()V")),
                metadata.hookRequirements());
        assertEquals(debugClosure(ArtifactProfile.ATTACHABLE),
                metadata.dependencyRequirements());
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
    }

    @Test
    void debugMetadataRejectsMissingOrWrongClosure() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactMetadata.builder()
                .compilerVersion("1.0-SNAPSHOT").compilerBuild("lyra-phase18")
                .runtimeAbi(RuntimeAbi.CURRENT).profile(RuntimeProfile.CURRENT)
                .previewRequired(false).artifactId("test")
                .artifactRevision(ArtifactRevision.of(SHA))
                .rootModuleId(ModuleId.path("root.lyra"))
                .rootModuleRevision(ModuleRevision.of(SHA))
                .modules(List.of(new ModuleMetadata(ModuleId.path("root.lyra"),
                        ModuleRevision.of(SHA), "root.lyra")))
                .sources(List.of(new SourceMetadata(SourceId.path("root.lyra"),
                        "root.lyra", SHA, Optional.of("META-INF/lyra/sources/root.lyra"))))
                .debugMapHash(SHA)
                .replCapable(true)
                .dependencyRequirements(List.of(new ArtifactDependency("io.mindspice",
                        "lyra-runtime", "9.9.9", ArtifactProfile.NORMAL)))
                .build());
        // A debug publication may never omit an embedded source snapshot.
        assertThrows(IllegalArgumentException.class, () -> ArtifactMetadata.builder()
                .compilerVersion("1.0-SNAPSHOT").compilerBuild("lyra-phase18")
                .runtimeAbi(RuntimeAbi.CURRENT).profile(RuntimeProfile.CURRENT)
                .previewRequired(false).artifactId("test")
                .artifactRevision(ArtifactRevision.of(SHA))
                .rootModuleId(ModuleId.path("root.lyra"))
                .rootModuleRevision(ModuleRevision.of(SHA))
                .modules(List.of(new ModuleMetadata(ModuleId.path("root.lyra"),
                        ModuleRevision.of(SHA), "root.lyra")))
                .sources(List.of(new SourceMetadata(SourceId.path("root.lyra"),
                        "root.lyra", SHA, Optional.empty())))
                .debugMapHash(SHA)
                .replCapable(true)
                .build());
        // Session artifacts cannot be debug publications.
        assertThrows(IllegalArgumentException.class, () -> ArtifactMetadata.builder()
                .compilerVersion("1.0-SNAPSHOT").compilerBuild("lyra-phase18")
                .runtimeAbi(RuntimeAbi.CURRENT).profile(RuntimeProfile.CURRENT)
                .previewRequired(false).artifactId("test")
                .artifactRevision(ArtifactRevision.of(SHA))
                .rootModuleId(ModuleId.path("root.lyra"))
                .rootModuleRevision(ModuleRevision.of(SHA))
                .modules(List.of(new ModuleMetadata(ModuleId.path("root.lyra"),
                        ModuleRevision.of(SHA), "root.lyra")))
                .sources(List.of(new SourceMetadata(SourceId.path("root.lyra"),
                        "root.lyra", SHA, Optional.of("META-INF/lyra/sources/root.lyra"))))
                .debugMapHash(SHA)
                .executionProfile(ArtifactProfile.SESSION)
                .replCapable(true)
                .build());
    }

    @Test
    void readerRejectsDebugEncodingsWithMissingOrForeignFields() {
        ArtifactMetadata metadata = debugMetadata(ArtifactProfile.NORMAL, Optional.empty());
        String json = metadata.canonicalJson();
        // Removing the capability turns the profile extension fields into an
        // illegal normal encoding.
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replace(
                        ",\"replCapability\":{\"schema\":1}", "")));
        // A foreign activation key must never be accepted as optional data.
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replace(
                        "\"replCapability\":{\"schema\":1}",
                        "\"replCapability\":{\"schema\":1,\"port\":7000}")));
        // Debug-capable normal metadata must not carry an executionProfile.
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replace(
                        "\"hookRequirements\":[]",
                        "\"executionProfile\":\"normal\",\"hookRequirements\":[]")));
        // A changed closure entry invalidates the canonical revision inputs.
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replace(
                        "\"version\":\"1.0-SNAPSHOT\",\"profile\":\"normal\"",
                        "\"version\":\"9.9.9\",\"profile\":\"normal\"")));
    }

    @Test
    void debugCapabilityEntersArtifactRevisionButOrdinaryRevisionsAreUnchanged() {
        ArtifactMetadata ordinary = debugMetadata(ArtifactProfile.NORMAL, Optional.empty());
        ArtifactRevision ordinaryRevision = ArtifactRevision.compute(
                ordinary.compilerBuild(), ordinary.modules(), ordinary.javaNameMap(),
                ordinary.profile(), ordinary.packagingMode(), ordinary.previewRequired(),
                ordinary.javaPackage(), ordinary.sources(), ordinary.runtimeRequirement(),
                ordinary.executionProfile(), ordinary.hookRequirements(),
                ordinary.dependencyRequirements(), ordinary.attachmentContext(),
                ordinary.imports(), ordinary.reproducibleOptions(), false);
        ArtifactRevision debugRevision = ArtifactRevision.compute(
                ordinary.compilerBuild(), ordinary.modules(), ordinary.javaNameMap(),
                ordinary.profile(), ordinary.packagingMode(), ordinary.previewRequired(),
                ordinary.javaPackage(), ordinary.sources(), ordinary.runtimeRequirement(),
                ordinary.executionProfile(), ordinary.hookRequirements(),
                ordinary.dependencyRequirements(), ordinary.attachmentContext(),
                ordinary.imports(), ordinary.reproducibleOptions(), true);
        assertFalse(ordinaryRevision.equals(debugRevision));
        // A capability-free artifact's published revision must remain
        // reproducible with the capability input absent.
        ArtifactMetadata capabilityFree = ordinaryMetadata();
        assertEquals(capabilityFree.artifactRevision(), ArtifactRevision.compute(
                capabilityFree.compilerBuild(), capabilityFree.modules(), capabilityFree.javaNameMap(),
                capabilityFree.profile(), capabilityFree.packagingMode(), capabilityFree.previewRequired(),
                capabilityFree.javaPackage(), capabilityFree.sources(), capabilityFree.runtimeRequirement(),
                capabilityFree.executionProfile(), capabilityFree.hookRequirements(),
                capabilityFree.dependencyRequirements(), capabilityFree.attachmentContext(),
                capabilityFree.imports(), capabilityFree.reproducibleOptions(), false));
    }

    private static ArtifactMetadata ordinaryMetadata() {
        ModuleMetadata module = new ModuleMetadata(ModuleId.path("root.lyra"),
                ModuleRevision.of(SHA), "root.lyra");
        List<SourceMetadata> sources = List.of(new SourceMetadata(SourceId.path("root.lyra"),
                "root.lyra", SHA, Optional.of("META-INF/lyra/sources/root.lyra")));
        ArtifactRevision revision = ArtifactRevision.compute(
                "lyra-phase18", List.of(module), Map.of(), RuntimeProfile.CURRENT,
                PackagingMode.CLASSES, false, "lyra.generated", sources,
                Optional.empty(), ArtifactProfile.NORMAL, List.of(), List.of(),
                Optional.empty(), List.of(), Map.of(), false);
        return ArtifactMetadata.builder()
                .compilerVersion("1.0-SNAPSHOT").compilerBuild("lyra-phase18")
                .runtimeAbi(RuntimeAbi.CURRENT).profile(RuntimeProfile.CURRENT)
                .previewRequired(false).artifactId("ordinary-test")
                .artifactRevision(revision)
                .rootModuleId(ModuleId.path("root.lyra"))
                .rootModuleRevision(ModuleRevision.of(SHA))
                .modules(List.of(module))
                .sources(sources)
                .debugMapHash(SHA)
                .packagingMode(PackagingMode.CLASSES)
                .build();
    }

    private static ArtifactMetadata debugMetadata(ArtifactProfile executionProfile,
                                                   Optional<AttachmentContext> context) {
        ModuleMetadata module = new ModuleMetadata(ModuleId.path("root.lyra"),
                ModuleRevision.of(SHA), "root.lyra");
        ArtifactRevision revision = ArtifactRevision.compute(
                "lyra-phase18", List.of(module), Map.of(), RuntimeProfile.CURRENT,
                PackagingMode.CLASSES, false, "lyra.generated",
                List.of(new SourceMetadata(SourceId.path("root.lyra"), "root.lyra",
                        SHA, Optional.of("META-INF/lyra/sources/root.lyra"))),
                Optional.empty(), executionProfile,
                executionProfile == ArtifactProfile.ATTACHABLE
                        ? List.of(new ArtifactHook("$lyra$attachmentLifecycle",
                        "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                        new ArtifactHook("$lyra$attachmentSafePoint", "()V"))
                        : List.of(),
                debugClosure(executionProfile), context, List.of(),
                Map.of("checked-arithmetic", "on"), true);
        return ArtifactMetadata.builder()
                .compilerVersion("1.0-SNAPSHOT").compilerBuild("lyra-phase18")
                .runtimeAbi(RuntimeAbi.CURRENT).profile(RuntimeProfile.CURRENT)
                .previewRequired(false).artifactId("debug-test")
                .artifactRevision(revision)
                .rootModuleId(ModuleId.path("root.lyra"))
                .rootModuleRevision(ModuleRevision.of(SHA))
                .modules(List.of(module))
                .sources(List.of(new SourceMetadata(SourceId.path("root.lyra"),
                        "root.lyra", SHA, Optional.of("META-INF/lyra/sources/root.lyra"))))
                .debugMapHash(SHA)
                .executionProfile(executionProfile)
                .hookRequirements(executionProfile == ArtifactProfile.ATTACHABLE
                        ? List.of(new ArtifactHook("$lyra$attachmentLifecycle",
                        "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                        new ArtifactHook("$lyra$attachmentSafePoint", "()V"))
                        : List.of())
                .dependencyRequirements(debugClosure(executionProfile))
                .attachmentContext(context)
                .imports(List.of())
                .reproducibleOptions(Map.of("checked-arithmetic", "on"))
                .replCapable(true)
                .build();
    }

    private static List<ArtifactDependency> debugClosure(ArtifactProfile executionProfile) {
        return List.of(
                new ArtifactDependency(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                        LyraRuntimeConstants.COMPILER_ARTIFACT_ID,
                        LyraRuntimeConstants.COMPILER_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                        LyraRuntimeConstants.REPL_ARTIFACT_ID,
                        LyraRuntimeConstants.REPL_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                        LyraRuntimeConstants.RUNTIME_ARTIFACT_ID,
                        LyraRuntimeConstants.RUNTIME_VERSION, executionProfile));
    }

    private static byte[] readResourceBytes(String name) {
        try (var input = DebugArtifactMetadataTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            return input.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ArtifactMetadata readResource(String name) {
        try (var input = DebugArtifactMetadataTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            return ArtifactMetadataReader.read(input);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
