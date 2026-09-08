package io.mindspice.lyra.runtime;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable compatibility metadata for a compiled Lyra artifact.
 *
 * <p>The object describes public ABI and packaging facts only.  It is not a
 * serialized syntax tree, semantic graph, or typed IR.</p>
 */
public final class ArtifactMetadata {
    public static final int SCHEMA_VERSION = LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION;
    public static final int LANGUAGE_CONTRACT_VERSION = LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION;

    private final int schemaVersion;
    private final int languageContractVersion;
    private final String compilerVersion;
    private final String compilerBuild;
    private final String javaPackage;
    private final RuntimeAbi runtimeAbi;
    private final RuntimeProfile profile;
    private final int javaClassFileTarget;
    private final boolean previewRequired;
    private final String artifactId;
    private final ArtifactRevision artifactRevision;
    private final ModuleId rootModuleId;
    private final ModuleRevision rootModuleRevision;
    private final List<ModuleMetadata> modules;
    private final List<SourceMetadata> sources;
    private final List<ExportMetadata> exports;
    private final Map<String, String> javaNameMap;
    private final int debugMapVersion;
    private final String debugMapHash;
    private final PackagingMode packagingMode;
    private final Optional<RuntimeRequirement> runtimeRequirement;
    private final ArtifactProfile executionProfile;
    private final List<ArtifactHook> hookRequirements;
    private final List<ArtifactDependency> dependencyRequirements;
    private final Optional<AttachmentContext> attachmentContext;
    private final List<ArtifactImport> imports;
    private final Map<String, String> reproducibleOptions;
    private final Optional<ReplCapability> replCapability;

    public ArtifactMetadata(
            int schemaVersion,
            int languageContractVersion,
            String compilerVersion,
            String compilerBuild,
            RuntimeAbi runtimeAbi,
            RuntimeProfile profile,
            int javaClassFileTarget,
            boolean previewRequired,
            String artifactId,
            ArtifactRevision artifactRevision,
            ModuleId rootModuleId,
            ModuleRevision rootModuleRevision,
            List<? extends ModuleMetadata> modules,
            List<? extends ExportMetadata> exports,
            Map<String, String> javaNameMap,
            int debugMapVersion,
            String debugMapHash,
            PackagingMode packagingMode,
            Optional<RuntimeRequirement> runtimeRequirement) {
        this(schemaVersion, languageContractVersion, compilerVersion, compilerBuild, runtimeAbi,
                profile, javaClassFileTarget, previewRequired, artifactId, artifactRevision,
                rootModuleId, rootModuleRevision, modules, List.of(), "lyra.generated", exports,
                javaNameMap, debugMapVersion, debugMapHash, packagingMode, runtimeRequirement);
    }

    public ArtifactMetadata(
            int schemaVersion,
            int languageContractVersion,
            String compilerVersion,
            String compilerBuild,
            RuntimeAbi runtimeAbi,
            RuntimeProfile profile,
            int javaClassFileTarget,
            boolean previewRequired,
            String artifactId,
            ArtifactRevision artifactRevision,
            ModuleId rootModuleId,
            ModuleRevision rootModuleRevision,
            List<? extends ModuleMetadata> modules,
            List<? extends SourceMetadata> sources,
            List<? extends ExportMetadata> exports,
            Map<String, String> javaNameMap,
            int debugMapVersion,
            String debugMapHash,
            PackagingMode packagingMode,
            Optional<RuntimeRequirement> runtimeRequirement) {
        this(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId,
                artifactRevision, rootModuleId, rootModuleRevision, modules, sources,
                "lyra.generated", exports, javaNameMap, debugMapVersion, debugMapHash,
                packagingMode, runtimeRequirement);
    }

    public ArtifactMetadata(
            int schemaVersion,
            int languageContractVersion,
            String compilerVersion,
            String compilerBuild,
            RuntimeAbi runtimeAbi,
            RuntimeProfile profile,
            int javaClassFileTarget,
            boolean previewRequired,
            String artifactId,
            ArtifactRevision artifactRevision,
            ModuleId rootModuleId,
            ModuleRevision rootModuleRevision,
            List<? extends ModuleMetadata> modules,
            List<? extends SourceMetadata> sources,
            String javaPackage,
            List<? extends ExportMetadata> exports,
            Map<String, String> javaNameMap,
            int debugMapVersion,
            String debugMapHash,
            PackagingMode packagingMode,
            Optional<RuntimeRequirement> runtimeRequirement) {
        this(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId,
                artifactRevision, rootModuleId, rootModuleRevision, modules, sources,
                javaPackage, exports, javaNameMap, debugMapVersion, debugMapHash,
                packagingMode, runtimeRequirement, ArtifactProfile.NORMAL, List.of(),
                List.of(), Optional.empty(), List.of(), Map.of());
    }

    /** Full metadata form used by session and attachable publications. */
    public ArtifactMetadata(
            int schemaVersion,
            int languageContractVersion,
            String compilerVersion,
            String compilerBuild,
            RuntimeAbi runtimeAbi,
            RuntimeProfile profile,
            int javaClassFileTarget,
            boolean previewRequired,
            String artifactId,
            ArtifactRevision artifactRevision,
            ModuleId rootModuleId,
            ModuleRevision rootModuleRevision,
            List<? extends ModuleMetadata> modules,
            List<? extends SourceMetadata> sources,
            String javaPackage,
            List<? extends ExportMetadata> exports,
            Map<String, String> javaNameMap,
            int debugMapVersion,
            String debugMapHash,
            PackagingMode packagingMode,
            Optional<RuntimeRequirement> runtimeRequirement,
            ArtifactProfile executionProfile,
            List<? extends ArtifactHook> hookRequirements,
            List<? extends ArtifactDependency> dependencyRequirements,
            Optional<AttachmentContext> attachmentContext,
            List<? extends ArtifactImport> imports,
            Map<String, String> reproducibleOptions) {
        this(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId,
                artifactRevision, rootModuleId, rootModuleRevision, modules, sources,
                javaPackage, exports, javaNameMap, debugMapVersion, debugMapHash,
                packagingMode, runtimeRequirement, executionProfile, hookRequirements,
                dependencyRequirements, attachmentContext, imports, reproducibleOptions,
                Optional.empty());
    }

    /** Full metadata form used by session, attachable, and debug publications. */
    public ArtifactMetadata(
            int schemaVersion,
            int languageContractVersion,
            String compilerVersion,
            String compilerBuild,
            RuntimeAbi runtimeAbi,
            RuntimeProfile profile,
            int javaClassFileTarget,
            boolean previewRequired,
            String artifactId,
            ArtifactRevision artifactRevision,
            ModuleId rootModuleId,
            ModuleRevision rootModuleRevision,
            List<? extends ModuleMetadata> modules,
            List<? extends SourceMetadata> sources,
            String javaPackage,
            List<? extends ExportMetadata> exports,
            Map<String, String> javaNameMap,
            int debugMapVersion,
            String debugMapHash,
            PackagingMode packagingMode,
            Optional<RuntimeRequirement> runtimeRequirement,
            ArtifactProfile executionProfile,
            List<? extends ArtifactHook> hookRequirements,
            List<? extends ArtifactDependency> dependencyRequirements,
            Optional<AttachmentContext> attachmentContext,
            List<? extends ArtifactImport> imports,
            Map<String, String> reproducibleOptions,
            Optional<ReplCapability> replCapability) {
        if (schemaVersion != LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION
                || languageContractVersion != LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION
                || debugMapVersion != LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported metadata schema or language version");
        }
        this.schemaVersion = schemaVersion;
        this.languageContractVersion = languageContractVersion;
        this.compilerVersion = text(compilerVersion, "compilerVersion");
        this.compilerBuild = text(compilerBuild, "compilerBuild");
        this.javaPackage = requireJavaPackage(javaPackage);
        this.runtimeAbi = Objects.requireNonNull(runtimeAbi, "runtimeAbi");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (javaClassFileTarget != profile.javaClassFileTarget()) {
            throw new IllegalArgumentException("Java class-file target disagrees with profile");
        }
        if (!runtimeAbi.equals(profile.runtimeAbi())) {
            throw new IllegalArgumentException("runtime ABI disagrees with profile");
        }
        this.javaClassFileTarget = javaClassFileTarget;
        this.previewRequired = previewRequired;
        if (previewRequired && !profile.previewSupported()) {
            throw new IllegalArgumentException("preview artifact requires a preview-capable profile");
        }
        this.artifactId = text(artifactId, "artifactId");
        this.artifactRevision = Objects.requireNonNull(artifactRevision, "artifactRevision");
        this.rootModuleId = Objects.requireNonNull(rootModuleId, "rootModuleId");
        this.rootModuleRevision = Objects.requireNonNull(rootModuleRevision, "rootModuleRevision");
        this.modules = sortedModules(modules);
        this.sources = sortedSources(sources);
        this.exports = sortedExports(exports);
        this.javaNameMap = sortedNames(javaNameMap);
        this.debugMapVersion = debugMapVersion;
        this.debugMapHash = requireRevision(debugMapHash, "debugMapHash");
        this.packagingMode = Objects.requireNonNull(packagingMode, "packagingMode");
        this.runtimeRequirement = Objects.requireNonNull(runtimeRequirement, "runtimeRequirement");
        this.executionProfile = Objects.requireNonNull(executionProfile, "executionProfile");
        this.hookRequirements = sortedHooks(hookRequirements);
        this.dependencyRequirements = sortedDependencies(dependencyRequirements);
        this.attachmentContext = Objects.requireNonNull(attachmentContext, "attachmentContext");
        this.imports = sortedImports(imports);
        this.reproducibleOptions = sortedOptions(reproducibleOptions);
        this.replCapability = Objects.requireNonNull(replCapability, "replCapability");
        validateReplCapability();
        if (executionProfile == ArtifactProfile.NORMAL
                && replCapability.isEmpty()
                && (!this.hookRequirements.isEmpty() || !this.dependencyRequirements.isEmpty()
                || this.attachmentContext.isPresent() || !this.imports.isEmpty()
                || !this.reproducibleOptions.isEmpty())) {
            throw new IllegalArgumentException("normal artifacts cannot carry optional profile context");
        }
        if (executionProfile == ArtifactProfile.SESSION
                && (!this.hookRequirements.isEmpty() || !this.dependencyRequirements.isEmpty()
                || this.attachmentContext.isPresent())) {
            throw new IllegalArgumentException("session artifacts cannot carry attachment hooks or root context");
        }
        if (executionProfile == ArtifactProfile.ATTACHABLE && replCapability.isEmpty()) {
            List<ArtifactHook> expectedHooks = List.of(
                    new ArtifactHook("$lyra$attachmentLifecycle",
                            "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                    new ArtifactHook("$lyra$attachmentSafePoint", "()V"));
            List<ArtifactDependency> expectedDependencies = List.of(
                    new ArtifactDependency("io.mindspice", "lyra-runtime",
                            LyraRuntimeConstants.RUNTIME_VERSION,
                            ArtifactProfile.ATTACHABLE));
            if (!this.hookRequirements.equals(expectedHooks)
                    || !this.dependencyRequirements.equals(expectedDependencies)
                    || this.attachmentContext.isEmpty()) {
                throw new IllegalArgumentException(
                        "attachable artifacts require the declared hooks, runtime dependency, and context");
            }
        }
        if (executionProfile == ArtifactProfile.ATTACHABLE) {
            if (!this.hookRequirements.equals(List.of(
                    new ArtifactHook("$lyra$attachmentLifecycle",
                            "()Lio/mindspice/lyra/runtime/ModuleLifecycle;"),
                    new ArtifactHook("$lyra$attachmentSafePoint", "()V")))
                    || this.attachmentContext.isEmpty()) {
                throw new IllegalArgumentException(
                        "attachable artifacts require the declared hooks and root context");
            }
            AttachmentContext context = this.attachmentContext.orElseThrow();
            if (!context.rootModule().equals(rootModuleId)
                    || !context.rootRevision().equals(rootModuleRevision)
                    || !context.javaPackage().equals(this.javaPackage)) {
                throw new IllegalArgumentException("attachable context does not describe the artifact root");
            }
        }
        if (packagingMode == PackagingMode.THIN_JAR && runtimeRequirement.isEmpty()) {
            throw new IllegalArgumentException("thin artifacts require an external runtime requirement");
        }
        if (packagingMode != PackagingMode.THIN_JAR && runtimeRequirement.isPresent()) {
            throw new IllegalArgumentException("only thin artifacts record an external runtime requirement");
        }
        runtimeRequirement.ifPresent(requirement -> {
            if (!requirement.groupId().equals(LyraRuntimeConstants.RUNTIME_GROUP_ID)
                    || !requirement.artifactId().equals(LyraRuntimeConstants.RUNTIME_ARTIFACT_ID)
                    || !requirement.version().equals(LyraRuntimeConstants.RUNTIME_VERSION)
                    || !requirement.profile().runtimeAbi().equals(requirement.minimumRuntimeAbi())
                    || !runtimeAbi.isCompatibleWith(requirement.minimumRuntimeAbi())
                    || !profile.isCompatibleWith(requirement.profile(), requirement.previewRequired())) {
                throw new IllegalArgumentException("thin runtime requirement exceeds artifact compatibility");
            }
        });
        validateModuleIdentity();
        validateSources();
        validateExports();
        if (executionProfile == ArtifactProfile.ATTACHABLE
                && exports.stream().anyMatch(export -> export.declarationIdentity() < 0
                || export.originDeclarationIdentity() < 0)) {
            throw new IllegalArgumentException(
                    "attachable exports require complete declaration provenance");
        }
        ArtifactRevision expectedRevision = ArtifactRevision.compute(
                this.compilerBuild, this.modules, this.javaNameMap, this.profile,
                this.packagingMode, this.previewRequired, this.javaPackage, this.sources,
                this.runtimeRequirement, this.executionProfile, this.hookRequirements,
                this.dependencyRequirements, this.attachmentContext, this.imports,
                this.reproducibleOptions, this.replCapability.isPresent());
        if (!expectedRevision.equals(this.artifactRevision)) {
            throw new IllegalArgumentException("artifact revision disagrees with metadata inputs");
        }
        validateImports();
    }

    /**
     * Debug-capable publications embed the complete original source/revision
     * context and declare the exact production closure requirement.  The
     * closure is fixed: compiler and REPL distribution plus the runtime at
     * the artifact's own execution profile.  Missing or different entries
     * are configuration errors, never silently accepted layouts.
     */
    private void validateReplCapability() {
        if (replCapability.isEmpty()) {
            return;
        }
        if (executionProfile == ArtifactProfile.SESSION) {
            throw new IllegalArgumentException(
                    "session artifacts cannot declare a debug REPL capability");
        }
        if (sources.isEmpty() || sources.stream().anyMatch(source -> source.entryName().isEmpty())) {
            throw new IllegalArgumentException(
                    "debug-capable artifacts must embed every reachable source snapshot");
        }
        ArtifactProfile runtimeProfile = executionProfile == ArtifactProfile.ATTACHABLE
                ? ArtifactProfile.ATTACHABLE : ArtifactProfile.NORMAL;
        List<ArtifactDependency> expectedClosure = List.of(
                new ArtifactDependency(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                        LyraRuntimeConstants.COMPILER_ARTIFACT_ID,
                        LyraRuntimeConstants.COMPILER_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                        LyraRuntimeConstants.REPL_ARTIFACT_ID,
                        LyraRuntimeConstants.REPL_VERSION, ArtifactProfile.NORMAL),
                new ArtifactDependency(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                        LyraRuntimeConstants.RUNTIME_ARTIFACT_ID,
                        LyraRuntimeConstants.RUNTIME_VERSION, runtimeProfile));
        if (!this.dependencyRequirements.equals(expectedClosure)) {
            throw new IllegalArgumentException(
                    "debug-capable artifacts must declare the exact compiler/REPL/runtime closure requirement");
        }
    }

    private void validateImports() {
        java.util.HashSet<ModuleId> moduleIds = new java.util.HashSet<>();
        for (ModuleMetadata module : modules) {
            moduleIds.add(module.id());
        }
        for (ArtifactImport imported : imports) {
            if (!moduleIds.contains(imported.fromModule())
                    || !moduleIds.contains(imported.targetModule())) {
                throw new IllegalArgumentException(
                        "artifact import refers to a module outside the artifact: " + imported);
            }
            if (imported.startOffset() > imported.endOffset()) {
                throw new IllegalArgumentException(
                        "artifact import has an inverted source span: " + imported);
            }
        }
    }

    public ArtifactMetadata(
            int schemaVersion,
            int languageContractVersion,
            String compilerVersion,
            String compilerBuild,
            RuntimeAbi runtimeAbi,
            int javaClassFileTarget,
            boolean previewRequired,
            String artifactId,
            String artifactRevision,
            ModuleId rootModuleId,
            String rootModuleRevision,
            List<? extends ModuleMetadata> modules,
            List<? extends ExportMetadata> exports,
            Map<String, String> javaNameMap,
            int debugMapVersion,
            String debugMapHash,
            PackagingMode packagingMode) {
        this(schemaVersion, languageContractVersion, compilerVersion, compilerBuild, runtimeAbi,
                new RuntimeProfile(LyraRuntimeConstants.JAVA_PROFILE, javaClassFileTarget, true, runtimeAbi),
                javaClassFileTarget, previewRequired, artifactId, ArtifactRevision.of(artifactRevision),
                rootModuleId, ModuleRevision.of(rootModuleRevision), modules, exports, javaNameMap,
                debugMapVersion, debugMapHash, packagingMode, Optional.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public int schemaVersion() { return schemaVersion; }
    public int languageContractVersion() { return languageContractVersion; }
    public String compilerVersion() { return compilerVersion; }
    public String compilerBuild() { return compilerBuild; }
    public String javaPackage() { return javaPackage; }
    public String basePackage() { return javaPackage; }
    public RuntimeAbi runtimeAbi() { return runtimeAbi; }
    public RuntimeProfile profile() { return profile; }
    public String javaProfile() { return profile.name(); }
    public int javaClassFileTarget() { return javaClassFileTarget; }
    public int javaTarget() { return javaClassFileTarget; }
    public boolean previewRequired() { return previewRequired; }
    public String artifactId() { return artifactId; }
    public ArtifactRevision artifactRevision() { return artifactRevision; }
    public ModuleId rootModuleId() { return rootModuleId; }
    public ModuleRevision rootModuleRevision() { return rootModuleRevision; }
    public List<ModuleMetadata> modules() { return modules; }
    public List<ModuleMetadata> moduleMetadata() { return modules; }
    public List<SourceMetadata> sources() { return sources; }
    public List<SourceMetadata> sourceMetadata() { return sources; }
    public List<ExportMetadata> exports() { return exports; }
    public Map<String, String> javaNameMap() { return javaNameMap; }
    public Map<String, String> nameMap() { return javaNameMap; }
    public int debugMapVersion() { return debugMapVersion; }
    public String debugMapHash() { return debugMapHash; }
    public PackagingMode packagingMode() { return packagingMode; }
    public Optional<RuntimeRequirement> runtimeRequirement() { return runtimeRequirement; }
    public ArtifactProfile executionProfile() { return executionProfile; }
    public ArtifactProfile artifactProfile() { return executionProfile; }
    public List<ArtifactHook> hookRequirements() { return hookRequirements; }
    public List<ArtifactDependency> dependencyRequirements() { return dependencyRequirements; }
    public Optional<AttachmentContext> attachmentContext() { return attachmentContext; }
    public List<ArtifactImport> imports() { return imports; }
    public Map<String, String> reproducibleOptions() { return reproducibleOptions; }
    public Optional<ReplCapability> replCapability() { return replCapability; }

    /** True when this publication declares the debug REPL capability. */
    public boolean replCapable() { return replCapability.isPresent(); }

    /** Canonical no-whitespace JSON used for artifact metadata. */
    public String canonicalJson() {
        StringBuilder result = new StringBuilder();
        boolean[] first = {true};
        result.append('{');
        field(result, first, "schemaVersion", Integer.toString(schemaVersion));
        field(result, first, "languageContractVersion", Integer.toString(languageContractVersion));
        fieldString(result, first, "compilerVersion", compilerVersion);
        fieldString(result, first, "compilerBuild", compilerBuild);
        fieldObject(result, first, "runtimeAbi", runtimeAbiJson(runtimeAbi));
        fieldString(result, first, "profile", profile.name());
        fieldString(result, first, "javaPackage", javaPackage);
        if (!profile.previewSupported()) {
            field(result, first, "previewSupported", "false");
        }
        field(result, first, "javaClassFileTarget", Integer.toString(javaClassFileTarget));
        field(result, first, "previewRequired", Boolean.toString(previewRequired));
        fieldString(result, first, "artifactId", artifactId);
        fieldString(result, first, "artifactRevision", artifactRevision.value());
        fieldString(result, first, "rootModuleId", rootModuleId.canonicalSpelling());
        fieldString(result, first, "rootModuleRevision", rootModuleRevision.value());
        fieldObject(result, first, "modules", modulesJson());
        fieldObject(result, first, "sources", sourcesJson());
        fieldObject(result, first, "exports", exportsJson());
        fieldObject(result, first, "javaNameMap", namesJson());
        field(result, first, "debugMapVersion", Integer.toString(debugMapVersion));
        fieldString(result, first, "debugMapHash", debugMapHash);
        fieldString(result, first, "packagingMode", packagingMode.canonicalSpelling());
        runtimeRequirement.ifPresent(requirement -> fieldObject(
                result, first, "runtimeRequirement", requirementJson(requirement)));
        replCapability.ifPresent(capability -> fieldObject(
                result, first, "replCapability", capability.canonicalJson()));
        if (executionProfile != ArtifactProfile.NORMAL || replCapability.isPresent()) {
            if (executionProfile != ArtifactProfile.NORMAL) {
                fieldString(result, first, "executionProfile", executionProfile.canonicalSpelling());
            }
            fieldObject(result, first, "hookRequirements", hooksJson());
            fieldObject(result, first, "dependencyRequirements", dependenciesJson());
            attachmentContext.ifPresent(context -> fieldObject(
                    result, first, "attachmentContext", attachmentContextJson(context)));
            fieldObject(result, first, "imports", importsJson());
            fieldObject(result, first, "reproducibleOptions", optionsJson());
        }
        return result.append('}').toString();
    }

    public String toJson() { return canonicalJson(); }

    public byte[] canonicalUtf8() {
        try {
            java.nio.ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(canonicalJson()));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("canonical metadata is not valid UTF-8", exception);
        }
    }

    public byte[] utf8Bytes() { return canonicalUtf8().clone(); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArtifactMetadata metadata
                && schemaVersion == metadata.schemaVersion
                && languageContractVersion == metadata.languageContractVersion
                && compilerVersion.equals(metadata.compilerVersion)
                && compilerBuild.equals(metadata.compilerBuild)
                && javaPackage.equals(metadata.javaPackage)
                && runtimeAbi.equals(metadata.runtimeAbi)
                && profile.equals(metadata.profile)
                && javaClassFileTarget == metadata.javaClassFileTarget
                && previewRequired == metadata.previewRequired
                && artifactId.equals(metadata.artifactId)
                && artifactRevision.equals(metadata.artifactRevision)
                && rootModuleId.equals(metadata.rootModuleId)
                && rootModuleRevision.equals(metadata.rootModuleRevision)
                && modules.equals(metadata.modules)
                && sources.equals(metadata.sources)
                && exports.equals(metadata.exports)
                && javaNameMap.equals(metadata.javaNameMap)
                && debugMapVersion == metadata.debugMapVersion
                && debugMapHash.equals(metadata.debugMapHash)
                && packagingMode == metadata.packagingMode
                && runtimeRequirement.equals(metadata.runtimeRequirement)
                && executionProfile == metadata.executionProfile
                && hookRequirements.equals(metadata.hookRequirements)
                && dependencyRequirements.equals(metadata.dependencyRequirements)
                && attachmentContext.equals(metadata.attachmentContext)
                && imports.equals(metadata.imports)
                && reproducibleOptions.equals(metadata.reproducibleOptions)
                && replCapability.equals(metadata.replCapability);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                javaPackage, runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId,
                artifactRevision, rootModuleId, rootModuleRevision, modules, sources, exports, javaNameMap,
                debugMapVersion, debugMapHash, packagingMode, runtimeRequirement, executionProfile,
                hookRequirements, dependencyRequirements, attachmentContext, imports, reproducibleOptions,
                replCapability);
    }

    @Override
    public String toString() {
        return canonicalJson();
    }

    private void validateModuleIdentity() {
        ModuleMetadata root = modules.stream().filter(value -> value.id().equals(rootModuleId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "root module is absent from artifact metadata"));
        if (!root.revision().equals(rootModuleRevision)) {
            throw new IllegalArgumentException("root module revision disagrees with module metadata");
        }
    }

    private void validateSources() {
        // Retain the Phase-13 in-memory constructor contract. Schema-1 bytes
        // are stricter and reject this legacy empty projection in the reader.
        if (sources.isEmpty()) {
            return;
        }
        java.util.HashSet<SourceId> sourceIds = new java.util.HashSet<>();
        java.util.HashSet<String> entryNames = new java.util.HashSet<>();
        java.util.HashSet<ModuleId> moduleIds = new java.util.HashSet<>();
        Boolean sourcesIncluded = null;
        for (ModuleMetadata module : modules) {
            moduleIds.add(module.id());
        }
        for (SourceMetadata source : sources) {
            if (!sourceIds.add(source.sourceId())) {
                throw new IllegalArgumentException("duplicate source metadata: " + source.sourceId());
            }
            boolean included = source.entryName().isPresent();
            if (sourcesIncluded == null) {
                sourcesIncluded = included;
            } else if (sourcesIncluded != included) {
                throw new IllegalArgumentException(
                        "source metadata must include either every source or no sources");
            }
            source.entryName().ifPresent(entry -> {
                if (!entryNames.add(entry)) {
                    throw new IllegalArgumentException("duplicate source entry metadata: " + entry);
                }
            });
            ModuleId module = ModuleId.fromSourceId(source.sourceId());
            if (!moduleIds.contains(module)) {
                throw new IllegalArgumentException("source metadata refers to an absent module: "
                        + source.sourceId());
            }
        }
        if (sourceIds.size() != moduleIds.size()) {
            throw new IllegalArgumentException("source metadata must cover every module");
        }
        for (ModuleId module : moduleIds) {
            if (!sourceIds.contains(module.sourceId())) {
                throw new IllegalArgumentException("source metadata is missing module: " + module);
            }
        }
    }

    private void validateExports() {
        java.util.HashSet<String> exportIds = new java.util.HashSet<>();
        java.util.HashMap<ModuleId, java.util.HashSet<String>> exportNamesByModule =
                new java.util.HashMap<>();
        java.util.HashMap<ModuleId, java.util.HashSet<String>> javaNamesByModule =
                new java.util.HashMap<>();
        for (ExportMetadata export : exports) {
            if (modules.stream().noneMatch(module -> module.id().equals(export.moduleId()))) {
                throw new IllegalArgumentException("export refers to an absent module: " + export);
            }
            String exportId = export.id().id();
            if (!exportIds.add(exportId)) {
                throw new IllegalArgumentException("duplicate export ID: " + exportId);
            }
            java.util.HashSet<String> exportNames = exportNamesByModule.computeIfAbsent(
                    export.moduleId(), ignored -> new java.util.HashSet<>());
            if (!exportNames.add(export.name())) {
                throw new IllegalArgumentException("duplicate export name in module: "
                        + export.moduleId() + "#" + export.name());
            }
            String mappedName = javaNameMap.get(exportId);
            if (!export.javaName().equals(mappedName)) {
                throw new IllegalArgumentException("Java name map disagrees with export metadata: " + export);
            }
            java.util.HashSet<String> moduleNames = javaNamesByModule.computeIfAbsent(
                    export.moduleId(), ignored -> new java.util.HashSet<>());
            if (!moduleNames.add(mappedName)) {
                throw new IllegalArgumentException("Java name map contains a duplicate Java name in module: "
                        + mappedName);
            }
        }
        if (!javaNameMap.keySet().equals(exportIds)) {
            throw new IllegalArgumentException("Java name map must contain exactly the exported IDs");
        }
    }

    private String modulesJson() {
        StringBuilder result = new StringBuilder("[");
        boolean[] first = {true};
        for (ModuleMetadata module : modules) {
            CanonicalJson.comma(result, first);
            result.append('{');
            boolean[] fields = {true};
            fieldString(result, fields, "id", module.id().canonicalSpelling());
            fieldString(result, fields, "revision", module.revision().value());
            fieldString(result, fields, "sourceLabel", module.sourceLabel());
            result.append('}');
        }
        return result.append(']').toString();
    }

    private String sourcesJson() {
        StringBuilder result = new StringBuilder("[");
        boolean[] first = {true};
        for (SourceMetadata source : sources) {
            CanonicalJson.comma(result, first);
            result.append('{');
            boolean[] fields = {true};
            fieldString(result, fields, "sourceKind", source.sourceId().kindTag());
            fieldString(result, fields, "sourceId", source.sourceId().canonicalSpelling());
            fieldString(result, fields, "sourceLabel", source.sourceLabel());
            fieldString(result, fields, "sha256", source.sha256());
            CanonicalJson.comma(result, fields);
            CanonicalJson.fieldName(result, "entry");
            result.append(source.entryName().map(CanonicalJson::quote).orElse("null"));
            result.append('}');
        }
        return result.append(']').toString();
    }

    private String exportsJson() {
        StringBuilder result = new StringBuilder("[");
        boolean[] first = {true};
        for (ExportMetadata export : exports) {
            CanonicalJson.comma(result, first);
            result.append('{');
            boolean[] fields = {true};
            fieldString(result, fields, "id", export.id().id());
            fieldString(result, fields, "moduleId", export.moduleId().canonicalSpelling());
            fieldString(result, fields, "name", export.name());
            fieldString(result, fields, "signature", export.canonicalContract());
            fieldString(result, fields, "jvmDescriptor", export.jvmDescriptor());
            fieldString(result, fields, "bindingMutability", export.bindingMutability().canonicalSpelling());
            fieldString(result, fields, "javaName", export.javaName());
            fieldString(result, fields, "getterName", export.getterName());
            fieldString(result, fields, "functionValueName", export.functionValueName());
            if (export.declarationIdentity() >= 0) {
                field(result, fields, "declarationIdentity", Long.toString(export.declarationIdentity()));
                field(result, fields, "originDeclarationIdentity",
                        Long.toString(export.originDeclarationIdentity()));
            }
            CanonicalJson.comma(result, fields);
            CanonicalJson.fieldName(result, "setterName");
            result.append(export.setterName().map(CanonicalJson::quote).orElse("null"));
            result.append('}');
        }
        return result.append(']').toString();
    }

    private String namesJson() {
        StringBuilder result = new StringBuilder("{");
        boolean[] first = {true};
        for (Map.Entry<String, String> entry : javaNameMap.entrySet()) {
            CanonicalJson.comma(result, first);
            CanonicalJson.stringField(result, entry.getKey(), entry.getValue());
        }
        return result.append('}').toString();
    }

    private static String runtimeAbiJson(RuntimeAbi abi) {
        return "{\"major\":" + abi.major() + ",\"minor\":" + abi.minor() + "}";
    }

    private String hooksJson() {
        StringBuilder result = new StringBuilder("[");
        boolean[] first = {true};
        for (ArtifactHook hook : hookRequirements) {
            CanonicalJson.comma(result, first);
            result.append('{');
            boolean[] fields = {true};
            fieldString(result, fields, "name", hook.name());
            fieldString(result, fields, "descriptor", hook.descriptor());
            result.append('}');
        }
        return result.append(']').toString();
    }

    private String dependenciesJson() {
        StringBuilder result = new StringBuilder("[");
        boolean[] first = {true};
        for (ArtifactDependency dependency : dependencyRequirements) {
            CanonicalJson.comma(result, first);
            result.append('{');
            boolean[] fields = {true};
            fieldString(result, fields, "groupId", dependency.groupId());
            fieldString(result, fields, "artifactId", dependency.artifactId());
            fieldString(result, fields, "version", dependency.version());
            fieldString(result, fields, "profile", dependency.profile().canonicalSpelling());
            result.append('}');
        }
        return result.append(']').toString();
    }

    private static String attachmentContextJson(AttachmentContext context) {
        StringBuilder result = new StringBuilder("{");
        boolean[] fields = {true};
        fieldString(result, fields, "rootModule", context.rootModule().canonicalSpelling());
        fieldString(result, fields, "rootRevision", context.rootRevision().value());
        fieldString(result, fields, "graphRevision", context.graphRevision());
        fieldString(result, fields, "sourceInventoryRevision", context.sourceInventoryRevision());
        fieldString(result, fields, "optionsRevision", context.optionsRevision());
        fieldString(result, fields, "javaPackage", context.javaPackage());
        return result.append('}').toString();
    }

    private String importsJson() {
        StringBuilder result = new StringBuilder("[");
        boolean[] first = {true};
        for (ArtifactImport imported : imports) {
            CanonicalJson.comma(result, first);
            result.append('{');
            boolean[] fields = {true};
            fieldString(result, fields, "from", imported.fromModule().canonicalSpelling());
            fieldString(result, fields, "logicalTarget", imported.logicalTarget());
            fieldString(result, fields, "target", imported.targetModule().canonicalSpelling());
            field(result, fields, "start", Integer.toString(imported.startOffset()));
            field(result, fields, "end", Integer.toString(imported.endOffset()));
            result.append('}');
        }
        return result.append(']').toString();
    }

    private String optionsJson() {
        StringBuilder result = new StringBuilder("{");
        boolean[] first = {true};
        for (Map.Entry<String, String> entry : reproducibleOptions.entrySet()) {
            CanonicalJson.comma(result, first);
            CanonicalJson.stringField(result, entry.getKey(), entry.getValue());
        }
        return result.append('}').toString();
    }

    private static String requirementJson(RuntimeRequirement requirement) {
        StringBuilder result = new StringBuilder("{");
        boolean[] first = {true};
        fieldString(result, first, "groupId", requirement.groupId());
        fieldString(result, first, "artifactId", requirement.artifactId());
        fieldString(result, first, "version", requirement.version());
        fieldString(result, first, "profile", requirement.profile().name());
        if (!requirement.profile().previewSupported()) {
            field(result, first, "previewSupported", "false");
        }
        field(result, first, "previewRequired", Boolean.toString(requirement.previewRequired()));
        fieldObject(result, first, "minimumRuntimeAbi", runtimeAbiJson(requirement.minimumRuntimeAbi()));
        return result.append('}').toString();
    }

    private static void field(StringBuilder result, boolean[] first, String name, String value) {
        CanonicalJson.comma(result, first);
        CanonicalJson.fieldName(result, name);
        result.append(value);
    }

    private static void fieldString(StringBuilder result, boolean[] first, String name, String value) {
        CanonicalJson.comma(result, first);
        CanonicalJson.stringField(result, name, value);
    }

    private static void fieldObject(StringBuilder result, boolean[] first, String name, String value) {
        CanonicalJson.comma(result, first);
        CanonicalJson.fieldName(result, name);
        result.append(value);
    }

    private static List<ArtifactHook> sortedHooks(List<? extends ArtifactHook> values) {
        Objects.requireNonNull(values, "hookRequirements");
        ArrayList<ArtifactHook> copy = new ArrayList<>(values.size());
        for (ArtifactHook value : values) copy.add(Objects.requireNonNull(value, "hookRequirements"));
        copy.sort(ArtifactHook::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException("duplicate artifact hook: " + copy.get(index));
            }
        }
        return List.copyOf(copy);
    }

    private static List<ArtifactDependency> sortedDependencies(
            List<? extends ArtifactDependency> values) {
        Objects.requireNonNull(values, "dependencyRequirements");
        ArrayList<ArtifactDependency> copy = new ArrayList<>(values.size());
        for (ArtifactDependency value : values) {
            copy.add(Objects.requireNonNull(value, "dependencyRequirements"));
        }
        copy.sort(ArtifactDependency::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException("duplicate artifact dependency: " + copy.get(index));
            }
        }
        return List.copyOf(copy);
    }

    private static List<ArtifactImport> sortedImports(List<? extends ArtifactImport> values) {
        Objects.requireNonNull(values, "imports");
        ArrayList<ArtifactImport> copy = new ArrayList<>(values.size());
        for (ArtifactImport value : values) copy.add(Objects.requireNonNull(value, "imports"));
        copy.sort(ArtifactImport::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException("duplicate artifact import: " + copy.get(index));
            }
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> sortedOptions(Map<String, String> values) {
        Objects.requireNonNull(values, "reproducibleOptions");
        ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String key = text(entry.getKey(), "reproducible option key");
            String value = text(entry.getValue(), "reproducible option value");
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate reproducible option: " + key);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<ModuleMetadata> sortedModules(List<? extends ModuleMetadata> values) {
        Objects.requireNonNull(values, "modules");
        ArrayList<ModuleMetadata> copy = new ArrayList<>(values.size());
        for (ModuleMetadata value : values) {
            copy.add(Objects.requireNonNull(value, "modules must not contain null"));
        }
        copy.sort(ModuleMetadata::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).id().equals(copy.get(index).id())) {
                throw new IllegalArgumentException("duplicate module metadata: " + copy.get(index).id());
            }
        }
        return List.copyOf(copy);
    }

    private static List<SourceMetadata> sortedSources(List<? extends SourceMetadata> values) {
        Objects.requireNonNull(values, "sources");
        ArrayList<SourceMetadata> copy = new ArrayList<>(values.size());
        for (SourceMetadata value : values) {
            copy.add(Objects.requireNonNull(value, "sources must not contain null"));
        }
        copy.sort(SourceMetadata::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).sourceId().equals(copy.get(index).sourceId())) {
                throw new IllegalArgumentException("duplicate source metadata: "
                        + copy.get(index).sourceId());
            }
        }
        return List.copyOf(copy);
    }

    private static List<ExportMetadata> sortedExports(List<? extends ExportMetadata> values) {
        Objects.requireNonNull(values, "exports");
        ArrayList<ExportMetadata> copy = new ArrayList<>(values.size());
        for (ExportMetadata value : values) {
            copy.add(Objects.requireNonNull(value, "exports must not contain null"));
        }
        copy.sort(ExportMetadata::compareTo);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).id().equals(copy.get(index).id())) {
                throw new IllegalArgumentException("duplicate export metadata: " + copy.get(index).id());
            }
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> sortedNames(Map<String, String> values) {
        Objects.requireNonNull(values, "javaNameMap");
        ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String key = text(entry.getKey(), "javaNameMap key");
            copy.put(key, text(entry.getValue(), "javaNameMap value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireJavaPackage(String value) {
        CanonicalJson.requireUtf8(value, "javaPackage");
        if (value.isBlank() || value.startsWith(".") || value.endsWith(".")
                || value.indexOf('/') >= 0 || value.chars().anyMatch(character -> character == 92)) {
            throw new IllegalArgumentException("invalid Java package: " + value);
        }
        for (String part : value.split("[.]", -1)) {
            if (part.isEmpty() || isJavaKeyword(part)) {
                throw new IllegalArgumentException("invalid Java package: " + value);
            }
            int first = part.codePointAt(0);
            if (!Character.isJavaIdentifierStart(first)) {
                throw new IllegalArgumentException("invalid Java package: " + value);
            }
            for (int offset = Character.charCount(first); offset < part.length();) {
                int codePoint = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(codePoint)) {
                    throw new IllegalArgumentException("invalid Java package: " + value);
                }
                offset += Character.charCount(codePoint);
            }
        }
        return value;
    }

    private static boolean isJavaKeyword(String value) {
        return switch (value) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                    "class", "const", "continue", "default", "do", "double", "else", "enum",
                    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                    "import", "instanceof", "int", "interface", "long", "native", "new", "package",
                    "private", "protected", "public", "return", "short", "static", "strictfp",
                    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                    "try", "void", "volatile", "while", "true", "false", "null", "_", "record",
                    "sealed", "permits", "non-sealed", "var", "yield", "module", "open", "opens",
                    "requires", "transitive", "exports", "to", "uses", "provides", "with", "when" -> true;
            default -> false;
        };
    }

    private static String text(String value, String field) {
        CanonicalJson.requireUtf8(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        return value;
    }

    private static String requireRevision(String value, String field) {
        text(value, field);
        if (!ModuleRevision.isRevision(value)) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return value;
    }

    public static final class Builder {
        private int schemaVersion = LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION;
        private int languageContractVersion = LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION;
        private String compilerVersion;
        private String compilerBuild;
        private String javaPackage = "lyra.generated";
        private RuntimeAbi runtimeAbi = RuntimeAbi.CURRENT;
        private RuntimeProfile profile = RuntimeProfile.CURRENT;
        private int javaClassFileTarget = LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET;
        private boolean previewRequired;
        private String artifactId;
        private ArtifactRevision artifactRevision;
        private ModuleId rootModuleId;
        private ModuleRevision rootModuleRevision;
        private List<ModuleMetadata> modules = List.of();
        private List<SourceMetadata> sources = List.of();
        private List<ExportMetadata> exports = List.of();
        private Map<String, String> javaNameMap = Map.of();
        private int debugMapVersion = LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION;
        private String debugMapHash;
        private PackagingMode packagingMode = PackagingMode.CLASSES;
        private Optional<RuntimeRequirement> runtimeRequirement = Optional.empty();
        private ArtifactProfile executionProfile = ArtifactProfile.NORMAL;
        private List<ArtifactHook> hookRequirements = List.of();
        private List<ArtifactDependency> dependencyRequirements = List.of();
        private Optional<AttachmentContext> attachmentContext = Optional.empty();
        private List<ArtifactImport> imports = List.of();
        private Map<String, String> reproducibleOptions = Map.of();
        private Optional<ReplCapability> replCapability = Optional.empty();

        public Builder schemaVersion(int value) { schemaVersion = value; return this; }
        public Builder languageContractVersion(int value) { languageContractVersion = value; return this; }
        public Builder compilerVersion(String value) { compilerVersion = value; return this; }
        public Builder compilerBuild(String value) { compilerBuild = value; return this; }
        public Builder javaPackage(String value) { javaPackage = value; return this; }
        public Builder basePackage(String value) { javaPackage = value; return this; }
        public Builder runtimeAbi(RuntimeAbi value) { runtimeAbi = value; return this; }
        public Builder profile(RuntimeProfile value) { profile = value; return this; }
        public Builder javaClassFileTarget(int value) { javaClassFileTarget = value; return this; }
        public Builder previewRequired(boolean value) { previewRequired = value; return this; }
        public Builder artifactId(String value) { artifactId = value; return this; }
        public Builder artifactId(ArtifactId value) { artifactId = Objects.requireNonNull(value, "artifactId").value(); return this; }
        public Builder artifactRevision(ArtifactRevision value) { artifactRevision = value; return this; }
        public Builder artifactRevision(String value) { artifactRevision = ArtifactRevision.of(value); return this; }
        public Builder rootModuleId(ModuleId value) { rootModuleId = value; return this; }
        public Builder rootModuleRevision(ModuleRevision value) { rootModuleRevision = value; return this; }
        public Builder rootModuleRevision(String value) { rootModuleRevision = ModuleRevision.of(value); return this; }
        public Builder modules(List<? extends ModuleMetadata> value) { modules = List.copyOf(value); return this; }
        public Builder sources(List<? extends SourceMetadata> value) { sources = List.copyOf(value); return this; }
        public Builder sourceMetadata(List<? extends SourceMetadata> value) { sources = List.copyOf(value); return this; }
        public Builder exports(List<? extends ExportMetadata> value) { exports = List.copyOf(value); return this; }
        public Builder javaNameMap(Map<String, String> value) { javaNameMap = Map.copyOf(value); return this; }
        public Builder debugMapVersion(int value) { debugMapVersion = value; return this; }
        public Builder debugMapHash(String value) { debugMapHash = value; return this; }
        public Builder packagingMode(PackagingMode value) { packagingMode = value; return this; }
        public Builder runtimeRequirement(RuntimeRequirement value) { runtimeRequirement = Optional.ofNullable(value); return this; }
        public Builder runtimeRequirement(Optional<RuntimeRequirement> value) { runtimeRequirement = Objects.requireNonNull(value, "runtimeRequirement"); return this; }
        public Builder executionProfile(ArtifactProfile value) { executionProfile = Objects.requireNonNull(value, "executionProfile"); return this; }
        public Builder artifactProfile(ArtifactProfile value) { return executionProfile(value); }
        public Builder hookRequirements(List<? extends ArtifactHook> value) { hookRequirements = List.copyOf(value); return this; }
        public Builder dependencyRequirements(List<? extends ArtifactDependency> value) { dependencyRequirements = List.copyOf(value); return this; }
        public Builder attachmentContext(AttachmentContext value) { attachmentContext = Optional.ofNullable(value); return this; }
        public Builder attachmentContext(Optional<AttachmentContext> value) { attachmentContext = Objects.requireNonNull(value, "attachmentContext"); return this; }
        public Builder imports(List<? extends ArtifactImport> value) { imports = List.copyOf(value); return this; }
        public Builder reproducibleOptions(Map<String, String> value) { reproducibleOptions = Map.copyOf(value); return this; }
        public Builder replCapability(ReplCapability value) { replCapability = Optional.ofNullable(value); return this; }
        public Builder replCapability(Optional<ReplCapability> value) { replCapability = Objects.requireNonNull(value, "replCapability"); return this; }
        public Builder replCapable(boolean value) { replCapability = value ? Optional.of(ReplCapability.CURRENT) : Optional.empty(); return this; }

        public ArtifactMetadata build() {
            return new ArtifactMetadata(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                    runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId, artifactRevision,
                    rootModuleId, rootModuleRevision, modules, sources, javaPackage, exports, javaNameMap,
                    debugMapVersion, debugMapHash, packagingMode, runtimeRequirement, executionProfile,
                    hookRequirements, dependencyRequirements, attachmentContext, imports,
                    reproducibleOptions, replCapability);
        }
    }
}
