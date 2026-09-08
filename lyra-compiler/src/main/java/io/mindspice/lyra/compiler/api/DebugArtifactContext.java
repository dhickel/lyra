package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.runtime.ArtifactImport;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactProfile;
import io.mindspice.lyra.runtime.ArtifactSource;
import io.mindspice.lyra.runtime.LyraCompatibilityException;
import io.mindspice.lyra.runtime.SourceMetadata;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable reconstruction inventory read from a debug-capable packaged
 * artifact.
 *
 * <p>The inventory contains the complete original reachable source
 * snapshots, the canonical import resolution topology, and the reproducible
 * scalar compilation options.  Reconstruction re-derives semantic facts
 * through the ordinary compiler pipeline from this embedded data: it never
 * re-queries resolver objects, never touches edited or deleted original
 * files, never executes module initializers, and never deserializes IR.</p>
 */
public final class DebugArtifactContext {
    private final ArtifactMetadata metadata;
    private final List<SourceSnapshot> sources;
    private final List<ArtifactImport> imports;
    private final Map<String, String> reproducibleOptions;
    private final ModuleId rootModule;

    private DebugArtifactContext(ArtifactMetadata metadata,
                                 List<SourceSnapshot> sources,
                                 List<ArtifactImport> imports,
                                 Map<String, String> reproducibleOptions,
                                 ModuleId rootModule) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.sources = List.copyOf(sources);
        this.imports = List.copyOf(imports);
        this.reproducibleOptions = Map.copyOf(reproducibleOptions);
        this.rootModule = Objects.requireNonNull(rootModule, "rootModule");
    }

    /**
     * Reads the complete embedded source/resolution context from a
     * debug-capable artifact.  Missing capability, absent embedded entries,
     * hash mismatches, invalid UTF-8, and inconsistent import topology are
     * structured compatibility errors.
     */
    public static DebugArtifactContext read(ArtifactSource artifact) {
        Objects.requireNonNull(artifact, "artifact");
        ArtifactMetadata metadata;
        try {
            metadata = Objects.requireNonNull(artifact.metadata(), "artifact metadata");
        } catch (RuntimeException failure) {
            throw compatibility("artifact has no readable metadata view", failure);
        }
        if (metadata.replCapability().isEmpty()) {
            throw compatibility("artifact does not declare a debug REPL capability; "
                    + "source reconstruction requires a debug-capable publication", null);
        }
        ModuleId rootModule = compilerModuleId(metadata.rootModuleId());
        Map<ModuleId, SourceSnapshot> byModule = readSources(artifact, metadata);
        validateCoverage(byModule, metadata);
        validateImports(metadata);
        return new DebugArtifactContext(metadata, orderedSources(byModule, metadata),
                metadata.imports(), metadata.reproducibleOptions(), rootModule);
    }

    /** The artifact this inventory was read from. */
    public ArtifactMetadata metadata() {
        return metadata;
    }

    /** Original reachable source snapshots in stable module order. */
    public List<SourceSnapshot> sources() {
        return sources;
    }

    /** Canonical import resolution topology with exact source spans. */
    public List<ArtifactImport> imports() {
        return imports;
    }

    /** Exact reproducible scalar compilation options of the original build. */
    public Map<String, String> reproducibleOptions() {
        return reproducibleOptions;
    }

    /** The canonical root module identity. */
    public ModuleId rootModule() {
        return rootModule;
    }

    /**
     * Builds a compile request that re-runs the ordinary compiler pipeline
     * purely from embedded data.  The root is supplied as captured source
     * text and every imported module resolves through a fresh in-memory
     * resolver built from the embedded snapshots; no original resolver
     * object, file, or initializer participates.
     */
    public CompileRequest compileRequest() {
        SourceSnapshot root = snapshotFor(rootModule);
        LinkedHashMap<LogicalModuleId, ResolvedSource> resolverInputs = new LinkedHashMap<>();
        for (SourceSnapshot snapshot : sources) {
            if (snapshot.sourceId().equals(rootModule.sourceId())) {
                continue;
            }
            for (ArtifactImport imported : imports) {
                if (compilerModuleId(imported.targetModule()).sourceId().equals(snapshot.sourceId())) {
                    resolverInputs.putIfAbsent(
                            LogicalModuleId.parse(imported.logicalTarget()),
                            ResolvedSource.fromSnapshot(
                                    LogicalModuleId.parse(imported.logicalTarget()), snapshot));
                }
            }
        }
        io.mindspice.lyra.compiler.api.SourceResolver resolver =
                io.mindspice.lyra.compiler.api.SourceResolver.inMemory(resolverInputs);
        return CompileRequest.builder()
                .rootSource(root.sourceId(), root.text())
                .resolver(resolver)
                .javaBasePackage(metadata.javaPackage())
                .semanticOptions(reproducibleOptions)
                .profile(metadata.artifactProfile() == ArtifactProfile.ATTACHABLE
                        ? CompileProfile.ATTACHABLE : CompileProfile.NORMAL)
                // The reconstructed publication carries the same declared
                // debug capability so its topology/context inventory remains
                // comparable with the original publication.
                .debugCapable(true)
                .build();
    }

    /**
     * Recompiles the embedded graph and verifies that the recorded module
     * revisions, import topology, reproducible options, and graph/source
     * inventory identities are reproduced exactly.  Compilation performs no
     * source execution; a rebuilt mismatch is a structured compatibility
     * error rather than a silently accepted context.
     */
    public CompileResult rebuild() {
        CompileResult result;
        try {
            result = LyraCompiler.compile(compileRequest());
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw compatibility("embedded sources cannot be recompiled", failure);
        }
        if (result instanceof CompileResult.Failure failure) {
            throw compatibility("embedded sources do not reconstruct a compilable graph: "
                    + failure.diagnostics(), null);
        }
        verifyRebuilt(((CompileResult.Success) result).artifact().metadata());
        return result;
    }

    /**
     * Rebuilds the source-independent attachable root context from the
     * embedded snapshots through the ordinary attachable compiler pipeline.
     * This is the activation reconstruction seam for packaged artifacts:
     * no resolver object, original file, initializer, or serialized IR
     * participates, and a rebuilt mismatch is a structured compatibility
     * error.
     */
    public AttachableRootContext attachableContext() {
        if (metadata.artifactProfile() != ArtifactProfile.ATTACHABLE) {
            throw compatibility(
                    "REPL activation requires an artifact compiled with the attachable "
                            + "profile; rebuild it with compile --repl", null);
        }
        AttachableCompileResult result;
        try {
            result = LyraCompiler.compileAttachable(compileRequest());
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw compatibility("embedded sources cannot be recompiled for attachment", failure);
        }
        if (result instanceof AttachableCompileResult.Failure failure) {
            throw compatibility("embedded sources do not reconstruct an attachable "
                    + "compilation: " + failure.diagnostics(), null);
        }
        AttachableCompileResult.Success success = (AttachableCompileResult.Success) result;
        verifyRebuilt(success.artifact().metadata());
        return success.context();
    }

    private void verifyRebuilt(ArtifactMetadata rebuilt) {
        List<io.mindspice.lyra.runtime.ModuleMetadata> originalModules = metadata.modules();
        List<io.mindspice.lyra.runtime.ModuleMetadata> rebuiltModules = rebuilt.modules();
        if (!originalModules.equals(rebuiltModules)) {
            throw compatibility("embedded sources do not reconstruct the recorded module revisions", null);
        }
        if (!metadata.rootModuleRevision().equals(rebuilt.rootModuleRevision())) {
            throw compatibility("embedded sources do not reconstruct the recorded root revision", null);
        }
        if (!metadata.imports().equals(rebuilt.imports())) {
            throw compatibility("embedded sources do not reconstruct the recorded import topology", null);
        }
        if (!metadata.reproducibleOptions().equals(rebuilt.reproducibleOptions())) {
            throw compatibility("embedded sources do not reconstruct the recorded options", null);
        }
        if (metadata.attachmentContext().isPresent()) {
            io.mindspice.lyra.runtime.AttachmentContext original = metadata.attachmentContext().orElseThrow();
            io.mindspice.lyra.runtime.AttachmentContext derived = rebuilt.attachmentContext().orElseThrow(() ->
                    compatibility("rebuilt attachable context is missing", null));
            if (!original.graphRevision().equals(derived.graphRevision())
                    || !original.sourceInventoryRevision().equals(derived.sourceInventoryRevision())
                    || !original.optionsRevision().equals(derived.optionsRevision())) {
                throw compatibility(
                        "embedded sources do not reconstruct the recorded graph/source/options context", null);
            }
        }
    }

    private SourceSnapshot snapshotFor(ModuleId module) {
        for (SourceSnapshot snapshot : sources) {
            if (snapshot.sourceId().equals(module.sourceId())) {
                return snapshot;
            }
        }
        throw compatibility("embedded source inventory is missing the root module "
                + module.sourceId(), null);
    }

    private static List<SourceSnapshot> orderedSources(Map<ModuleId, SourceSnapshot> byModule,
                                                        ArtifactMetadata metadata) {
        ArrayList<SourceSnapshot> result = new ArrayList<>();
        for (io.mindspice.lyra.runtime.ModuleMetadata module : metadata.modules()) {
            SourceSnapshot snapshot = byModule.get(compilerModuleId(module.id()));
            if (snapshot == null) {
                throw compatibility("embedded source inventory is missing module " + module.id(), null);
            }
            result.add(snapshot);
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<ModuleId, SourceSnapshot> readSources(ArtifactSource artifact,
                                                              ArtifactMetadata metadata) {
        TreeMap<ModuleId, SourceSnapshot> result = new TreeMap<>();
        for (SourceMetadata source : metadata.sources()) {
            String entry = source.entryName().orElseThrow(() -> compatibility(
                    "debug artifact does not embed source " + source.sourceId(), null));
            byte[] bytes;
            try {
                bytes = artifact.entry(entry).orElseThrow(() -> compatibility(
                        "debug artifact is missing embedded source entry " + entry, null));
            } catch (LyraCompatibilityException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw compatibility("debug artifact has an unreadable source inventory", failure);
            }
            if (!sha256Hex(bytes).equals(source.sha256())) {
                throw compatibility("embedded source hash does not match metadata: " + entry, null);
            }
            SourceId compilerSource = source.sourceId().isUri()
                    ? SourceId.uri(source.sourceId().asUri())
                    : SourceId.path(source.sourceId().value());
            PhaseResult<SourceSnapshot> captured = SourceSnapshot.capture(
                    compilerSource, memoryPhysicalKey(compilerSource, new String(
                            bytes, StandardCharsets.UTF_8)), bytes);
            if (!(captured instanceof PhaseResult.Success<SourceSnapshot> success)) {
                throw compatibility("embedded source is not a valid UTF-8 snapshot: "
                        + entry + " (" + captured.diagnostics() + ")", null);
            }
            ModuleId module = compilerModuleId(io.mindspice.lyra.runtime.ModuleId.fromSourceId(
                    source.sourceId()));
            if (result.put(module, success.value()) != null) {
                throw compatibility("embedded source inventory duplicates module " + module, null);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateCoverage(Map<ModuleId, SourceSnapshot> byModule,
                                          ArtifactMetadata metadata) {
        for (io.mindspice.lyra.runtime.ModuleMetadata module : metadata.modules()) {
            if (!byModule.containsKey(compilerModuleId(module.id()))) {
                throw compatibility("embedded source inventory is missing module "
                        + module.id(), null);
            }
        }
    }

    private static void validateImports(ArtifactMetadata metadata) {
        java.util.HashSet<String> moduleSpellings = new java.util.HashSet<>();
        for (io.mindspice.lyra.runtime.ModuleMetadata module : metadata.modules()) {
            moduleSpellings.add(module.id().canonicalSpelling());
        }
        for (ArtifactImport imported : metadata.imports()) {
            if (!moduleSpellings.contains(imported.fromModule().canonicalSpelling())
                    || !moduleSpellings.contains(imported.targetModule().canonicalSpelling())) {
                throw compatibility("artifact import topology refers to a module outside the "
                        + "embedded inventory: " + imported, null);
            }
        }
    }

    /** Deterministic memory key matching the compiler's in-memory capture scheme. */
    private static PhysicalSourceKey memoryPhysicalKey(SourceId sourceId, String text) {
        String identity = sourceId.toString() + "\u0000" + text;
        String hash = sha256Hex(identity.getBytes(StandardCharsets.UTF_8));
        return PhysicalSourceKey.uri(URI.create("memory:lyra/" + hash));
    }

    private static ModuleId compilerModuleId(io.mindspice.lyra.runtime.ModuleId module) {
        return module.isUri()
                ? ModuleId.uri(module.asUri())
                : ModuleId.path(module.value());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static LyraCompatibilityException compatibility(String summary, Throwable cause) {
        return cause == null ? new LyraCompatibilityException(summary)
                : new LyraCompatibilityException(summary, List.of(), List.of(), cause);
    }
}
