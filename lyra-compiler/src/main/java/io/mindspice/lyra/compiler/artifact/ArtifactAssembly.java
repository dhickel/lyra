package io.mindspice.lyra.compiler.artifact;

import io.mindspice.lyra.compiler.backend.jvm.JvmBytecodeArtifact;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.api.WriteOptions;
import io.mindspice.lyra.runtime.ArtifactDependency;
import io.mindspice.lyra.runtime.ArtifactHook;
import io.mindspice.lyra.runtime.ArtifactImport;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactProfile;
import io.mindspice.lyra.runtime.AttachmentContext;
import io.mindspice.lyra.runtime.ArtifactSource;
import io.mindspice.lyra.runtime.ArtifactRevision;
import io.mindspice.lyra.runtime.BindingMutability;
import io.mindspice.lyra.runtime.DebugMapMetadata;
import io.mindspice.lyra.runtime.ExportMetadata;
import io.mindspice.lyra.runtime.ModuleMetadata;
import io.mindspice.lyra.runtime.PackagingMode;
import io.mindspice.lyra.runtime.RuntimeAbi;
import io.mindspice.lyra.runtime.RuntimeProfile;
import io.mindspice.lyra.runtime.RuntimeRequirement;
import io.mindspice.lyra.runtime.SourceMetadata;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Complete immutable internal publication unit for one emitted Lyra graph.
 * It contains generated class bytes and compatibility/debug metadata, but no
 * public serialized IR or live module instance.
 */
public final class ArtifactAssembly implements ArtifactSource {
    public static final String ARTIFACT_METADATA_PATH = "META-INF/lyra/artifact.json";
    public static final String DEBUG_MAP_PATH = "META-INF/lyra/debug-map.json";
    public static final String SOURCES_PATH = "META-INF/lyra/sources/";
    private static final String METADATA_CHUNK_PREFIX = "\u0001LYRA-METADATA-CHUNK-";
    private static final String METADATA_CHUNK_SUFFIX = "\u0001";
    private static final int METADATA_CHUNK_COUNT = 32;
    private static final int METADATA_CHUNK_BYTES = 60_000;

    private final JvmBytecodeArtifact bytecode;
    private final ArtifactMetadata metadata;
    private final DebugMapMetadata debugMap;
    private final Map<String, byte[]> generatedClasses;
    private final Map<String, byte[]> entries;
    private final Map<String, byte[]> sourceEntries;
    private final byte[] artifactJson;
    private final byte[] debugMapJson;

    private ArtifactAssembly(
            JvmBytecodeArtifact bytecode,
            ArtifactMetadata metadata,
            DebugMapMetadata debugMap,
            Map<String, byte[]> generatedClasses,
            Map<String, byte[]> entries,
            Map<String, byte[]> sourceEntries) {
        this.bytecode = Objects.requireNonNull(bytecode, "bytecode");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.debugMap = Objects.requireNonNull(debugMap, "debugMap");
        this.generatedClasses = copyBytes(generatedClasses, "generatedClasses");
        this.entries = copyBytes(entries, "entries");
        this.sourceEntries = copyBytes(sourceEntries, "sourceEntries");
        this.artifactJson = metadata.canonicalUtf8();
        this.debugMapJson = debugMap.canonicalUtf8();
        if (!MessageDigests.sha256Hex(debugMapJson).equals(metadata.debugMapHash())) {
            throw new ArtifactAssemblyException("debug-map hash disagrees with artifact metadata");
        }
    }

    /** Assembles the canonical class-directory form without embedded source text. */
    public static ArtifactAssembly assemble(JvmBytecodeArtifact bytecode) {
        return assemble(bytecode, ArtifactAssemblyOptions.defaults());
    }

    /** Assembles one mode without embedded source text. */
    public static ArtifactAssembly assemble(JvmBytecodeArtifact bytecode, PackagingMode mode) {
        return assemble(bytecode, ArtifactAssemblyOptions.builder().packagingMode(mode).build());
    }

    /** Assembles one mode and optionally embeds canonical source snapshots. */
    public static ArtifactAssembly assemble(JvmBytecodeArtifact bytecode,
                                            PackagingMode mode, boolean includeSources) {
        return assemble(bytecode, ArtifactAssemblyOptions.builder()
                .packagingMode(mode).includeSources(includeSources).build());
    }

    /**
     * Assembles one mode with the versioned debug REPL capability when
     * requested.  Debug publications embed every reachable source snapshot
     * and declare the exact compiler/REPL/runtime closure requirement.
     */
    public static ArtifactAssembly assemble(JvmBytecodeArtifact bytecode,
                                            PackagingMode mode, boolean includeSources,
                                            boolean replCapable) {
        return assemble(bytecode, ArtifactAssemblyOptions.builder()
                .packagingMode(mode).includeSources(includeSources)
                .replCapable(replCapable).build());
    }

    static ArtifactAssembly assemble(JvmBytecodeArtifact bytecode,
                                     ArtifactAssemblyOptions options) {
        Objects.requireNonNull(bytecode, "bytecode");
        Objects.requireNonNull(options, "options");
        bytecode.typedIr().requireValidated();

        Map<String, byte[]> classes = copyBytes(bytecode.classes(), "generated class files");
        validateGeneratedClasses(bytecode, classes, options.replCapable());
        Map<ModuleId, SourceSnapshot> snapshots = sourceSnapshots(bytecode);
        List<ModuleMetadata> modules = moduleMetadata(bytecode, snapshots);
        ArtifactProfile artifactProfile = bytecode.artifactProfile();
        // Debug-capable publications embed every reachable source snapshot
        // even when ordinary include-sources is off; the embedded context is
        // the reconstruction boundary, not an opt-in source omission.
        boolean includeSources = options.includeSources()
                || artifactProfile == ArtifactProfile.ATTACHABLE
                || options.replCapable();
        List<SourceMetadata> sources = sourceMetadata(bytecode, snapshots, includeSources);
        List<ExportMetadata> exports = exportMetadata(bytecode);
        Map<String, String> names = new TreeMap<>();
        for (ExportMetadata export : exports) {
            if (names.put(export.id().id(), export.javaName()) != null) {
                throw new ArtifactAssemblyException("duplicate export ID in artifact metadata: "
                        + export.id().id());
            }
        }

        DebugMapMetadata debugMap = DebugMapBuilder.build(bytecode);
        if (options.packagingMode() == PackagingMode.BUNDLED_JAR) {
            List<ExportMetadata> mains = exports.stream()
                    .filter(export -> export.moduleId().equals(
                            runtimeModuleId(bytecode.typedIr().rootModule().moduleId())))
                    .filter(export -> export.name().equals("main"))
                    .toList();
            if (mains.size() != 1
                    || !mains.getFirst().isFunction()
                    || !mains.getFirst().canonicalContract()
                    .equals("Fn<Array<String>;I32>")) {
                throw new ArtifactAssemblyException(
                        "bundled artifacts require exactly one root export main "
                                + ":Fn<Array<String>;I32> for LyraLauncher execution");
            }
        }
        Map<String, byte[]> runtimeEntries = options.packagingMode() == PackagingMode.BUNDLED_JAR
                ? BundledRuntime.collect(classes.keySet(), options.replCapable()) : Map.of();
        boolean previewRequired = bytecode.previewRequired()
                || runtimeEntries.values().stream().anyMatch(ArtifactAssembly::previewClassFile);
        validateProfile(options.profile(), options.runtimeAbi(), previewRequired);

        ModuleMetadata root = modules.stream()
                .filter(module -> module.id().equals(runtimeModuleId(bytecode.typedIr().rootModule().moduleId())))
                .findFirst().orElseThrow(() -> new ArtifactAssemblyException(
                        "root module is absent from artifact metadata"));
        Optional<RuntimeRequirement> requirement = options.packagingMode() == PackagingMode.THIN_JAR
                ? options.runtimeRequirement()
                : Optional.empty();
        List<ArtifactImport> imports = artifactProfile == ArtifactProfile.NORMAL
                && !options.replCapable() ? List.of() : bytecode.imports();
        List<ArtifactHook> hooks = artifactProfile == ArtifactProfile.ATTACHABLE
                ? List.of(new ArtifactHook("$lyra$attachmentLifecycle",
                        "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                new ArtifactHook("$lyra$attachmentSafePoint", "()V"))
                : List.of();
        List<ArtifactDependency> dependencies = dependencyRequirements(
                artifactProfile, options.replCapable());
        Optional<AttachmentContext> attachmentContext = artifactProfile == ArtifactProfile.ATTACHABLE
                ? Optional.of(attachmentContext(bytecode, modules, sources, options, imports,
                previewRequired))
                : Optional.empty();
        Map<String, String> reproducibleOptions = artifactProfile == ArtifactProfile.NORMAL
                && !options.replCapable() ? Map.of() : bytecode.reproducibleOptions();
        ArtifactRevision baseRevision = ArtifactRevision.compute(
                options.compilerBuild(), modules, names, options.profile(),
                options.packagingMode(), previewRequired, bytecode.javaBasePackage(), sources, requirement,
                artifactProfile, hooks, dependencies, attachmentContext, imports, reproducibleOptions,
                options.replCapable());
        ArtifactRevision revision = ArtifactRevision.bindNominalSchemas(baseRevision, bytecode.nominalSchemas());
        String artifactId = options.artifactId().orElseGet(() -> MessageDigests.sha256Hex(
                "LYRA-ARTIFACT-ID", options.compilerBuild(),
                root.id().canonicalSpelling(), revision.value()));
        ArtifactMetadata metadata = ArtifactMetadata.builder()
                .compilerVersion(options.compilerVersion())
                .compilerBuild(options.compilerBuild())
                .runtimeAbi(options.runtimeAbi())
                .profile(options.profile())
                .javaClassFileTarget(options.profile().javaClassFileTarget())
                .previewRequired(previewRequired)
                .artifactId(artifactId)
                .artifactRevision(revision)
                .rootModuleId(root.id())
                .rootModuleRevision(root.revision())
                .modules(modules)
                .sources(sources)
                .nominalSchemas(bytecode.nominalSchemas())
                .javaPackage(bytecode.javaBasePackage())
                .exports(exports)
                .javaNameMap(names)
                .debugMapVersion(debugMap.schemaVersion())
                .debugMapHash(debugMap.sha256())
                .packagingMode(options.packagingMode())
                .runtimeRequirement(requirement)
                .executionProfile(artifactProfile)
                .hookRequirements(hooks)
                .dependencyRequirements(dependencies)
                .attachmentContext(attachmentContext)
                .imports(imports)
                .reproducibleOptions(reproducibleOptions)
                .replCapable(options.replCapable())
                .build();

        // The emitter must produce facades before packaging metadata exists,
        // so it embeds a provisional metadata string.  Replace that string at
        // the final artifact boundary; otherwise the facade's public metadata
        // would silently describe a Phase-15/classes artifact even when this
        // publication is a thin JAR or includes source/option revisions.
        classes = patchFacadeMetadata(classes, bytecode.javaBasePackage(), metadata.canonicalJson());
        Map<String, byte[]> sourceEntries = sourceEntries(snapshots, sources);
        TreeMap<String, byte[]> allEntries = new TreeMap<>(EntryNames.utf8Comparator());
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            putEntry(allEntries, EntryNames.classEntry(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<String, byte[]> entry : runtimeEntries.entrySet()) {
            validateClosureClass(entry.getKey(), entry.getValue(), options.replCapable());
            putEntry(allEntries, entry.getKey(), entry.getValue());
        }
        putEntry(allEntries, ARTIFACT_METADATA_PATH, metadata.canonicalUtf8());
        putEntry(allEntries, DEBUG_MAP_PATH, debugMap.canonicalUtf8());
        for (Map.Entry<String, byte[]> entry : sourceEntries.entrySet()) {
            putEntry(allEntries, entry.getKey(), entry.getValue());
        }
        return new ArtifactAssembly(bytecode, metadata, debugMap, classes, allEntries,
                sourceEntries);
    }

    public ArtifactMetadata metadata() {
        return metadata;
    }

    public DebugMapMetadata debugMap() {
        return debugMap;
    }

    public JvmBytecodeArtifact bytecodeArtifact() {
        return bytecode;
    }

    public PackagingMode packagingMode() {
        return metadata.packagingMode();
    }

    public byte[] artifactJson() {
        return artifactJson.clone();
    }

    public byte[] debugMapJson() {
        return debugMapJson.clone();
    }

    /** Generated classes keyed by binary name, with a fresh byte copy on each call. */
    public Map<String, byte[]> classes() {
        return copyBytes(generatedClasses, "generatedClasses");
    }

    public Map<String, byte[]> classFiles() {
        return classes();
    }

    /** All non-manifest files that are placed in a class directory/JAR. */
    public Map<String, byte[]> entries() {
        return copyBytes(entries, "entries");
    }

    public Map<String, byte[]> sourceEntries() {
        return copyBytes(sourceEntries, "sourceEntries");
    }

    public List<String> entryNames() {
        return List.copyOf(entries.keySet());
    }

    public Optional<byte[]> entry(String name) {
        Objects.requireNonNull(name, "name");
        byte[] value = entries.get(name);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    /** Internal class-directory publication boundary. */
    void writeClasses(java.nio.file.Path output, ArtifactWriteOptions options)
            throws java.io.IOException {
        if (metadata.packagingMode() != PackagingMode.CLASSES) {
            throw new ArtifactAssemblyException("class-directory output needs classes packaging metadata");
        }
        ArtifactOutputWriter.writeClasses(this, output,
                Objects.requireNonNull(options, "options"));
    }

    /** Internal JAR publication boundary. */
    void writeJar(java.nio.file.Path output, ArtifactWriteOptions options)
            throws java.io.IOException {
        if (metadata.packagingMode() == PackagingMode.CLASSES) {
            throw new ArtifactAssemblyException("JAR output needs thin-jar or bundled-jar metadata");
        }
        ArtifactOutputWriter.writeJar(this, output,
                Objects.requireNonNull(options, "options"));
    }

    /** Writes this assembly as a class directory with public options. */
    public void writeClasses(java.nio.file.Path output, WriteOptions options)
            throws java.io.IOException {
        Objects.requireNonNull(options, "options");
        writeClasses(output, ArtifactWriteOptions.builder().force(options.force()).build());
    }

    /** Writes this assembly as a JAR with public options. */
    public void writeJar(java.nio.file.Path output, WriteOptions options)
            throws java.io.IOException {
        Objects.requireNonNull(options, "options");
        writeJar(output, ArtifactWriteOptions.builder().force(options.force()).build());
    }

    /** Convenience for internal tests and legacy callers. */
    public void writeClasses(java.nio.file.Path output, boolean force)
            throws java.io.IOException {
        writeClasses(output, ArtifactWriteOptions.builder().force(force).build());
    }

    /** Convenience for internal tests and legacy callers. */
    public void writeJar(java.nio.file.Path output, boolean force)
            throws java.io.IOException {
        writeJar(output, ArtifactWriteOptions.builder().force(force).build());
    }

    static String sourceEntryName(SourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isPath()) {
            return SOURCES_PATH + sourceId.value();
        }
        return SOURCES_PATH + "uri-" + MessageDigests.sha256Hex(
                "LYRA-SOURCE-ENTRY", sourceId.value()) + ".lyra";
    }

    private static Map<ModuleId, SourceSnapshot> sourceSnapshots(JvmBytecodeArtifact artifact) {
        TreeMap<ModuleId, SourceSnapshot> result = new TreeMap<>();
        // Retained producers have no emitted body, but their original source remains part of the link/debug inventory.
        for (var module : artifact.typedIr().typedSemanticGraph().modules()) {
            SourceSnapshot snapshot = artifact.typedIr().sourceSnapshot(module.moduleId())
                    .orElseThrow(() -> new ArtifactAssemblyException(
                            "validated typed IR is missing source snapshot: " + module.moduleId()));
            if (!snapshot.sourceId().equals(module.moduleId().sourceId())) {
                throw new ArtifactAssemblyException("source snapshot identity disagrees with IR module");
            }
            if (result.put(module.moduleId(), snapshot) != null) {
                throw new ArtifactAssemblyException("duplicate source snapshot: " + module.moduleId());
            }
        }
        if (result.size() != artifact.typedIr().typedSemanticGraph().modules().size()) {
            throw new ArtifactAssemblyException("source snapshot inventory is incomplete");
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<ModuleMetadata> moduleMetadata(JvmBytecodeArtifact artifact,
                                                        Map<ModuleId, SourceSnapshot> snapshots) {
        ModuleGraph graph = artifact.typedIr().typedSemanticGraph().resolvedGraph().moduleGraph();
        ArrayList<ModuleMetadata> result = new ArrayList<>();
        for (ModuleGraph.Node node : graph.modules()) {
            SourceSnapshot snapshot = snapshots.get(node.moduleId());
            if (snapshot == null) {
                throw new ArtifactAssemblyException("module graph has no typed-IR snapshot: "
                        + node.moduleId());
            }
            if (!node.snapshot().equals(snapshot)) {
                throw new ArtifactAssemblyException("module graph snapshot disagrees with typed IR: "
                        + node.moduleId());
            }
            // The graph owns the revision because it includes the request's
            // canonical semantics-affecting options.  Recomputing here with
            // empty options would reject valid public API requests and would
            // discard those options from the published artifact.
            if (!io.mindspice.lyra.compiler.source.ModuleRevision.isRevision(node.revision())) {
                throw new ArtifactAssemblyException("module revision is invalid: " + node.moduleId());
            }
            result.add(new ModuleMetadata(runtimeModuleId(node.moduleId()),
                    io.mindspice.lyra.runtime.ModuleRevision.of(node.revision()),
                    snapshot.sourceId().value()));
        }
        result.sort(ModuleMetadata::compareTo);
        return List.copyOf(result);
    }

    private static List<SourceMetadata> sourceMetadata(JvmBytecodeArtifact artifact,
                                                       Map<ModuleId, SourceSnapshot> snapshots,
                                                       boolean includeSources) {
        ArrayList<SourceMetadata> result = new ArrayList<>();
        Set<String> sourceEntries = new HashSet<>();
        for (Map.Entry<ModuleId, SourceSnapshot> entry : snapshots.entrySet()) {
            SourceSnapshot snapshot = entry.getValue();
            byte[] bytes = snapshot.utf8Bytes();
            if (!MessageDigests.sha256Hex(bytes).equals(snapshot.sha256())) {
                throw new ArtifactAssemblyException("source snapshot hash is inconsistent: "
                        + snapshot.sourceId());
            }
            Optional<String> sourceEntry = includeSources
                    ? Optional.of(uniqueSourceEntryName(snapshot.sourceId(), sourceEntries))
                    : Optional.empty();
            result.add(new SourceMetadata(runtimeSourceId(snapshot.sourceId()),
                    snapshot.sourceId().value(), snapshot.sha256(), sourceEntry));
        }
        result.sort(SourceMetadata::compareTo);
        return List.copyOf(result);
    }

    /**
     * Keeps the historical path entry spelling where possible while ensuring
     * a path source can never collide with the hash-based URI spelling.  The
     * source identity kind remains explicit in metadata; the suffix is only
     * an entry-name collision escape and is deterministic for a fixed graph.
     */
    private static String uniqueSourceEntryName(SourceId sourceId, Set<String> used) {
        String base = sourceEntryName(sourceId);
        if (used.add(base)) {
            return base;
        }
        String stem = base.endsWith(".lyra")
                ? base.substring(0, base.length() - ".lyra".length()) : base;
        String kind = sourceId.isUri() ? "uri" : "path";
        for (int index = 1; ; index++) {
            String candidate = stem + "-" + kind + (index == 1 ? "" : "-" + index) + ".lyra";
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private static List<ExportMetadata> exportMetadata(JvmBytecodeArtifact artifact) {
        ArrayList<ExportMetadata> result = new ArrayList<>();
        for (JvmBytecodeArtifact.EmittedExport export : artifact.emittedExports()) {
            io.mindspice.lyra.runtime.ModuleId module = runtimeModuleId(export.moduleId());
            io.mindspice.lyra.runtime.LyraType contract =
                    io.mindspice.lyra.runtime.LyraType.parse(export.canonicalSignature(), artifact.nominalSchemas());
            if (!contract.canonicalSpelling().equals(export.canonicalSignature())) {
                throw new ArtifactAssemblyException("export contract is not canonical: "
                        + export.sourceName());
            }
            ExportMetadata metadata = new ExportMetadata(
                    new io.mindspice.lyra.runtime.ExportId(module, export.sourceName(), contract),
                    export.jvmDescriptor(), export.mutable()
                            ? BindingMutability.MUTABLE : BindingMutability.IMMUTABLE,
                    export.javaName(), export.getterName(), export.functionValueName(),
                    export.setterName(), export.declarationIdentity(),
                    export.originDeclarationIdentity());
            if (!metadata.id().id().equals(export.stableId())) {
                throw new ArtifactAssemblyException("export identity disagrees with runtime metadata: "
                        + export.sourceName());
            }
            result.add(metadata);
        }
        result.sort(ExportMetadata::compareTo);
        return List.copyOf(result);
    }

    private static Map<String, byte[]> sourceEntries(Map<ModuleId, SourceSnapshot> snapshots,
                                                      List<SourceMetadata> sources) {
        Map<SourceId, SourceSnapshot> byId = new TreeMap<>(
                java.util.Comparator.comparing(SourceId::value)
                        .thenComparing(SourceId::isUri));
        for (SourceSnapshot snapshot : snapshots.values()) {
            byId.put(snapshot.sourceId(), snapshot);
        }
        TreeMap<String, byte[]> result = new TreeMap<>(EntryNames.utf8Comparator());
        for (SourceMetadata source : sources) {
            String entryName = source.entryName().orElse(null);
            if (entryName == null) {
                continue;
            }
            SourceId compilerSourceId = source.sourceId().isUri()
                    ? SourceId.uri(source.sourceId().asUri())
                    : SourceId.path(source.sourceId().value());
            SourceSnapshot snapshot = byId.get(compilerSourceId);
            if (snapshot == null) {
                throw new ArtifactAssemblyException("embedded source is absent: " + source.sourceId());
            }
            byte[] bytes = snapshot.utf8Bytes();
            if (!MessageDigests.sha256Hex(bytes).equals(source.sha256())) {
                throw new ArtifactAssemblyException("embedded source hash does not match metadata: "
                        + source.sourceId());
            }
            putEntry(result, entryName, bytes);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, byte[]> patchFacadeMetadata(
            Map<String, byte[]> classes, String javaPackage, String metadataJson) {
        String facadePrefix = Objects.requireNonNull(javaPackage, "javaPackage")
                + ".$lyra$facade$";
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        int facadeCount = 0;
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String binaryName = entry.getKey();
            byte[] bytes = entry.getValue();
            if (!binaryName.startsWith(facadePrefix)) {
                result.put(binaryName, bytes.clone());
                continue;
            }
            facadeCount++;
            byte[] patched = replaceEmbeddedMetadata(binaryName, bytes, metadataJson);
            ClassFileReader.read(binaryName, patched);
            result.put(binaryName, patched);
        }
        if (facadeCount == 0) {
            throw new ArtifactAssemblyException("generated artifact has no module facade to publish");
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Replaces the marker-addressable provisional metadata constants in a
     * generated facade.  Class files are a length-prefixed stream, so changing
     * these constants requires rebuilding the tail rather than mutating bytes
     * in place.  No class semantics or member offsets are encoded as absolute
     * file offsets.
     */
    private static byte[] replaceEmbeddedMetadata(
            String binaryName, byte[] bytes, String metadataJson) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(metadataJson, "metadataJson");
        String schemaOnePrefix = "{\"schemaVersion\":1,\"languageContractVersion\":1,\"compilerVersion\":";
        String schemaTwoPrefix = "{\"schemaVersion\":2,\"languageContractVersion\":1,\"compilerVersion\":";
        String provisionalPrefix;
        if (metadataJson.startsWith(schemaOnePrefix)) provisionalPrefix = schemaOnePrefix;
        else if (metadataJson.startsWith(schemaTwoPrefix)) provisionalPrefix = schemaTwoPrefix;
        else throw new ArtifactAssemblyException("unsupported facade metadata publication contract: " + binaryName);
        if (bytes.length < 10 || u4(bytes, 0) != 0xCAFEBABE) {
            throw new ArtifactAssemblyException("facade is not a class file: " + binaryName);
        }
        int[] starts = new int[METADATA_CHUNK_COUNT];
        int[] ends = new int[METADATA_CHUNK_COUNT];
        boolean[] seenChunks = new boolean[METADATA_CHUNK_COUNT];
        byte[][] replacements = new byte[METADATA_CHUNK_COUNT][];
        int constantPoolCount = u2(bytes, 8);
        int offset = 10;
        int matches = 0;
        int legacyMatches = 0;
        int legacyStart = -1;
        int legacyEnd = -1;
        byte[] legacyReplacement = null;
        for (int index = 1; index < constantPoolCount; index++) {
            if (offset >= bytes.length) {
                throw new ArtifactAssemblyException("truncated facade constant pool: " + binaryName);
            }
            int tag = bytes[offset++] & 0xff;
            switch (tag) {
                case 1 -> {
                    if (offset > bytes.length - 2) {
                        throw new ArtifactAssemblyException("truncated facade UTF-8 constant: " + binaryName);
                    }
                    int length = u2(bytes, offset);
                    int dataStart = offset + 2;
                    int dataEnd = dataStart + length;
                    if (dataEnd < dataStart || dataEnd > bytes.length) {
                        throw new ArtifactAssemblyException("truncated facade UTF-8 constant: " + binaryName);
                    }
                    String value = modifiedUtf8(bytes, offset, length, binaryName);
                    if (value.startsWith(provisionalPrefix)
                            && value.endsWith(",\"_lyraProvisional\":true}")) {
                        legacyMatches++;
                        legacyStart = offset;
                        legacyEnd = dataEnd;
                        legacyReplacement = modifiedUtf8Bytes(metadataJson, binaryName);
                    }
                    int chunk = metadataChunkIndex(value);
                    if (chunk >= 0) {
                        if (seenChunks[chunk]) {
                            throw new ArtifactAssemblyException(
                                    "duplicate facade metadata chunk " + chunk + " for " + binaryName);
                        }
                        starts[chunk] = offset;
                        ends[chunk] = dataEnd;
                        seenChunks[chunk] = true;
                        matches++;
                    } else if (chunk == -2) {
                        throw new ArtifactAssemblyException(
                                "invalid facade metadata chunk marker for " + binaryName);
                    }
                    offset = dataEnd;
                }
                case 3, 4 -> offset = checkedAdvance(offset, 4, bytes, binaryName);
                case 5, 6 -> {
                    offset = checkedAdvance(offset, 8, bytes, binaryName);
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset = checkedAdvance(offset, 2, bytes, binaryName);
                case 9, 10, 11, 12, 17, 18 -> offset = checkedAdvance(offset, 4, bytes, binaryName);
                case 15 -> offset = checkedAdvance(offset, 3, bytes, binaryName);
                default -> throw new ArtifactAssemblyException(
                        "invalid facade constant-pool tag: " + tag);
            }
        }
        if (legacyMatches == 1 && matches == 0 && legacyReplacement != null) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    bytes.length - (legacyEnd - legacyStart) + legacyReplacement.length);
            try {
                output.write(bytes, 0, legacyStart);
                output.write(legacyReplacement);
                output.write(bytes, legacyEnd, bytes.length - legacyEnd);
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            return output.toByteArray();
        }
        if (legacyMatches != 0 || matches != METADATA_CHUNK_COUNT) {
            throw new ArtifactAssemblyException("facade metadata marker count is "
                    + (legacyMatches + matches) + " for " + binaryName);
        }
        List<String> chunks = metadataChunks(metadataJson);
        for (int index = 0; index < METADATA_CHUNK_COUNT; index++) {
            replacements[index] = modifiedUtf8Bytes(
                    metadataChunkMarker(index) + chunks.get(index), binaryName);
        }
        ArrayList<Integer> replacementOrder = new ArrayList<>(METADATA_CHUNK_COUNT);
        for (int index = 0; index < METADATA_CHUNK_COUNT; index++) {
            replacementOrder.add(index);
        }
        replacementOrder.sort((left, right) -> Integer.compare(starts[left], starts[right]));
        ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
        try {
            int cursor = 0;
            for (int index : replacementOrder) {
                output.write(bytes, cursor, starts[index] - cursor);
                output.write(replacements[index]);
                cursor = ends[index];
            }
            output.write(bytes, cursor, bytes.length - cursor);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return output.toByteArray();
    }

    private static List<String> metadataChunks(String value) {
        ArrayList<String> result = new ArrayList<>(METADATA_CHUNK_COUNT);
        int offset = 0;
        for (int index = 0; index < METADATA_CHUNK_COUNT; index++) {
            int start = offset;
            int bytes = 0;
            while (offset < value.length()) {
                char character = value.charAt(offset);
                int characterBytes = character == '\u0000' ? 2
                        : character <= 0x7f ? 1
                        : character <= 0x7ff ? 2 : 3;
                if (bytes + characterBytes > METADATA_CHUNK_BYTES) {
                    break;
                }
                bytes += characterBytes;
                offset++;
            }
            if (start == offset && offset < value.length()) {
                throw new ArtifactAssemblyException(
                        "facade metadata exceeds the supported size");
            }
            result.add(value.substring(start, offset));
        }
        if (offset < value.length()) {
            throw new ArtifactAssemblyException(
                    "facade metadata exceeds the supported size");
        }
        while (result.size() < METADATA_CHUNK_COUNT) {
            result.add("");
        }
        return List.copyOf(result);
    }

    private static String metadataChunkMarker(int index) {
        return METADATA_CHUNK_PREFIX + index + METADATA_CHUNK_SUFFIX;
    }

    /** Returns -1 for an ordinary constant and -2 for a malformed marker. */
    private static int metadataChunkIndex(String value) {
        if (!value.startsWith(METADATA_CHUNK_PREFIX)) {
            return -1;
        }
        int suffix = value.indexOf(METADATA_CHUNK_SUFFIX, METADATA_CHUNK_PREFIX.length());
        if (suffix < 0) {
            return -2;
        }
        String digits = value.substring(METADATA_CHUNK_PREFIX.length(), suffix);
        if (digits.isEmpty() || (digits.length() > 1 && digits.charAt(0) == '0')) {
            return -2;
        }
        try {
            int index = Integer.parseInt(digits);
            return index >= 0 && index < METADATA_CHUNK_COUNT ? index : -2;
        } catch (NumberFormatException failure) {
            return -2;
        }
    }

    private static int checkedAdvance(int offset, int length, byte[] bytes, String binaryName) {
        if (length < 0 || offset > bytes.length - length) {
            throw new ArtifactAssemblyException("truncated facade constant pool: " + binaryName);
        }
        return offset + length;
    }

    private static String modifiedUtf8(byte[] bytes, int lengthOffset, int length, String binaryName) {
        byte[] encoded = new byte[length + 2];
        encoded[0] = bytes[lengthOffset];
        encoded[1] = bytes[lengthOffset + 1];
        System.arraycopy(bytes, lengthOffset + 2, encoded, 2, length);
        try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            return input.readUTF();
        } catch (IOException failure) {
            throw new ArtifactAssemblyException("invalid modified UTF-8 in facade: " + binaryName, failure);
        }
    }

    private static byte[] modifiedUtf8Bytes(String value, String binaryName) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeUTF(value);
            data.flush();
            return output.toByteArray();
        } catch (IOException failure) {
            throw new ArtifactAssemblyException("facade metadata is too large: " + binaryName, failure);
        }
    }

    /**
     * The debug closure requirement is fixed: the compiler and REPL
     * distribution plus the runtime at the artifact's own execution profile.
     * Ordinary and attachable publications keep their existing requirement
     * sets so schema-1 compatibility is unchanged.
     */
    private static List<ArtifactDependency> dependencyRequirements(
            ArtifactProfile artifactProfile, boolean replCapable) {
        if (replCapable) {
            ArtifactProfile runtimeProfile = artifactProfile == ArtifactProfile.ATTACHABLE
                    ? ArtifactProfile.ATTACHABLE : ArtifactProfile.NORMAL;
            return List.of(
                    new ArtifactDependency("io.mindspice", "lyra-compiler",
                            io.mindspice.lyra.runtime.LyraRuntimeConstants.COMPILER_VERSION,
                            ArtifactProfile.NORMAL),
                    new ArtifactDependency("io.mindspice", "lyra-repl",
                            io.mindspice.lyra.runtime.LyraRuntimeConstants.REPL_VERSION,
                            ArtifactProfile.NORMAL),
                    new ArtifactDependency("io.mindspice", "lyra-runtime",
                            io.mindspice.lyra.runtime.LyraRuntimeConstants.RUNTIME_VERSION,
                            runtimeProfile));
        }
        if (artifactProfile == ArtifactProfile.ATTACHABLE) {
            return List.of(new ArtifactDependency("io.mindspice", "lyra-runtime",
                    io.mindspice.lyra.runtime.LyraRuntimeConstants.RUNTIME_VERSION,
                    ArtifactProfile.ATTACHABLE));
        }
        return List.of();
    }

    private static void validateClosureClass(String entryName, byte[] bytes, boolean replCapable) {
        boolean runtimeEntry = entryName.startsWith("io/mindspice/lyra/runtime/");
        boolean productionClosure = replCapable
                && (entryName.startsWith("io/mindspice/lyra/compiler/")
                || entryName.startsWith("io/mindspice/lyra/repl/"));
        if ((!runtimeEntry && !productionClosure) || !entryName.endsWith(".class")) {
            throw new ArtifactAssemblyException(
                    "bundled production closure contains an invalid entry: " + entryName);
        }
        String binaryName = entryName.substring(0, entryName.length() - ".class".length())
                .replace('/', '.');
        ClassFileReader.read(binaryName, bytes);
    }

    private static void validateGeneratedClasses(JvmBytecodeArtifact artifact,
                                                 Map<String, byte[]> classes,
                                                 boolean replCapable) {
        if (!List.copyOf(classes.keySet()).equals(artifact.classNames())) {
            throw new ArtifactAssemblyException("generated class inventory is not the validated plan inventory");
        }
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String binaryName = entry.getKey();
            if (binaryName.startsWith("io.mindspice.lyra.runtime.")
                    || replCapable && (binaryName.startsWith("io.mindspice.lyra.compiler.")
                    || binaryName.startsWith("io.mindspice.lyra.repl."))) {
                throw new ArtifactAssemblyException(
                        replCapable && !binaryName.startsWith("io.mindspice.lyra.runtime.")
                                ? "debug-capable generated classes may not occupy the bundled production closure namespace: "
                                + binaryName
                                : "generated classes may not occupy the shared runtime namespace: "
                                + binaryName);
            }
            ClassFileReader.ParsedClass parsed = ClassFileReader.read(entry.getKey(), entry.getValue());
            EntryNames.require(entry.getKey().replace('.', '/') + ".class");
            Set<String> actualMembers = new HashSet<>();
            parsed.fields().forEach(field -> actualMembers.add(field.name() + field.descriptor()));
            parsed.methods().forEach(method -> actualMembers.add(method.name() + method.descriptor()));
            String prefix = entry.getKey() + "#";
            Set<String> expectedMembers = new HashSet<>();
            artifact.descriptors().forEach((key, descriptor) -> {
                if (key.startsWith(prefix)) {
                    expectedMembers.add(key.substring(prefix.length()));
                }
            });
            if (!actualMembers.equals(expectedMembers)) {
                throw new ArtifactAssemblyException("class member inventory disagrees with its type plan: "
                        + entry.getKey());
            }
        }
    }

    private static void validateProfile(RuntimeProfile profile, RuntimeAbi abi,
                                        boolean previewRequired) {
        if (profile.javaClassFileTarget() != 25 || !profile.name().equals("java-25")) {
            throw new ArtifactAssemblyException("artifact profile must be java-25");
        }
        if (!profile.runtimeAbi().equals(abi)) {
            throw new ArtifactAssemblyException("artifact runtime ABI disagrees with profile");
        }
        if (previewRequired && !profile.previewSupported()) {
            throw new ArtifactAssemblyException("preview class files need a preview-capable profile");
        }
    }

    private static AttachmentContext attachmentContext(JvmBytecodeArtifact bytecode,
                                                        List<ModuleMetadata> modules,
                                                        List<SourceMetadata> sources,
                                                        ArtifactAssemblyOptions options,
                                                        List<ArtifactImport> imports,
                                                        boolean previewRequired) {
        io.mindspice.lyra.runtime.ModuleId root = runtimeModuleId(
                bytecode.typedIr().rootModule().moduleId());
        ModuleMetadata rootMetadata = modules.stream().filter(module -> module.id().equals(root))
                .findFirst().orElseThrow(() -> new ArtifactAssemblyException(
                        "attachable root is absent from module metadata"));
        ArrayList<String> graphParts = new ArrayList<>();
        for (ModuleMetadata module : modules) {
            graphParts.add(module.id().canonicalSpelling() + "=" + module.revision().value());
        }
        for (ArtifactImport imported : imports) {
            graphParts.add(imported.fromModule().canonicalSpelling() + "->"
                    + imported.logicalTarget() + "=" + imported.targetModule().canonicalSpelling()
                    + "@" + imported.startOffset() + ":" + imported.endOffset());
        }
        String graphRevision = MessageDigests.sha256Hex("LYRA-ATTACHMENT-GRAPH",
                graphParts.toArray(String[]::new));
        ArrayList<String> sourceParts = new ArrayList<>();
        for (SourceMetadata source : sources) {
            sourceParts.add(source.sourceId().canonicalSpelling() + "=" + source.sha256()
                    + "#" + source.entryName().orElse(""));
        }
        String sourceInventoryRevision = MessageDigests.sha256Hex("LYRA-ATTACHMENT-SOURCES",
                sourceParts.toArray(String[]::new));
        ArrayList<String> optionParts = new ArrayList<>();
        optionParts.add(options.compilerVersion());
        optionParts.add(options.compilerBuild());
        optionParts.add(options.profile().name());
        optionParts.add(Integer.toString(options.profile().javaClassFileTarget()));
        optionParts.add(Integer.toString(options.runtimeAbi().major()));
        optionParts.add(Integer.toString(options.runtimeAbi().minor()));
        optionParts.add(Boolean.toString(previewRequired));
        optionParts.add(bytecode.javaBasePackage());
        // Packaging mode and include-sources are deployment/presentation
        // options recorded elsewhere in the metadata; they never affect the
        // analysis, so packaged activation reconstruction (which is always a
        // classes assembly) must be able to reproduce this revision exactly.
        if (options.replCapable()) {
            optionParts.add("repl-capable");
        }
        bytecode.reproducibleOptions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> optionParts.add(entry.getKey() + "=" + entry.getValue()));
        String optionsRevision = MessageDigests.sha256Hex("LYRA-ATTACHMENT-OPTIONS",
                optionParts.toArray(String[]::new));
        return new AttachmentContext(root, rootMetadata.revision(), graphRevision,
                sourceInventoryRevision, optionsRevision, bytecode.javaBasePackage());
    }

    private static io.mindspice.lyra.runtime.ModuleId runtimeModuleId(ModuleId module) {
        return module.isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(module.asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(module.value());
    }

    private static io.mindspice.lyra.runtime.SourceId runtimeSourceId(SourceId source) {
        return source.isUri()
                ? io.mindspice.lyra.runtime.SourceId.uri(source.asUri())
                : io.mindspice.lyra.runtime.SourceId.path(source.value());
    }

    private static void putEntry(Map<String, byte[]> entries, String name, byte[] bytes) {
        EntryNames.require(name);
        if (entries.put(name, bytes.clone()) != null) {
            throw new ArtifactAssemblyException("duplicate artifact entry: " + name);
        }
    }

    private static Map<String, byte[]> copyBytes(Map<String, byte[]> values, String label) {
        Objects.requireNonNull(values, label);
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : values.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), label + " contains a null key");
            byte[] bytes = Objects.requireNonNull(entry.getValue(), label + " contains null bytes");
            if (result.put(name, bytes.clone()) != null) {
                throw new ArtifactAssemblyException("duplicate " + label + " entry: " + name);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static int u2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int u4(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static boolean previewClassFile(byte[] bytes) {
        return bytes.length >= 6 && (bytes[4] & 0xff) == 0xff && (bytes[5] & 0xff) == 0xff;
    }

    private static final class MessageDigests {
        private MessageDigests() {
        }

        static String sha256Hex(byte[] value) {
            return hex(digest(value));
        }

        static String sha256Hex(String domain, String... values) {
            MessageDigest digest = sha256();
            put(digest, domain);
            for (String value : values) {
                put(digest, value);
            }
            return hex(digest.digest());
        }

        private static byte[] digest(byte[] value) {
            MessageDigest digest = sha256();
            digest.update(Objects.requireNonNull(value, "value"));
            return digest.digest();
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private static void put(MessageDigest digest, String value) {
            byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }

        private static String hex(byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
    }
}
