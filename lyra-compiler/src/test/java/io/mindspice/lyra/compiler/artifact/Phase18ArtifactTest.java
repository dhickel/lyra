package io.mindspice.lyra.compiler.artifact;

import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactMetadataReader;
import io.mindspice.lyra.runtime.ArtifactRevision;
import io.mindspice.lyra.runtime.ExportMetadata;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.runtime.LyraRuntimeConstants;
import io.mindspice.lyra.runtime.LyraType;
import io.mindspice.lyra.runtime.ModuleMetadata;
import io.mindspice.lyra.runtime.ModuleRevision;
import io.mindspice.lyra.runtime.PackagingMode;
import io.mindspice.lyra.runtime.RuntimeAbi;
import io.mindspice.lyra.runtime.RuntimeProfile;
import io.mindspice.lyra.runtime.SourceId;
import io.mindspice.lyra.runtime.SourceMetadata;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase18ArtifactTest {
    @Test
    void metadataIsCanonicalEscapedOrderedAndReadable() {
        ArtifactMetadata metadata = metadata();
        String json = metadata.canonicalJson();

        assertEquals(json, new String(metadata.canonicalUtf8(), StandardCharsets.UTF_8));
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
        assertTrue(json.indexOf("\"schemaVersion\"")
                < json.indexOf("\"languageContractVersion\""));
        assertTrue(json.indexOf("\"modules\"") < json.indexOf("\"sources\""));
        assertTrue(json.indexOf("\"sources\"") < json.indexOf("\"exports\""));
        assertTrue(json.indexOf("\"exports\"") < json.indexOf("\"javaNameMap\""));
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
    }

    @Test
    void readerRejectsMalformedAndRequiredUnknownFieldsAsCompatibilityFailures() {
        String json = metadata().canonicalJson();
        String malformedNumber = json.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(malformedNumber));

        String unknownRequired = json.replace("\"packagingMode\":\"classes\"}",
                "\"packagingMode\":\"classes\",\"requiredFuture\":1}");
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(unknownRequired));

        int sources = json.indexOf("\"sources\":");
        int exports = json.indexOf("\"exports\":", sources);
        String missingSources = json.substring(0, sources) + json.substring(exports);
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(missingSources));
    }

    @Test
    void sourceMetadataRoundTripsWhenPathAndUriValuesCoincide() throws Exception {
        io.mindspice.lyra.runtime.ModuleId path =
                io.mindspice.lyra.runtime.ModuleId.path("same:value");
        io.mindspice.lyra.runtime.ModuleId uri =
                io.mindspice.lyra.runtime.ModuleId.uri(URI.create("same:value"));
        ModuleRevision pathRevision = ModuleRevision.of("a".repeat(64));
        ModuleRevision uriRevision = ModuleRevision.of("b".repeat(64));
        ModuleMetadata pathModule = new ModuleMetadata(path, pathRevision, "same:value");
        ModuleMetadata uriModule = new ModuleMetadata(uri, uriRevision, "same:value");
        SourceMetadata pathSource = new SourceMetadata(SourceId.path("same:value"),
                "same:value", "c".repeat(64));
        SourceMetadata uriSource = new SourceMetadata(SourceId.uri(URI.create("same:value")),
                "same:value", "d".repeat(64));
        List<ModuleMetadata> modules = List.of(pathModule, uriModule);
        List<SourceMetadata> sources = List.of(pathSource, uriSource);
        ArtifactRevision revision = ArtifactRevision.compute("build", modules, Map.of(),
                RuntimeProfile.CURRENT, PackagingMode.CLASSES, false, sources, Optional.empty());
        ArtifactMetadata metadata = ArtifactMetadata.builder()
                .compilerVersion("1.0").compilerBuild("build").artifactId("artifact")
                .artifactRevision(revision).rootModuleId(path).rootModuleRevision(pathRevision)
                .modules(modules).sources(sources).javaNameMap(Map.of())
                .debugMapHash("e".repeat(64)).build();

        String json = metadata.canonicalJson();
        String pathIdentity = "\"sourceKind\":\"path\",\"sourceId\":\"same:value\"";
        String uriIdentity = "\"sourceKind\":\"uri\",\"sourceId\":\"same:value\"";
        assertTrue(json.indexOf(pathIdentity) >= 0);
        assertTrue(json.indexOf(pathIdentity) < json.indexOf(uriIdentity));
        assertEquals(List.of(pathSource, uriSource), metadata.sources());
        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));

        String duplicate = json.replace(uriIdentity, pathIdentity);
        LyraCompatibilityException failure = assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(duplicate));
        assertTrue(failure.getMessage().contains(
                "source metadata is not sorted or contains a duplicate"));
    }

    @Test
    void manifestContinuationLinesRespectTheJar72ByteLimit() throws Exception {
        ArtifactMetadata base = metadata();
        ArtifactMetadata longId = new ArtifactMetadata(
                base.schemaVersion(), base.languageContractVersion(), base.compilerVersion(),
                base.compilerBuild(), base.runtimeAbi(), base.profile(), base.javaClassFileTarget(),
                base.previewRequired(), "a".repeat(200), base.artifactRevision(), base.rootModuleId(),
                base.rootModuleRevision(), base.modules(), base.sources(), base.exports(),
                base.javaNameMap(), base.debugMapVersion(), base.debugMapHash(),
                base.packagingMode(), base.runtimeRequirement());
        var method = ArtifactOutputWriter.class.getDeclaredMethod("manifest", ArtifactMetadata.class);
        method.setAccessible(true);
        String manifest = new String((byte[]) method.invoke(null, longId), StandardCharsets.UTF_8);
        String[] lines = manifest.split("\\r\\n", -1);
        assertEquals("", lines[lines.length - 1]);
        for (int index = 0; index < lines.length - 1; index++) {
            int lineBytes = lines[index].getBytes(StandardCharsets.UTF_8).length;
            assertTrue(lineBytes + 2 <= 72,
                    "manifest line exceeds 72 bytes including CRLF: " + lines[index]);
        }
    }

    @Test
    void entryPolicyRejectsTraversalAbsoluteAndInvalidBinaryNames() {
        assertThrows(ArtifactAssemblyException.class, () -> EntryNames.require("../escape"));
        assertThrows(ArtifactAssemblyException.class, () -> EntryNames.require("C:/escape"));
        assertThrows(ArtifactAssemblyException.class, () -> EntryNames.require("/escape"));
        assertThrows(ArtifactAssemblyException.class, () -> EntryNames.classEntry("bad-name.Type"));
        assertEquals("lyra/$lyra$Thing.class", EntryNames.classEntry("lyra.$lyra$Thing"));
    }

    private static ArtifactMetadata metadata() {
        io.mindspice.lyra.runtime.ModuleId module =
                io.mindspice.lyra.runtime.ModuleId.path("pkg/main.lyra");
        ModuleRevision moduleRevision = ModuleRevision.of("a".repeat(64));
        ModuleMetadata moduleMetadata = new ModuleMetadata(module, moduleRevision, "module");
        SourceMetadata source = new SourceMetadata(SourceId.path("pkg/main.lyra"),
                "source\"\\label", "b".repeat(64),
                Optional.of("META-INF/lyra/sources/pkg/main.lyra"));
        ExportMetadata export = new ExportMetadata(module, "value", LyraType.I32, "()I");
        Map<String, String> names = Map.of(export.id().id(), "value");
        ArtifactRevision revision = ArtifactRevision.compute(
                "build\\\"", List.of(moduleMetadata), names, RuntimeProfile.CURRENT,
                PackagingMode.CLASSES, false, List.of(source), Optional.empty());
        return new ArtifactMetadata(
                LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION,
                LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION,
                "1.0",
                "build\\\"",
                RuntimeAbi.CURRENT,
                RuntimeProfile.CURRENT,
                LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET,
                false,
                "id\\\"value",
                revision,
                module,
                moduleRevision,
                List.of(moduleMetadata),
                List.of(source),
                List.of(export),
                names,
                LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION,
                "c".repeat(64),
                PackagingMode.CLASSES,
                Optional.empty());
    }
}
