package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NominalArtifactMetadataTest {
    @Test void supportedSchemaVersionsRemainAnExplicitClosedSet() {
        assertDoesNotThrow(() -> LyraRuntimeConstants.requireArtifactSchema(1));
        assertDoesNotThrow(() -> LyraRuntimeConstants.requireArtifactSchema(2));
        for (int version : new int[] { Integer.MIN_VALUE, -1, 0, 3, Integer.MAX_VALUE }) {
            assertThrows(LyraCompatibilityException.class,
                    () -> LyraRuntimeConstants.requireArtifactSchema(version));
        }
    }

    @Test void seededSharedSignatureCachePreservesIndividualProducerLifetimes() {
        int cases = Integer.getInteger("lyra.fuzz.cases", 60);
        for (long seed : new long[] { 521, 20260912 }) {
            var random = new java.util.Random(seed);
            var key = ModuleLifecycle.newArtifactKey(RuntimeOptions.defaults(), builder(schemas()).build());
            String canonical = "Fn<;" + type("First") + ">";
            var expected = LyraSignature.of(List.of(), type("First"));
            for (int index = 0; index < cases; index++) {
                String replay = "seed=" + seed + ", index=" + index;
                var retired = ModuleLifecycle.forArtifact(MODULE, key);
                var live = ModuleLifecycle.forArtifact(MODULE, key);
                var retiredAuthority = retired.closureAuthority();
                var liveAuthority = live.closureAuthority();
                assertEquals(expected, retiredAuthority.resolveSignature(canonical), replay);
                assertSame(retiredAuthority.resolveSignature(canonical), liveAuthority.resolveSignature(canonical), replay);
                boolean failed = random.nextBoolean();
                if (failed) retired.fail(new IllegalStateException("model initialization failure"));
                else { retired.open(); retired.close(); }
                Class<? extends RuntimeException> expectedFailure = failed
                        ? LyraInitializationException.class : LyraClosedException.class;
                assertThrows(expectedFailure,
                        () -> retiredAuthority.resolveSignature(canonical), replay);
                assertEquals(expected, liveAuthority.resolveSignature(canonical), replay);
                live.open();
                assertEquals(expected, liveAuthority.resolveSignature(canonical), replay);
                live.close();
                assertThrows(LyraClosedException.class, () -> liveAuthority.resolveSignature(canonical), replay);
            }
        }
    }

    @Test void producerScopedSignaturesKeepOwnerLifecycleAndSchemaChecks() throws Exception {
        var environment = schemas();
        var metadata = builder(environment).build();
        var options = RuntimeOptions.defaults();
        var key = ModuleLifecycle.newArtifactKey(options, metadata);
        var lifecycle = ModuleLifecycle.forArtifact(MODULE, key);
        var authority = lifecycle.closureAuthority();
        String canonical = "Fn<" + type("First") + ";@nil" + type("Second") + ">";
        var expected = LyraSignature.of(List.of(type("First")), type("Second").nilable());
        assertEquals(expected, authority.resolveSignature(canonical));
        lifecycle.open();
        assertSame(authority.resolveSignature(canonical), authority.resolveSignature(canonical));
        assertThrows(LyraLinkException.class, () -> authority.resolveSignature("Fn<;" + type("Missing") + ">"));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { authority.resolveSignature(canonical); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        thread.start();
        thread.join();
        assertInstanceOf(LyraThreadException.class, failure.get());
        var linkedOptions = new RuntimeOptions(options.owner(), options.ioEnvironment(), options.profile(),
                options.runtimeAbi(), options.previewEnabled(), key);
        assertSame(key, ModuleLifecycle.newArtifactKey(linkedOptions, metadata));
        assertThrows(LyraLinkException.class, () -> ModuleLifecycle.newArtifactKey(linkedOptions, builder().build()));
        lifecycle.close();
        assertThrows(LyraClosedException.class, () -> authority.resolveSignature(canonical));
        var otherLifecycle = new ModuleLifecycle();
        assertThrows(LyraLinkException.class, () -> otherLifecycle.closureAuthority().resolveSignature(canonical));
        otherLifecycle.open();
        otherLifecycle.close();
    }

    @Test void seededSchemaPublicationsRejectIndependentMetadataMutations() {
        int cases = Integer.getInteger("lyra.fuzz.cases", 60);
        for (long seed : new long[] { 379, 20260911 }) {
            var random = new java.util.Random(seed);
            for (int index = 0; index < cases; index++) {
                var nominal = type("Node" + index);
                var fields = new java.util.ArrayList<NominalSchema.Member>();
                var required = new java.util.ArrayList<LyraType>();
                int count = 1 + random.nextInt(5);
                for (int field = 0; field < count; field++) {
                    LyraType contract = random.nextBoolean() ? PrimitiveType.I32 : nominal.nilable();
                    if (random.nextBoolean()) contract = ArrayType.of(contract);
                    boolean initialized = random.nextBoolean();
                    fields.add(new NominalSchema.Member("field" + field, contract, true, random.nextBoolean(), initialized));
                    if (!initialized) required.add(contract);
                }
                var environment = new NominalTypeEnvironment(List.of(new NominalSchema(nominal,
                        NominalSchema.Kind.STRUCT, fields, required)));
                var metadata = builder(environment).build();
                String replay = "seed=" + seed + ", index=" + index;
                var decoded = ArtifactMetadataReader.read(metadata.canonicalUtf8());
                assertEquals(fields, decoded.nominalSchemas().require(nominal).members(), replay);
                assertEquals(required, decoded.nominalSchemas().require(nominal).constructorParameters(), replay);
                String corrupted = metadata.canonicalJson().replace("\"name\":\"field0\"", "\"name\":\"changed\"");
                assertThrows(LyraCompatibilityException.class, () -> ArtifactMetadataReader.read(corrupted), replay);
            }
        }
    }

    private static final ModuleId MODULE = ModuleId.path("types.lyra");
    private static final String SHA = "a".repeat(64);

    private static NominalType type(String name) {
        return new NominalType(new NominalTypeId(MODULE, SHA, name, 0));
    }

    private static ArtifactMetadata.Builder builder() {
        return builder(NominalTypeEnvironment.empty());
    }

    private static ArtifactRevision revision(NominalTypeEnvironment environment, Map<String, String> names) {
        var base = ArtifactRevision.compute("test", List.of(new ModuleMetadata(MODULE, SHA, "types.lyra")),
                names, RuntimeProfile.CURRENT, PackagingMode.CLASSES, false,
                List.of(new SourceMetadata(MODULE.sourceId(), "types.lyra", SHA)));
        return ArtifactRevision.bindNominalSchemas(base, environment);
    }

    private static ArtifactMetadata.Builder builder(NominalTypeEnvironment environment) {
        return ArtifactMetadata.builder().compilerVersion("test").compilerBuild("test")
                .artifactId("nominal-contract").artifactRevision(revision(environment, Map.of()))
                .nominalSchemas(environment)
                .rootModuleId(MODULE).rootModuleRevision(SHA)
                .modules(List.of(new ModuleMetadata(MODULE, SHA, "types.lyra")))
                .sources(List.of(new SourceMetadata(MODULE.sourceId(), "types.lyra", SHA)))
                .debugMapHash(SHA);
    }

    private static NominalTypeEnvironment schemas() {
        var first = type("First");
        var second = type("Second");
        return new NominalTypeEnvironment(List.of(
                new NominalSchema(first, NominalSchema.Kind.STRUCT,
                        List.of(new NominalSchema.Member("next", second.nilable(), true, true, false)), List.of(second.nilable())),
                new NominalSchema(second, NominalSchema.Kind.STRUCT,
                        List.of(new NominalSchema.Member("next", first.nilable(), true, true, false)), List.of(first.nilable()))));
    }

    @Test void recursiveSchemasPrecedeAndAuthenticateExportContracts() {
        var environment = schemas();
        var export = new ExportMetadata(MODULE, "instance", type("First"),
                "()Llyra/generated/$lyra$nominal$" + type("First").id().stableHash() + ";");
        var names = Map.of(export.id().id(), export.javaName());
        var metadata = builder(environment).exports(List.of(export))
                .javaNameMap(names).artifactRevision(revision(environment, names)).build();
        assertEquals(2, metadata.schemaVersion());
        var decoded = ArtifactMetadataReader.read(metadata.canonicalUtf8());
        assertEquals(metadata, decoded);
        assertEquals(metadata.hashCode(), decoded.hashCode());
        assertArrayEquals(metadata.canonicalUtf8(), decoded.canonicalUtf8());
        assertEquals(environment.schemas(), decoded.nominalSchemas().schemas());
        assertEquals(type("First"), decoded.exports().getFirst().contract());
        assertThrows(IllegalArgumentException.class, () -> builder().exports(List.of(export))
                .javaNameMap(Map.of(export.id().id(), export.javaName())).build());
        assertThrows(IllegalArgumentException.class, () -> builder().nominalSchemas(environment).schemaVersion(1).build());
        assertThrows(IllegalArgumentException.class, () -> builder().schemaVersion(2).build());
    }

    @Test void legacyMetadataRemainsVersionOneAndNominalForgeriesReject() {
        var legacy = builder().build();
        assertEquals(1, legacy.schemaVersion());
        assertFalse(legacy.canonicalJson().contains("nominalSchemas"));
        assertEquals(legacy, ArtifactMetadataReader.read(legacy.canonicalUtf8()));
        String json = builder(schemas()).build().canonicalJson();
        for (String malformed : List.of(
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                json.replace("\"occurrence\":0", "\"occurrence\":1"),
                json.replace("\"mutable\":true", "\"mutable\":\"true\""),
                json.replace("\"mutable\":true", "\"mutable\":false"),
                json.replace("\"public\":true", "\"public\":false"),
                json.replace("\"kind\":\"STRUCT\"", "\"kind\":\"RECORD\""),
                json.replace("\"kind\":\"STRUCT\"", "\"kind\":\"CLASS\""),
                json.replace("\"type\":\"@nil" + type("First") + "\"",
                        "\"type\":\"@nilNominal<" + "0".repeat(64) + ">\""),
                json.replace("\"name\":\"next\"", "\"name\":\"bad-name\""),
                json.replace("\"nominalSchemas\":", "\"unknownSchemas\":"),
                json.replace(type("First").canonicalSpelling(), "Nominal<" + "0".repeat(64) + ">"))) {
            assertThrows(LyraCompatibilityException.class, () -> ArtifactMetadataReader.read(malformed), malformed);
        }
    }
}
