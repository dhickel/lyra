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
    private final RuntimeAbi runtimeAbi;
    private final RuntimeProfile profile;
    private final int javaClassFileTarget;
    private final boolean previewRequired;
    private final String artifactId;
    private final ArtifactRevision artifactRevision;
    private final ModuleId rootModuleId;
    private final ModuleRevision rootModuleRevision;
    private final List<ModuleMetadata> modules;
    private final List<ExportMetadata> exports;
    private final Map<String, String> javaNameMap;
    private final int debugMapVersion;
    private final String debugMapHash;
    private final PackagingMode packagingMode;
    private final Optional<RuntimeRequirement> runtimeRequirement;

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
        if (schemaVersion != LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION
                || languageContractVersion != LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION
                || debugMapVersion != LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported metadata schema or language version");
        }
        this.schemaVersion = schemaVersion;
        this.languageContractVersion = languageContractVersion;
        this.compilerVersion = text(compilerVersion, "compilerVersion");
        this.compilerBuild = text(compilerBuild, "compilerBuild");
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
        this.exports = sortedExports(exports);
        this.javaNameMap = sortedNames(javaNameMap);
        this.debugMapVersion = debugMapVersion;
        this.debugMapHash = requireRevision(debugMapHash, "debugMapHash");
        this.packagingMode = Objects.requireNonNull(packagingMode, "packagingMode");
        this.runtimeRequirement = Objects.requireNonNull(runtimeRequirement, "runtimeRequirement");
        if (packagingMode == PackagingMode.THIN_JAR && runtimeRequirement.isEmpty()) {
            throw new IllegalArgumentException("thin artifacts require an external runtime requirement");
        }
        if (packagingMode != PackagingMode.THIN_JAR && runtimeRequirement.isPresent()) {
            throw new IllegalArgumentException("only thin artifacts record an external runtime requirement");
        }
        validateModuleIdentity();
        validateExports();
        ArtifactRevision expectedRevision = ArtifactRevision.compute(
                this.compilerBuild, this.modules, this.javaNameMap, this.profile,
                this.packagingMode, this.previewRequired);
        if (!expectedRevision.equals(this.artifactRevision)) {
            throw new IllegalArgumentException("artifact revision disagrees with metadata inputs");
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
    public List<ExportMetadata> exports() { return exports; }
    public Map<String, String> javaNameMap() { return javaNameMap; }
    public Map<String, String> nameMap() { return javaNameMap; }
    public int debugMapVersion() { return debugMapVersion; }
    public String debugMapHash() { return debugMapHash; }
    public PackagingMode packagingMode() { return packagingMode; }
    public Optional<RuntimeRequirement> runtimeRequirement() { return runtimeRequirement; }

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
        fieldObject(result, first, "exports", exportsJson());
        fieldObject(result, first, "javaNameMap", namesJson());
        field(result, first, "debugMapVersion", Integer.toString(debugMapVersion));
        fieldString(result, first, "debugMapHash", debugMapHash);
        fieldString(result, first, "packagingMode", packagingMode.canonicalSpelling());
        runtimeRequirement.ifPresent(requirement -> fieldObject(
                result, first, "runtimeRequirement", requirementJson(requirement)));
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
                && runtimeAbi.equals(metadata.runtimeAbi)
                && profile.equals(metadata.profile)
                && javaClassFileTarget == metadata.javaClassFileTarget
                && previewRequired == metadata.previewRequired
                && artifactId.equals(metadata.artifactId)
                && artifactRevision.equals(metadata.artifactRevision)
                && rootModuleId.equals(metadata.rootModuleId)
                && rootModuleRevision.equals(metadata.rootModuleRevision)
                && modules.equals(metadata.modules)
                && exports.equals(metadata.exports)
                && javaNameMap.equals(metadata.javaNameMap)
                && debugMapVersion == metadata.debugMapVersion
                && debugMapHash.equals(metadata.debugMapHash)
                && packagingMode == metadata.packagingMode
                && runtimeRequirement.equals(metadata.runtimeRequirement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId,
                artifactRevision, rootModuleId, rootModuleRevision, modules, exports, javaNameMap,
                debugMapVersion, debugMapHash, packagingMode, runtimeRequirement);
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

    private void validateExports() {
        java.util.HashSet<String> exportIds = new java.util.HashSet<>();
        java.util.HashMap<ModuleId, java.util.HashSet<String>> javaNamesByModule =
                new java.util.HashMap<>();
        for (ExportMetadata export : exports) {
            if (modules.stream().noneMatch(module -> module.id().equals(export.moduleId()))) {
                throw new IllegalArgumentException("export refers to an absent module: " + export);
            }
            String exportId = export.id().id();
            exportIds.add(exportId);
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
            fieldString(result, fields, "signature", export.signature().canonicalSpelling());
            fieldString(result, fields, "jvmDescriptor", export.jvmDescriptor());
            fieldString(result, fields, "bindingMutability", export.bindingMutability().canonicalSpelling());
            fieldString(result, fields, "javaName", export.javaName());
            fieldString(result, fields, "getterName", export.getterName());
            fieldString(result, fields, "functionValueName", export.functionValueName());
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
        private RuntimeAbi runtimeAbi = RuntimeAbi.CURRENT;
        private RuntimeProfile profile = RuntimeProfile.CURRENT;
        private int javaClassFileTarget = LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET;
        private boolean previewRequired;
        private String artifactId;
        private ArtifactRevision artifactRevision;
        private ModuleId rootModuleId;
        private ModuleRevision rootModuleRevision;
        private List<ModuleMetadata> modules = List.of();
        private List<ExportMetadata> exports = List.of();
        private Map<String, String> javaNameMap = Map.of();
        private int debugMapVersion = LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION;
        private String debugMapHash;
        private PackagingMode packagingMode = PackagingMode.CLASSES;
        private Optional<RuntimeRequirement> runtimeRequirement = Optional.empty();

        public Builder schemaVersion(int value) { schemaVersion = value; return this; }
        public Builder languageContractVersion(int value) { languageContractVersion = value; return this; }
        public Builder compilerVersion(String value) { compilerVersion = value; return this; }
        public Builder compilerBuild(String value) { compilerBuild = value; return this; }
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
        public Builder exports(List<? extends ExportMetadata> value) { exports = List.copyOf(value); return this; }
        public Builder javaNameMap(Map<String, String> value) { javaNameMap = Map.copyOf(value); return this; }
        public Builder debugMapVersion(int value) { debugMapVersion = value; return this; }
        public Builder debugMapHash(String value) { debugMapHash = value; return this; }
        public Builder packagingMode(PackagingMode value) { packagingMode = value; return this; }
        public Builder runtimeRequirement(RuntimeRequirement value) { runtimeRequirement = Optional.ofNullable(value); return this; }
        public Builder runtimeRequirement(Optional<RuntimeRequirement> value) { runtimeRequirement = Objects.requireNonNull(value, "runtimeRequirement"); return this; }

        public ArtifactMetadata build() {
            return new ArtifactMetadata(schemaVersion, languageContractVersion, compilerVersion, compilerBuild,
                    runtimeAbi, profile, javaClassFileTarget, previewRequired, artifactId, artifactRevision,
                    rootModuleId, rootModuleRevision, modules, exports, javaNameMap, debugMapVersion,
                    debugMapHash, packagingMode, runtimeRequirement);
        }
    }
}
