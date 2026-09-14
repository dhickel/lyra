package io.mindspice.lyra.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict reader for the runtime-owned artifact metadata contract. */
public final class ArtifactMetadataReader {
    public static final String RESOURCE_PATH = LyraRuntimeConstants.ARTIFACT_METADATA_PATH;

    private static final List<String> FIELD_ORDER = List.of(
            "schemaVersion", "languageContractVersion", "compilerVersion", "compilerBuild",
            "runtimeAbi", "profile", "javaPackage", "previewSupported", "javaClassFileTarget", "previewRequired", "artifactId",
            "artifactRevision", "rootModuleId", "rootModuleRevision", "modules", "sources", "nominalSchemas", "exports",
            "javaNameMap", "debugMapVersion", "debugMapHash", "packagingMode",
            "runtimeRequirement", "replCapability", "executionProfile", "hookRequirements",
            "dependencyRequirements", "attachmentContext", "imports", "reproducibleOptions");
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "schemaVersion", "languageContractVersion", "compilerVersion", "compilerBuild",
            "runtimeAbi", "profile", "javaPackage", "javaClassFileTarget", "previewRequired", "artifactId",
            "artifactRevision", "rootModuleId", "rootModuleRevision", "modules", "sources", "exports",
            "javaNameMap", "debugMapVersion", "debugMapHash", "packagingMode");

    private ArtifactMetadataReader() {
    }

    public static ArtifactMetadata read(byte[] canonicalUtf8) {
        return read(canonicalUtf8, RuntimeProfile.CURRENT, RuntimeAbi.CURRENT);
    }

    public static ArtifactMetadata read(byte[] canonicalUtf8, RuntimeOptions options) {
        Objects.requireNonNull(options, "options");
        ArtifactMetadata metadata = read(canonicalUtf8, options.profile(), options.runtimeAbi());
        if (metadata.previewRequired() && !options.previewEnabled()) {
            throw compatibility("artifact requires preview to be enabled in runtime options", null);
        }
        return metadata;
    }

    public static ArtifactMetadata read(byte[] canonicalUtf8,
                                        RuntimeProfile runningProfile,
                                        RuntimeAbi runningAbi) {
        Objects.requireNonNull(canonicalUtf8, "canonicalUtf8");
        Objects.requireNonNull(runningProfile, "runningProfile");
        Objects.requireNonNull(runningAbi, "runningAbi");
        String json = decodeUtf8(canonicalUtf8);
        Map<String, Object> object;
        try {
            object = new JsonParser(json).parseObject();
        } catch (RuntimeException exception) {
            throw compatibility("malformed canonical artifact metadata: " + message(exception), exception);
        }
        try {
            validateObjectKeys(object, FIELD_ORDER, REQUIRED_FIELDS, "artifact metadata");
            requireSupportedVersions(object);
            ArtifactMetadata metadata = decodeMetadata(object);
            validateCompatibility(metadata, runningProfile, runningAbi);
            return metadata;
        } catch (LyraCompatibilityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw compatibility("inconsistent artifact metadata: " + message(exception), exception);
        }
    }

    public static ArtifactMetadata read(String canonicalJson) {
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        try {
            return read(encodeUtf8(canonicalJson));
        } catch (IllegalArgumentException exception) {
            throw compatibility("artifact metadata is not valid UTF-8", exception);
        }
    }

    /** Reads bytes without closing the supplied stream. */
    public static ArtifactMetadata read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return read(input.readAllBytes());
    }

    public static ArtifactMetadata read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return read(Files.readAllBytes(path));
    }

    private static ArtifactMetadata decodeMetadata(Map<String, Object> object) {
        int schemaVersion = integer(object, "schemaVersion");
        int languageVersion = integer(object, "languageContractVersion");
        String compilerVersion = string(object, "compilerVersion");
        String compilerBuild = string(object, "compilerBuild");
        RuntimeAbi runtimeAbi = decodeAbi(objectValue(object.get("runtimeAbi"), "runtimeAbi"), "runtimeAbi");
        String profileName = string(object, "profile");
        String javaPackage = string(object, "javaPackage");
        int target = integer(object, "javaClassFileTarget");
        boolean previewSupported = object.containsKey("previewSupported")
                ? bool(object, "previewSupported") : true;
        if (object.containsKey("previewSupported") && previewSupported) {
            throw new IllegalArgumentException("previewSupported=true must be omitted canonically");
        }
        boolean previewRequired = bool(object, "previewRequired");
        RuntimeProfile profile = new RuntimeProfile(profileName, target, previewSupported, runtimeAbi);
        String artifactId = string(object, "artifactId");
        ArtifactRevision artifactRevision = ArtifactRevision.of(string(object, "artifactRevision"));
        String rootModuleSpelling = string(object, "rootModuleId");
        ModuleId rootModuleId = moduleId(rootModuleSpelling);
        if (!rootModuleId.canonicalSpelling().equals(rootModuleSpelling)) {
            throw new IllegalArgumentException("root module ID is not canonical");
        }
        ModuleRevision rootModuleRevision = ModuleRevision.of(string(object, "rootModuleRevision"));
        List<ModuleMetadata> modules = decodeModules(array(object, "modules"));
        List<SourceMetadata> sources = decodeSources(array(object, "sources"));
        if (sources.size() != modules.size()) {
            throw new IllegalArgumentException(
                    "source metadata must cover every module");
        }
        if ((schemaVersion == ArtifactMetadata.NOMINAL_SCHEMA_VERSION) != object.containsKey("nominalSchemas")) {
            throw new IllegalArgumentException("nominal schema section/version mismatch");
        }
        NominalTypeEnvironment nominalSchemas = object.containsKey("nominalSchemas")
                ? decodeNominalSchemas(array(object, "nominalSchemas")) : NominalTypeEnvironment.empty();
        List<ExportMetadata> exports = decodeExports(array(object, "exports"), nominalSchemas);
        Map<String, String> names = decodeNames(objectValue(object.get("javaNameMap"), "javaNameMap"));
        int debugMapVersion = integer(object, "debugMapVersion");
        String debugMapHash = string(object, "debugMapHash");
        PackagingMode packagingMode = PackagingMode.parse(string(object, "packagingMode"));
        Optional<RuntimeRequirement> requirement = object.containsKey("runtimeRequirement")
                ? Optional.of(decodeRuntimeRequirement(objectValue(object.get("runtimeRequirement"), "runtimeRequirement")))
                : Optional.empty();
        String executionProfileSpelling = object.containsKey("executionProfile")
                ? string(object, "executionProfile") : ArtifactProfile.NORMAL.canonicalSpelling();
        ArtifactProfile executionProfile = ArtifactProfile.parse(executionProfileSpelling);
        if (!executionProfile.canonicalSpelling().equals(executionProfileSpelling)) {
            throw new IllegalArgumentException("execution profile is not canonical");
        }
        List<ArtifactHook> hooks = object.containsKey("hookRequirements")
                ? decodeHooks(array(object, "hookRequirements")) : List.of();
        List<ArtifactDependency> dependencies = object.containsKey("dependencyRequirements")
                ? decodeDependencies(array(object, "dependencyRequirements")) : List.of();
        Optional<AttachmentContext> attachmentContext = object.containsKey("attachmentContext")
                ? Optional.of(decodeAttachmentContext(objectValue(object.get("attachmentContext"), "attachmentContext")))
                : Optional.empty();
        List<ArtifactImport> imports = object.containsKey("imports")
                ? decodeImports(array(object, "imports")) : List.of();
        Map<String, String> options = object.containsKey("reproducibleOptions")
                ? decodeOptions(objectValue(object.get("reproducibleOptions"), "reproducibleOptions"))
                : Map.of();
        Optional<ReplCapability> replCapability = object.containsKey("replCapability")
                ? Optional.of(decodeReplCapability(
                        objectValue(object.get("replCapability"), "replCapability")))
                : Optional.empty();
        validateProfileEncoding(object, executionProfile, replCapability.isPresent());
        return new ArtifactMetadata(schemaVersion, languageVersion, compilerVersion, compilerBuild,
                runtimeAbi, profile, target, previewRequired, artifactId, artifactRevision,
                rootModuleId, rootModuleRevision, modules, sources, javaPackage, exports, names, debugMapVersion,
                debugMapHash, packagingMode, requirement, executionProfile, hooks, dependencies,
                attachmentContext, imports, options, replCapability, nominalSchemas);
    }

    private static ReplCapability decodeReplCapability(Map<String, Object> object) {
        validateObjectKeys(object, List.of("schema"), Set.of("schema"), "REPL capability");
        if (object.size() != 1) {
            throw new IllegalArgumentException(
                    "REPL capability must be the canonical {\"schema\":1} object");
        }
        Object value = object.get("schema");
        if (!(value instanceof java.math.BigInteger schema)
                || !schema.equals(java.math.BigInteger.ONE)) {
            throw new IllegalArgumentException("REPL capability must be the canonical {\"schema\":1} object");
        }
        return ReplCapability.CURRENT;
    }

    private static List<ModuleMetadata> decodeModules(List<Object> values) {
        ArrayList<ModuleMetadata> result = new ArrayList<>(values.size());
        ModuleId previous = null;
        for (Object value : values) {
            Map<String, Object> object = objectValue(value, "module metadata");
            validateObjectKeys(object, List.of("id", "revision", "sourceLabel"),
                    Set.of("id", "revision", "sourceLabel"), "module metadata");
            ModuleId id = moduleId(string(object, "id"));
            if (!id.canonicalSpelling().equals(string(object, "id"))) {
                throw new IllegalArgumentException("module ID is not canonical: " + id);
            }
            if (previous != null && previous.compareTo(id) >= 0) {
                throw new IllegalArgumentException("module metadata is not sorted or contains a duplicate");
            }
            previous = id;
            result.add(new ModuleMetadata(id, ModuleRevision.of(string(object, "revision")),
                    string(object, "sourceLabel")));
        }
        return List.copyOf(result);
    }

    private static List<SourceMetadata> decodeSources(List<Object> values) {
        ArrayList<SourceMetadata> result = new ArrayList<>(values.size());
        SourceMetadata previous = null;
        for (Object value : values) {
            Map<String, Object> object = objectValue(value, "source metadata");
            validateObjectKeys(object,
                    List.of("sourceKind", "sourceId", "sourceLabel", "sha256", "entry"),
                    Set.of("sourceKind", "sourceId", "sourceLabel", "sha256"),
                    "source metadata");
            String sourceSpelling = string(object, "sourceId");
            SourceId sourceId = switch (string(object, "sourceKind")) {
                case "path" -> SourceId.path(sourceSpelling);
                case "uri" -> SourceId.uri(URI.create(sourceSpelling));
                default -> throw new IllegalArgumentException("unknown source identity kind");
            };
            if (!sourceId.canonicalSpelling().equals(sourceSpelling)) {
                throw new IllegalArgumentException("source ID is not canonical");
            }
            Optional<String> entry = object.containsKey("entry")
                    ? Optional.ofNullable(nullableString(object, "entry")) : Optional.empty();
            SourceMetadata source = new SourceMetadata(sourceId, string(object, "sourceLabel"),
                    string(object, "sha256"), entry);
            if (previous != null && previous.compareTo(source) >= 0) {
                throw new IllegalArgumentException(
                        "source metadata is not sorted or contains a duplicate");
            }
            previous = source;
            result.add(source);
        }
        return List.copyOf(result);
    }

    private static NominalTypeEnvironment decodeNominalSchemas(List<Object> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("nominal schema version requires declarations");
        var identities = new java.util.TreeMap<String, NominalType>();
        var definitions = new ArrayList<Map<String, Object>>();
        List<String> fields = List.of("module", "revision", "name", "occurrence", "type", "kind", "members", "parameters");
        String previous = null;
        for (Object value : values) {
            Map<String, Object> definition = objectValue(value, "nominal schema");
            validateObjectKeys(definition, fields, Set.copyOf(fields), "nominal schema");
            String moduleSpelling = string(definition, "module");
            ModuleId module = moduleId(moduleSpelling);
            if (!module.canonicalSpelling().equals(moduleSpelling)) throw new IllegalArgumentException("noncanonical nominal module");
            NominalType type = new NominalType(new NominalTypeId(module, string(definition, "revision"),
                    string(definition, "name"), longValue(definition, "occurrence")));
            String canonical = string(definition, "type");
            if (!canonical.equals(type.canonicalSpelling()) || previous != null && previous.compareTo(canonical) >= 0) {
                throw new IllegalArgumentException("nominal identity digest or canonical schema ordering differs");
            }
            previous = canonical;
            if (identities.putIfAbsent(canonical, type) != null) throw new IllegalArgumentException("duplicate nominal schema");
            definitions.add(definition);
        }
        java.util.function.Function<String, NominalType> resolver = canonical -> {
            NominalType type = identities.get(canonical);
            if (type == null) throw new IllegalArgumentException("unknown nominal schema reference: " + canonical);
            return type;
        };
        var schemas = new ArrayList<NominalSchema>();
        List<String> memberFields = List.of("name", "type", "public", "mutable", "initializer");
        for (Map<String, Object> definition : definitions) {
            var members = new ArrayList<NominalSchema.Member>();
            for (Object value : array(definition, "members")) {
                var member = objectValue(value, "nominal member");
                validateObjectKeys(member, memberFields, Set.copyOf(memberFields), "nominal member");
                members.add(new NominalSchema.Member(string(member, "name"),
                        LyraTypeParser.parse(string(member, "type"), resolver), bool(member, "public"),
                        bool(member, "mutable"), bool(member, "initializer")));
            }
            var parameters = new ArrayList<LyraType>();
            for (Object value : array(definition, "parameters")) {
                if (!(value instanceof String spelling)) throw new IllegalArgumentException("nominal parameter must be a type string");
                parameters.add(LyraTypeParser.parse(spelling, resolver));
            }
            schemas.add(new NominalSchema(identities.get(string(definition, "type")),
                    NominalSchema.Kind.valueOf(string(definition, "kind")), members, parameters));
        }
        return new NominalTypeEnvironment(schemas);
    }

    private static List<ExportMetadata> decodeExports(List<Object> values, NominalTypeEnvironment nominalSchemas) {
        ArrayList<ExportMetadata> result = new ArrayList<>(values.size());
        ExportId previous = null;
        for (Object value : values) {
            Map<String, Object> object = objectValue(value, "export metadata");
            validateObjectKeys(object,
                    List.of("id", "moduleId", "name", "signature", "jvmDescriptor",
                            "bindingMutability", "javaName", "getterName", "functionValueName",
                            "declarationIdentity", "originDeclarationIdentity", "setterName"),
                    Set.of("id", "moduleId", "name", "signature", "jvmDescriptor",
                            "bindingMutability", "javaName", "getterName", "functionValueName",
                            "setterName"), "export metadata");
            String moduleSpelling = string(object, "moduleId");
            ModuleId moduleId = moduleId(moduleSpelling);
            if (!moduleId.canonicalSpelling().equals(moduleSpelling)) {
                throw new IllegalArgumentException("export module ID is not canonical");
            }
            String contractSpelling = string(object, "signature");
            LyraType contract = LyraType.parse(contractSpelling, nominalSchemas);
            if (!contract.canonicalSpelling().equals(contractSpelling)) {
                throw new IllegalArgumentException("export contract is not canonical");
            }
            String name = string(object, "name");
            ExportId id = new ExportId(moduleId, name, contract);
            String encodedId = string(object, "id");
            if (!id.id().equals(encodedId)) {
                throw new IllegalArgumentException("export ID does not match its module/name/signature");
            }
            if (previous != null && previous.compareTo(id) >= 0) {
                throw new IllegalArgumentException("export metadata is not sorted or contains a duplicate");
            }
            previous = id;
            String setter = nullableString(object, "setterName");
            long declarationIdentity = object.containsKey("declarationIdentity")
                    ? longValue(object, "declarationIdentity") : -1L;
            long originDeclarationIdentity = object.containsKey("originDeclarationIdentity")
                    ? longValue(object, "originDeclarationIdentity") : -1L;
            if ((declarationIdentity >= 0) != (originDeclarationIdentity >= 0)) {
                throw new IllegalArgumentException("export declaration provenance must be complete");
            }
            result.add(new ExportMetadata(id, string(object, "jvmDescriptor"),
                    BindingMutability.parse(string(object, "bindingMutability")),
                    string(object, "javaName"), string(object, "getterName"),
                    string(object, "functionValueName"), Optional.ofNullable(setter),
                    declarationIdentity, originDeclarationIdentity));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> decodeNames(Map<String, Object> object) {
        ArrayList<String> keys = new ArrayList<>(object.keySet());
        ArrayList<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        if (!keys.equals(sorted)) {
            throw new IllegalArgumentException("java name map is not sorted");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("java name map values must be strings");
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static void validateProfileEncoding(Map<String, Object> object,
                                                 ArtifactProfile profile,
                                                 boolean replCapable) {
        List<String> extensionFields = List.of("executionProfile", "hookRequirements",
                "dependencyRequirements", "attachmentContext", "imports",
                "reproducibleOptions");
        if (profile == ArtifactProfile.NORMAL && !replCapable) {
            if (extensionFields.stream().anyMatch(object::containsKey)) {
                throw new IllegalArgumentException(
                        "normal schema-1 metadata must omit profile extension fields");
            }
            return;
        }
        if (profile == ArtifactProfile.NORMAL) {
            // Debug-capable normal publications declare the context and the
            // closure requirement but keep the ordinary generated-code ABI.
            List<String> required = List.of("hookRequirements", "dependencyRequirements",
                    "imports", "reproducibleOptions");
            for (String field : required) {
                if (!object.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "debug-capable normal metadata is missing required profile field: " + field);
                }
            }
            if (object.containsKey("executionProfile")
                    || object.containsKey("attachmentContext")) {
                throw new IllegalArgumentException(
                        "debug-capable normal metadata must omit executionProfile and attachmentContext");
            }
            return;
        }
        List<String> required = profile == ArtifactProfile.ATTACHABLE
                ? extensionFields
                : extensionFields.stream().filter(field -> !field.equals("attachmentContext")).toList();
        for (String field : required) {
            if (!object.containsKey(field)) {
                throw new IllegalArgumentException(profile.canonicalSpelling()
                        + " metadata is missing required profile field: " + field);
            }
        }
    }

    private static List<ArtifactHook> decodeHooks(List<Object> values) {
        ArrayList<ArtifactHook> result = new ArrayList<>();
        ArtifactHook previous = null;
        for (Object value : values) {
            Map<String, Object> object = objectValue(value, "artifact hook");
            validateObjectKeys(object, List.of("name", "descriptor"),
                    Set.of("name", "descriptor"), "artifact hook");
            ArtifactHook hook = new ArtifactHook(string(object, "name"), string(object, "descriptor"));
            if (previous != null && previous.compareTo(hook) >= 0) {
                throw new IllegalArgumentException("artifact hooks are not sorted or contain a duplicate");
            }
            previous = hook;
            result.add(hook);
        }
        return List.copyOf(result);
    }

    private static List<ArtifactDependency> decodeDependencies(List<Object> values) {
        ArrayList<ArtifactDependency> result = new ArrayList<>();
        ArtifactDependency previous = null;
        for (Object value : values) {
            Map<String, Object> object = objectValue(value, "artifact dependency");
            validateObjectKeys(object, List.of("groupId", "artifactId", "version", "profile"),
                    Set.of("groupId", "artifactId", "version", "profile"), "artifact dependency");
            String profileSpelling = string(object, "profile");
            ArtifactProfile profile = ArtifactProfile.parse(profileSpelling);
            if (!profile.canonicalSpelling().equals(profileSpelling)) {
                throw new IllegalArgumentException("artifact dependency profile is not canonical");
            }
            ArtifactDependency dependency = new ArtifactDependency(string(object, "groupId"),
                    string(object, "artifactId"), string(object, "version"), profile);
            if (previous != null && previous.compareTo(dependency) >= 0) {
                throw new IllegalArgumentException("artifact dependencies are not sorted or contain a duplicate");
            }
            previous = dependency;
            result.add(dependency);
        }
        return List.copyOf(result);
    }

    private static AttachmentContext decodeAttachmentContext(Map<String, Object> object) {
        validateObjectKeys(object,
                List.of("rootModule", "rootRevision", "graphRevision", "sourceInventoryRevision",
                        "optionsRevision", "javaPackage"),
                Set.of("rootModule", "rootRevision", "graphRevision", "sourceInventoryRevision",
                        "optionsRevision", "javaPackage"), "attachment context");
        String rootModuleSpelling = string(object, "rootModule");
        ModuleId rootModule = moduleId(rootModuleSpelling);
        if (!rootModule.canonicalSpelling().equals(rootModuleSpelling)) {
            throw new IllegalArgumentException("attachment root module ID is not canonical");
        }
        return new AttachmentContext(rootModule,
                ModuleRevision.of(string(object, "rootRevision")),
                string(object, "graphRevision"), string(object, "sourceInventoryRevision"),
                string(object, "optionsRevision"), string(object, "javaPackage"));
    }

    private static List<ArtifactImport> decodeImports(List<Object> values) {
        ArrayList<ArtifactImport> result = new ArrayList<>();
        ArtifactImport previous = null;
        for (Object value : values) {
            Map<String, Object> object = objectValue(value, "artifact import");
            validateObjectKeys(object,
                    List.of("from", "logicalTarget", "target", "start", "end"),
                    Set.of("from", "logicalTarget", "target", "start", "end"), "artifact import");
            String fromSpelling = string(object, "from");
            String targetSpelling = string(object, "target");
            ModuleId from = moduleId(fromSpelling);
            ModuleId target = moduleId(targetSpelling);
            if (!from.canonicalSpelling().equals(fromSpelling)
                    || !target.canonicalSpelling().equals(targetSpelling)) {
                throw new IllegalArgumentException("artifact import module ID is not canonical");
            }
            ArtifactImport imported = new ArtifactImport(from,
                    string(object, "logicalTarget"), target,
                    integer(object, "start"), integer(object, "end"));
            if (previous != null && previous.compareTo(imported) >= 0) {
                throw new IllegalArgumentException("artifact imports are not sorted or contain a duplicate");
            }
            previous = imported;
            result.add(imported);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> decodeOptions(Map<String, Object> object) {
        ArrayList<String> keys = new ArrayList<>(object.keySet());
        ArrayList<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        if (!keys.equals(sorted)) throw new IllegalArgumentException("reproducible options are not sorted");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("reproducible option values must be strings");
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static RuntimeRequirement decodeRuntimeRequirement(Map<String, Object> object) {
        validateObjectKeys(object,
                List.of("groupId", "artifactId", "version", "profile", "previewSupported",
                        "previewRequired", "minimumRuntimeAbi"),
                Set.of("groupId", "artifactId", "version", "profile", "previewRequired",
                        "minimumRuntimeAbi"), "runtime requirement");
        RuntimeAbi minimumAbi = decodeAbi(objectValue(object.get("minimumRuntimeAbi"), "minimumRuntimeAbi"), "minimumRuntimeAbi");
        boolean previewSupported = object.containsKey("previewSupported")
                ? bool(object, "previewSupported") : true;
        if (object.containsKey("previewSupported") && previewSupported) {
            throw new IllegalArgumentException("previewSupported=true must be omitted canonically");
        }
        RuntimeProfile profile = new RuntimeProfile(string(object, "profile"),
                LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET, previewSupported, minimumAbi);
        return new RuntimeRequirement(string(object, "groupId"), string(object, "artifactId"),
                string(object, "version"), profile, bool(object, "previewRequired"), minimumAbi);
    }

    /**
     * Rejects an unsupported artifact schema, language contract, or debug-map
     * version with the explicit compatibility diagnostic before any
     * version-derived identity is decoded.  Encoding checks that depend on the
     * running language contract would otherwise report a confusing
     * identity-consistency failure for a stale artifact.
     */
    private static void requireSupportedVersions(Map<String, Object> object) {
        int schemaVersion = integer(object, "schemaVersion");
        if (schemaVersion != LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION
                && schemaVersion != ArtifactMetadata.NOMINAL_SCHEMA_VERSION) {
            throw compatibility("unsupported artifact schema version: " + schemaVersion, null);
        }
        int languageVersion = integer(object, "languageContractVersion");
        if (languageVersion != LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION) {
            throw compatibility("unsupported language contract version: " + languageVersion, null);
        }
        int debugMapVersion = integer(object, "debugMapVersion");
        if (debugMapVersion != LyraRuntimeConstants.DEBUG_MAP_SCHEMA_VERSION) {
            throw compatibility("unsupported debug-map schema version: " + debugMapVersion, null);
        }
    }

    private static void validateCompatibility(ArtifactMetadata metadata,
                                              RuntimeProfile runningProfile,
                                              RuntimeAbi runningAbi) {
        if (metadata.schemaVersion() != LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION
                && metadata.schemaVersion() != ArtifactMetadata.NOMINAL_SCHEMA_VERSION) {
            throw compatibility("unsupported artifact schema version: " + metadata.schemaVersion(), null);
        }
        if (metadata.languageContractVersion() != LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION) {
            throw compatibility("unsupported language contract version: "
                    + metadata.languageContractVersion(), null);
        }
        if (metadata.javaClassFileTarget() != LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET
                || !metadata.profile().name().equals(LyraRuntimeConstants.JAVA_PROFILE)
                || metadata.profile().javaClassFileTarget() != LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET) {
            throw compatibility("unsupported artifact Java profile: " + metadata.profile(), null);
        }
        if (!runningAbi.isCompatibleWith(metadata.runtimeAbi())) {
            throw compatibility("runtime ABI " + runningAbi + " cannot consume "
                    + metadata.runtimeAbi(), null);
        }
        if (!runningProfile.isCompatibleWith(metadata.profile(), metadata.previewRequired())) {
            throw compatibility("runtime profile " + runningProfile
                    + " cannot consume artifact profile " + metadata.profile(), null);
        }
        if (metadata.previewRequired() && !runningProfile.previewSupported()) {
            throw compatibility("artifact requires Java preview features", null);
        }
        ArtifactRevision expected = ArtifactRevision.compute(metadata.compilerBuild(), metadata.modules(),
                metadata.javaNameMap(), metadata.profile(), metadata.packagingMode(), metadata.previewRequired(),
                metadata.javaPackage(), metadata.sources(), metadata.runtimeRequirement(),
                metadata.executionProfile(), metadata.hookRequirements(), metadata.dependencyRequirements(),
                metadata.attachmentContext(), metadata.imports(), metadata.reproducibleOptions(),
                metadata.replCapable());
        expected = ArtifactRevision.bindNominalSchemas(expected, metadata.nominalSchemas());
        if (!expected.equals(metadata.artifactRevision())) {
            throw compatibility("artifact revision does not match canonical metadata inputs", null);
        }
        if (metadata.packagingMode() == PackagingMode.THIN_JAR) {
            RuntimeRequirement requirement = metadata.runtimeRequirement().orElseThrow();
            if (!runningAbi.isCompatibleWith(requirement.minimumRuntimeAbi())) {
                throw compatibility("runtime ABI does not satisfy thin-artifact requirement", null);
            }
            if (!requirement.profile().name().equals(LyraRuntimeConstants.JAVA_PROFILE)
                    || !runningProfile.isCompatibleWith(requirement.profile(), requirement.previewRequired())) {
                throw compatibility("runtime profile does not satisfy thin-artifact requirement", null);
            }
            if (!requirement.artifactId().equals(LyraRuntimeConstants.RUNTIME_ARTIFACT_ID)
                    || !requirement.groupId().equals(LyraRuntimeConstants.RUNTIME_GROUP_ID)
                    || !requirement.version().equals(LyraRuntimeConstants.RUNTIME_VERSION)) {
                throw compatibility("thin artifact names an incompatible runtime", null);
            }
        }
    }

    private static RuntimeAbi decodeAbi(Map<String, Object> object, String field) {
        validateObjectKeys(object, List.of("major", "minor"), Set.of("major", "minor"), field);
        return RuntimeAbi.of(integer(object, "major"), integer(object, "minor"));
    }

    private static ModuleId moduleId(String spelling) {
        try {
            if (spelling.startsWith("path:")) {
                return ModuleId.path(spelling.substring("path:".length()));
            }
            if (spelling.startsWith("uri:")) {
                return ModuleId.uri(URI.create(spelling.substring("uri:".length())));
            }
            throw new IllegalArgumentException("module ID must have a path: or uri: prefix");
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }

    private static void validateObjectKeys(Map<String, Object> object, List<String> order,
                                           Set<String> required, String context) {
        for (String field : required.stream().sorted().toList()) {
            if (!object.containsKey(field)) {
                throw new IllegalArgumentException(context + " is missing required field: " + field);
            }
        }
        int previous = -1;
        for (String key : object.keySet()) {
            int position = order.indexOf(key);
            if (position < 0) {
                if (isUnknownRequired(key)) {
                    throw new IllegalArgumentException(context + " contains unknown required field: " + key);
                }
                continue;
            }
            if (position < previous) {
                throw new IllegalArgumentException(context + " fields are not in canonical order");
            }
            previous = position;
        }
    }

    private static boolean isUnknownRequired(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("required") || lower.contains("required") || key.startsWith("!");
    }

    private static Map<String, Object> objectValue(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return (List<Object>) value;
    }

    private static String string(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String result)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return result;
    }

    private static String nullableString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String result)) {
            throw new IllegalArgumentException(field + " must be a string or null");
        }
        return result;
    }

    private static int integer(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof BigInteger result)
                || result.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || result.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        return result.intValue();
    }

    private static long longValue(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof BigInteger result)
                || result.signum() < 0
                || result.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(field + " must be a non-negative 64-bit integer");
        }
        return result.longValue();
    }

    private static boolean bool(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return result;
    }

    private static String decodeUtf8(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            throw compatibility("artifact metadata must not contain a UTF-8 BOM", null);
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (!value.isEmpty() && value.charAt(0) == '\ufeff') {
                throw compatibility("artifact metadata must not contain a UTF-8 BOM", null);
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw compatibility("artifact metadata is not valid UTF-8", exception);
        }
    }

    private static byte[] encodeUtf8(String value) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("JSON string is not valid UTF-8", exception);
        }
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static LyraCompatibilityException compatibility(String summary, Throwable cause) {
        return cause == null ? new LyraCompatibilityException(summary)
                : new LyraCompatibilityException(summary, List.of(), List.of(), cause);
    }

    private static final class MetadataParseException extends RuntimeException {
        private MetadataParseException(String message) {
            super(message);
        }
    }

    private static final class JsonParser {
        private final String input;
        private int index;

        private JsonParser(String input) {
            this.input = input;
        }

        private Map<String, Object> parseObject() {
            Object result = value();
            if (!(result instanceof Map<?, ?> object)) {
                throw error("metadata root must be an object");
            }
            if (index != input.length()) {
                throw error("trailing JSON data");
            }
            return objectValue(object, "metadata root");
        }

        private Object value() {
            if (index >= input.length()) {
                throw error("unexpected end of JSON");
            }
            return switch (input.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> stringValue();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            index++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) {
                return result;
            }
            while (true) {
                if (index >= input.length() || input.charAt(index) != '"') {
                    throw error("object key must be a string");
                }
                String key = stringValue();
                if (result.containsKey(key)) {
                    throw error("duplicate object field: " + key);
                }
                expect(':');
                result.put(key, value());
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            index++;
            ArrayList<Object> result = new ArrayList<>();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(value());
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String stringValue() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character < 0x20) {
                    throw error("unescaped control character in JSON string");
                }
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (index >= input.length()) {
                    throw error("truncated JSON escape");
                }
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicodeEscape());
                    default -> throw error("invalid JSON escape: \\" + escape);
                }
            }
            throw error("unterminated JSON string");
        }

        private char unicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("truncated Unicode JSON escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                char hexadecimal = input.charAt(index++);
                if (hexadecimal >= 'A' && hexadecimal <= 'F') {
                    throw error("non-minimal Unicode JSON escape");
                }
                int digit;
                if (hexadecimal >= '0' && hexadecimal <= '9') {
                    digit = hexadecimal - '0';
                } else if (hexadecimal >= 'a' && hexadecimal <= 'f') {
                    digit = hexadecimal - 'a' + 10;
                } else {
                    throw error("invalid Unicode JSON escape");
                }
                value = (value << 4) | digit;
            }
            char character = (char) value;
            if (character >= 0x20) {
                throw error("non-minimal Unicode JSON escape");
            }
            if (character == '\b' || character == '\f' || character == '\n'
                    || character == '\r' || character == '\t' || character == '"'
                    || character == '\\') {
                throw error("non-minimal Unicode JSON escape");
            }
            return character;
        }

        private Object literal(String spelling, Object value) {
            if (!input.startsWith(spelling, index)) {
                throw error("invalid JSON literal");
            }
            index += spelling.length();
            return value;
        }

        private Object number() {
            int start = index;
            if (consume('-') && index >= input.length()) {
                throw error("truncated JSON number");
            }
            if (consume('0')) {
                if (index < input.length() && asciiDigit(input.charAt(index))) {
                    throw error("leading zero in JSON number");
                }
            } else {
                if (index >= input.length() || input.charAt(index) < '1'
                        || input.charAt(index) > '9') {
                    throw error("invalid JSON number");
                }
                while (index < input.length() && asciiDigit(input.charAt(index))) {
                    index++;
                }
            }
            boolean fractional = false;
            if (consume('.')) {
                fractional = true;
                int fractionStart = index;
                while (index < input.length() && asciiDigit(input.charAt(index))) {
                    index++;
                }
                if (fractionStart == index) {
                    throw error("fraction requires digits");
                }
            }
            if (consume('e')) {
                fractional = true;
                consume('+');
                consume('-');
                int exponentStart = index;
                while (index < input.length() && asciiDigit(input.charAt(index))) {
                    index++;
                }
                if (exponentStart == index) {
                    throw error("exponent requires digits");
                }
            } else if (index < input.length() && input.charAt(index) == 'E') {
                throw error("uppercase exponent is not canonical");
            }
            String spelling = input.substring(start, index);
            if (spelling.startsWith("-") && fractional && new BigDecimal(spelling).signum() == 0
                    || spelling.equals("-0")) {
                throw error("non-canonical negative zero");
            }
            try {
                return fractional ? new BigDecimal(spelling) : new BigInteger(spelling);
            } catch (NumberFormatException exception) {
                throw error("invalid JSON number");
            }
        }

        private boolean asciiDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            if (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                throw error("whitespace is not permitted in canonical JSON");
            }
            return false;
        }

        private MetadataParseException error(String message) {
            return new MetadataParseException(message + " at offset " + index);
        }
    }
}
