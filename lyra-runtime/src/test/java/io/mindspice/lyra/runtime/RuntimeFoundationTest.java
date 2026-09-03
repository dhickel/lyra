package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assertion-grade coverage for the Phase-13 runtime contract. */
public final class RuntimeFoundationTest {
    private static final LyraSignature VALUE_SIGNATURE = LyraSignature.of(
            List.of(PrimitiveType.I32), PrimitiveType.I32);
    private static final String ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    void unitAndCanonicalTypesAreStable() throws Exception {
        assertSame(LyraUnit.INSTANCE, LyraUnit.INSTANCE);
        assertEquals(LyraUnit.INSTANCE, LyraUnit.INSTANCE);
        assertEquals("()", LyraUnit.INSTANCE.toString());
        assertSame(LyraUnit.INSTANCE, roundTripUnit());
        for (PrimitiveType primitive : PrimitiveType.values()) {
            assertSame(primitive, PrimitiveType.fromSpelling(primitive.canonicalSpelling()).orElseThrow());
        }

        LyraType signature = LyraType.parse("Fn<@mutArray<I32>,@nilString;@nilI32>");
        assertEquals("Fn<@mutArray<I32>,@nilString;@nilI32>", signature.canonicalSpelling());
        assertEquals(signature, LyraSignature.parse(signature.canonicalSpelling()).asFunctionType());
        assertEquals(signature.hashCode(), LyraType.parse(signature.canonicalSpelling()).hashCode());
        assertEquals("Tuple<I8,@nilString>", LyraType.tuple(List.of(
                PrimitiveType.I8, PrimitiveType.STRING.nilable())).canonical());
        assertThrows(IllegalArgumentException.class,
                () -> LyraType.parse("Fn<@nil@nilI32;I32>"));
        assertThrows(IllegalArgumentException.class,
                () -> LyraType.parse("Array<@mutI32>"));
        assertThrows(IllegalArgumentException.class,
                () -> LyraSignature.of(List.of(), PrimitiveType.UNIT.mutable()));
        assertThrows(UnsupportedOperationException.class,
                () -> ((FunctionType) signature).parameterTypes().clear());
    }

    @Test
    void identifiersRevisionsAndMetadataAreCanonical() {
        ModuleId module = ModuleId.path("app/main.lyra");
        ModuleRevision moduleRevision = ModuleRevision.compute(
                "let value :I32 = 1".getBytes(StandardCharsets.UTF_8));
        ModuleMetadata moduleMetadata = new ModuleMetadata(module, moduleRevision, "app/main.lyra");
        assertThrows(IllegalArgumentException.class, () -> ArtifactRevision.compute(
                "test-build", List.of(moduleMetadata, moduleMetadata), Map.of(),
                RuntimeProfile.CURRENT, PackagingMode.CLASSES));
        ExportMetadata export = new ExportMetadata(module, "value", VALUE_SIGNATURE, "(I)I",
                BindingMutability.MUTABLE, "value", "get$value", "value$value",
                Optional.of("set$value"));
        assertEquals(export.id(), new ExportId(module, "value", VALUE_SIGNATURE));
        assertEquals(64, export.id().id().length());
        assertNotEquals(export.id(), new ExportId(module, "value",
                LyraSignature.of(List.of(PrimitiveType.I64), PrimitiveType.I32)));

        DebugMapEntry entry = new DebugMapEntry("app/Main", "value", 0, 4,
                new SourceFrame(module, "value", new SourceSpan(module.sourceId(), 0, 5)));
        DebugMapMetadata debugMap = new DebugMapMetadata(1, List.of(entry));
        Map<String, String> names = Map.of(export.id().id(), "value");
        ArtifactRevision artifactRevision = ArtifactRevision.compute("test-build",
                List.of(moduleMetadata), names, RuntimeProfile.CURRENT, PackagingMode.CLASSES);
        ArtifactMetadata metadata = ArtifactMetadata.builder()
                .compilerVersion("1.0-SNAPSHOT")
                .compilerBuild("test-build")
                .artifactId("app")
                .artifactRevision(artifactRevision)
                .rootModuleId(module)
                .rootModuleRevision(moduleRevision)
                .modules(List.of(moduleMetadata))
                .exports(List.of(export))
                .javaNameMap(names)
                .debugMapHash(debugMap.sha256())
                .build();

        assertEquals(metadata, ArtifactMetadataReader.read(metadata.canonicalUtf8()));
        assertEquals(debugMap, DebugMapReader.read(debugMap.canonicalUtf8(), metadata));
        assertEquals(entry, DebugMapReader.read(debugMap.canonicalUtf8(), metadata)
                .lookup("app/Main", "value", 1).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> metadata.modules().clear());
        assertThrows(UnsupportedOperationException.class, () -> metadata.javaNameMap().clear());
        assertThrows(UnsupportedOperationException.class, () -> debugMap.entries().clear());
        assertArrayEquals(metadata.canonicalUtf8(), ArtifactMetadataReader
                .read(metadata.toJson()).canonicalUtf8());
    }

    @Test
    void metadataReaderRejectsMalformedDuplicateAndIncompatibleData() {
        ArtifactMetadata metadata = metadataWithoutExports();
        String json = metadata.canonicalJson();
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read((json.substring(0, json.length() - 1)
                        + ",\"schemaVersion\":1}")));
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replaceFirst("\\\"schemaVersion\\\":1",
                        "\\\"schemaVersion\\\":2")));
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replaceFirst("\\\"profile\\\":\\\"java-25\\\"",
                        "\\\"profile\\\":\\\"java-24\\\"")));
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read((" " + json).getBytes(StandardCharsets.UTF_8)));
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(new byte[]{'{', (byte) 0xc3, '('}));
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read((json.substring(0, json.length() - 1)
                        + ",\"unknownRequiredField\":true}")));
        assertThrows(LyraCompatibilityException.class,
                () -> ArtifactMetadataReader.read(json.replace(
                        "\"rootModuleId\":\"path:main.lyra\"",
                        "\"rootModuleId\":\"path:./main.lyra\"")));
        String optional = json.substring(0, json.length() - 1) + ",\"futureOptional\":true}";
        assertEquals(metadata, ArtifactMetadataReader.read(optional));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactMetadata.builder().compilerVersion("1.0")
                        .compilerBuild("build").artifactId("app")
                        .artifactRevision(ArtifactRevision.compute("build", metadata.modules(),
                                Map.of("unexported", "name"), metadata.profile(),
                                metadata.packagingMode()))
                        .rootModuleId(metadata.rootModuleId())
                        .rootModuleRevision(metadata.rootModuleRevision())
                        .modules(metadata.modules()).javaNameMap(Map.of("unexported", "name"))
                        .debugMapHash(ZERO_HASH).build());
        RuntimeProfile noPreview = new RuntimeProfile("java-25", 25, false, RuntimeAbi.CURRENT);
        ArtifactRevision noPreviewRevision = ArtifactRevision.compute("build", metadata.modules(),
                Map.of(), noPreview, PackagingMode.CLASSES);
        ArtifactMetadata noPreviewMetadata = ArtifactMetadata.builder().compilerVersion("1.0")
                .compilerBuild("build").profile(noPreview).artifactId("app")
                .artifactRevision(noPreviewRevision).rootModuleId(metadata.rootModuleId())
                .rootModuleRevision(metadata.rootModuleRevision()).modules(metadata.modules())
                .javaNameMap(Map.of()).debugMapHash(ZERO_HASH).build();
        assertEquals(noPreviewMetadata,
                ArtifactMetadataReader.read(noPreviewMetadata.canonicalUtf8()));
    }

    @Test
    void sourceFramesUseUtf16AndRenderSyntheticOriginsDeterministically() {
        ModuleId module = ModuleId.path("unicode.lyra");
        SourceData source = new SourceData(module.sourceId(), "unicode.lyra", "A😀B\n é\r\nZ");
        assertEquals(10, source.utf16Length());
        assertEquals(new SourcePosition(1, 1, 2), source.positionAt(1));
        assertEquals(new SourcePosition(2, 1, 3), source.positionAt(2));
        assertEquals(new SourcePosition(5, 2, 1), source.positionAt(5));
        assertEquals(new SourcePosition(7, 2, 3), source.positionAt(7));
        assertEquals(new SourcePosition(8, 2, 4), source.positionAt(8));
        assertEquals(new SourcePosition(9, 3, 1), source.positionAt(9));
        SourceSpan span = new SourceSpan(module.sourceId(), 1, 3);
        assertTrue(span.contains(1));
        assertFalse(span.contains(3));
        assertEquals(2, span.length());
        SourceFrame origin = new SourceFrame(module, "main",
                new SourceSpan(module.sourceId(), 1, 3), source);
        SourceFrame synthetic = SourceFrame.synthetic(module, "$lyra$check",
                new SourceSpan(module.sourceId(), 1, 2), origin);
        LyraRuntimeException failure = new LyraArithmeticException("overflow",
                List.of(synthetic), List.of(new RelatedSource("cause", origin.span(), source)));
        String rendered = failure.render();
        assertTrue(rendered.contains("LYR-ARITH: overflow"));
        assertTrue(rendered.contains("unicode.lyra:1:2"));
        assertTrue(rendered.contains("A😀B"));
        assertFalse(rendered.contains("$lyra$check"));
        assertTrue(failure.render(true).contains("[synthetic]"));
        assertEquals(origin, synthetic.nearestOrigin());
        assertThrows(IllegalStateException.class,
                () -> new SourceFrame(module, "f", new SourceSpan(module.sourceId(), 0, 1))
                        .startPosition());
        assertThrows(IllegalArgumentException.class,
                () -> new SourceFrame(module, "f", SourceSpan.at(module.sourceId(), 0),
                        Optional.empty(), true, Optional.empty()));
    }

    @Test
    void everyRuntimeCategoryCarriesImmutableStructuredPayload() {
        ModuleId module = ModuleId.path("failure.lyra");
        SourceSpan span = new SourceSpan(module.sourceId(), 0, 1);
        SourceFrame frame = new SourceFrame(module, "main", span);
        List<SourceFrame> frames = new ArrayList<>(List.of(frame));
        List<RelatedSource> related = new ArrayList<>(List.of(new RelatedSource("origin", span)));
        Throwable cause = new IllegalStateException("java cause");
        for (LyraFailureCategory category : LyraFailureCategory.values()) {
            LyraRuntimeException failure = LyraRuntimeException.of(category, "summary", frames, related, cause);
            assertEquals(category, failure.category());
            assertEquals(category.code(), failure.code());
            assertEquals("summary", failure.getMessage());
            assertSame(cause, failure.getCause());
            assertThrows(UnsupportedOperationException.class, () -> failure.frames().clear());
            assertThrows(UnsupportedOperationException.class, () -> failure.relatedSources().clear());
        }
        frames.clear();
        related.clear();
        assertEquals(1, LyraRuntimeException.of(LyraFailureCategory.INTERNAL, "x",
                List.of(frame), List.of(), null).frames().size());
        assertEquals(LyraFailureCategory.BOUNDS, LyraFailureCategory.fromCode("LYR-BOUNDS"));
        assertThrows(IllegalArgumentException.class,
                () -> LyraFailureCategory.fromCode("LYR-NOT-A-CATEGORY"));
    }

    @Test
    void optionsOwnNoStreamsAndLifecycleChecksOrderAndInvalidation() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        RuntimeOptions options = RuntimeOptions.builder()
                .streams(input, output, error, StandardCharsets.UTF_8)
                .build();
        assertSame(input, options.ioEnvironment().input());
        assertSame(output, options.ioEnvironment().output());
        assertEquals(StandardCharsets.UTF_8, options.ioEnvironment().charset());
        assertEquals(Thread.currentThread(), options.ownerThread());
        assertEquals(RuntimeAbi.CURRENT, options.runtimeAbi());
        assertEquals(RuntimeProfile.CURRENT, options.profile());
        assertThrows(NullPointerException.class, () -> new RuntimeIoEnvironment(null, output, error,
                StandardCharsets.UTF_8));
        RuntimeProfile noPreview = new RuntimeProfile("java-25", 25, false, RuntimeAbi.CURRENT);
        assertThrows(IllegalArgumentException.class, () -> RuntimeOptions.builder()
                .profile(noPreview).previewEnabled(true).build());

        ModuleLifecycle lifecycle = new ModuleLifecycle(Thread.currentThread(), ModuleId.path("main.lyra"));
        LyraClosureAuthority authority = lifecycle.closureAuthority();
        TestClosure closure = new TestClosure(authority, VALUE_SIGNATURE);
        assertThrows(LyraLifecycleException.class, closure::checkInvocation);
        lifecycle.open();
        assertThrows(LyraLifecycleException.class, lifecycle::open);
        closure.checkInvocation(VALUE_SIGNATURE);
        assertThrows(LyraLinkException.class,
                () -> closure.checkInvocation(LyraSignature.of(List.of(), PrimitiveType.UNIT)));
        lifecycle.close();
        assertFalse(closure.isValid());
        assertEquals(LifecycleState.CLOSED, lifecycle.state());
        assertThrows(LyraClosedException.class, closure::checkInvocation);
        lifecycle.close();

        AtomicReference<Throwable> wrongThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                lifecycle.state();
            } catch (Throwable throwable) {
                wrongThread.set(throwable);
            }
        });
        thread.start();
        thread.join();
        assertTrue(wrongThread.get() instanceof LyraThreadException);

        ModuleLifecycle failed = new ModuleLifecycle();
        LyraClosureAuthority failedAuthority = failed.closureAuthority();
        TestClosure failedClosure = new TestClosure(failedAuthority, VALUE_SIGNATURE);
        IllegalStateException initializationCause = new IllegalStateException("initializer");
        failed.fail(initializationCause);
        assertEquals(LifecycleState.FAILED, failed.state());
        LyraInitializationException failure = assertThrows(LyraInitializationException.class,
                failed::checkOpen);
        assertSame(initializationCause, failure.getCause());
        assertThrows(LyraInitializationException.class, failedClosure::checkInvocation);
        assertThrows(LyraLifecycleException.class, failed::close);
    }

    @Test
    void closuresHaveDistinctIdentityAndRejectForeignOrJavaValues() {
        ModuleLifecycle first = new ModuleLifecycle();
        first.open();
        LyraClosureAuthority firstAuthority = first.closureAuthority();
        TestClosure one = new TestClosure(firstAuthority, VALUE_SIGNATURE);
        TestClosure two = new TestClosure(firstAuthority, VALUE_SIGNATURE);
        assertNotEquals(one.identity(), two.identity());
        assertNotEquals(one.identity().hashCode(), two.identity().hashCode());
        assertEquals(one.identity(), one.identity());
        assertEquals(VALUE_SIGNATURE, one.signature());
        assertSame(one, LyraClosureSupport.requireAuthenticated(one, firstAuthority, VALUE_SIGNATURE));
        AtomicReference<Throwable> closureWrongThread = new AtomicReference<>();
        Thread closureThread = new Thread(() -> {
            try {
                one.isValid();
            } catch (Throwable throwable) {
                closureWrongThread.set(throwable);
            }
        });
        closureThread.start();
        assertDoesNotThrow(() -> closureThread.join());
        assertTrue(closureWrongThread.get() instanceof LyraThreadException);
        assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                new Object(), firstAuthority, VALUE_SIGNATURE));

        ModuleLifecycle second = new ModuleLifecycle();
        second.open();
        TestClosure foreign = new TestClosure(second.closureAuthority(), VALUE_SIGNATURE);
        assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                foreign, firstAuthority, VALUE_SIGNATURE));
        first.close();
        second.close();

        Object artifactKey = new Object();
        ModuleLifecycle root = new ModuleLifecycle(OwnerThread.capture(), ModuleId.path("root.lyra"), artifactKey);
        ModuleLifecycle dependency = new ModuleLifecycle(OwnerThread.capture(), ModuleId.path("dep.lyra"), artifactKey);
        root.open();
        dependency.open();
        TestClosure dependencyClosure = new TestClosure(dependency.closureAuthority(), VALUE_SIGNATURE);
        assertSame(dependencyClosure, LyraClosureSupport.requireAuthenticated(
                dependencyClosure, root.closureAuthority(), VALUE_SIGNATURE));
        root.close();
        dependency.close();
    }

    @Test
    void debugMapReaderRejectsDuplicateAndHashMismatch() {
        ModuleId module = ModuleId.path("map.lyra");
        DebugMapMetadata map = new DebugMapMetadata(1, List.of(new DebugMapEntry(
                "Map", "f", 0, 1, new SourceFrame(module, "f",
                SourceSpan.at(module.sourceId(), 0)))));
        String json = map.canonicalJson();
        assertThrows(LyraCompatibilityException.class,
                () -> DebugMapReader.read((json.substring(0, json.length() - 1)
                        + ",\"unknownRequired\":1}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(LyraCompatibilityException.class,
                () -> DebugMapReader.read((json.substring(0, json.length() - 1)
                        + ",\"entries\":[]}").getBytes(StandardCharsets.UTF_8)));
        ArtifactMetadata metadata = metadataWithoutExports();
        assertThrows(LyraCompatibilityException.class,
                () -> DebugMapReader.read(map.canonicalUtf8(), metadata));
        SourceData source = new SourceData(module.sourceId(), "map.lyra", "x");
        assertThrows(IllegalArgumentException.class, () -> new DebugMapEntry(
                "Map", "f", 0, 1,
                new SourceFrame(module, "f", SourceSpan.at(module.sourceId(), 0), source)));
        ModuleId colonModule = ModuleId.path("dir/colon:name.lyra");
        DebugMapMetadata colonMap = new DebugMapMetadata(1, List.of(new DebugMapEntry(
                "Colon", "f", 0, 1, new SourceFrame(colonModule, "f",
                SourceSpan.at(colonModule.sourceId(), 0)))));
        assertEquals(colonMap, DebugMapReader.read(colonMap.canonicalUtf8()));
    }

    private static LyraUnit roundTripUnit() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(LyraUnit.INSTANCE);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (LyraUnit) input.readObject();
        }
    }

    private static ArtifactMetadata metadataWithoutExports() {
        return metadataWithoutExports(Map.of());
    }

    private static ArtifactMetadata metadataWithoutExports(Map<String, String> names) {
        ModuleId module = ModuleId.path("main.lyra");
        ModuleRevision revision = ModuleRevision.compute("source".getBytes(StandardCharsets.UTF_8));
        ModuleMetadata moduleMetadata = new ModuleMetadata(module, revision, "main.lyra");
        ArtifactRevision artifactRevision = ArtifactRevision.compute("build", List.of(moduleMetadata),
                names, RuntimeProfile.CURRENT, PackagingMode.CLASSES);
        return ArtifactMetadata.builder().compilerVersion("1.0").compilerBuild("build")
                .artifactId("app").artifactRevision(artifactRevision).rootModuleId(module)
                .rootModuleRevision(revision).modules(List.of(moduleMetadata)).javaNameMap(names)
                .debugMapHash(ZERO_HASH).build();
    }

    private static final class TestClosure extends LyraClosure {
        private TestClosure(LyraClosureAuthority authority, LyraSignature signature) {
            super(authority, signature);
        }
    }
}
