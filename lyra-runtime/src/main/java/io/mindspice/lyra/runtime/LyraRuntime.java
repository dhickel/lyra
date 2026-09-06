package io.mindspice.lyra.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Runtime loading boundary for verified Lyra class-directory, thin, and bundled artifacts. */
public final class LyraRuntime {
    private static final String GENERATED_PREFIX = "$lyra$";
    private static final String FACADE_PREFIX = "$lyra$facade$";
    private static final String RUNTIME_PACKAGE = "io.mindspice.lyra.runtime.";
    private static final String REQUIRED_LAUNCHER_ENTRY =
            "io/mindspice/lyra/runtime/LyraLauncher.class";
    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final String SOURCES_PATH = "META-INF/lyra/sources/";
    private static final int CLASS_FILE_MAGIC = 0xcafebabe;
    private static final int CLASS_FILE_MAJOR_OFFSET = 6;
    private static final int PREVIEW_MINOR = 0xffff;
    private static final ClassLoader SHARED_RUNTIME_LOADER = LyraRuntime.class.getClassLoader();

    static {
        if (SHARED_RUNTIME_LOADER == null) {
            throw new ExceptionInInitializerError("Lyra runtime has no defining class loader");
        }
    }

    private LyraRuntime() {
    }

    /** Returns the one parent loader shared by all custom Lyra artifact loaders. */
    public static ClassLoader sharedRuntimeLoader() {
        return SHARED_RUNTIME_LOADER;
    }

    /** Loads an immutable compiler-produced or runtime-owned artifact view. */
    public static LoadedArtifact load(ArtifactSource artifact, LoadOptions options) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(options, "options");
        ArtifactData data = copyArtifact(artifact);
        return load(data, options, Container.IN_MEMORY);
    }

    /** Loads an artifact with the immutable default loading options. */
    public static LoadedArtifact load(ArtifactSource artifact) {
        return load(artifact, LoadOptions.defaults());
    }

    /** Loads a class directory, thin JAR, or bundled JAR from a filesystem path. */
    public static LoadedArtifact load(Path classesOrJar, LoadOptions options) throws IOException {
        Objects.requireNonNull(classesOrJar, "classesOrJar");
        Objects.requireNonNull(options, "options");
        Path path = classesOrJar.toAbsolutePath().normalize();
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return load(readDirectory(path), options, Container.CLASS_DIRECTORY);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("artifact path is neither a directory nor a regular file: " + path);
        }
        return load(readJar(path), options, Container.JAR);
    }

    /** Loads a class directory, thin JAR, or bundled JAR using default options. */
    public static LoadedArtifact load(Path classesOrJar) throws IOException {
        return load(classesOrJar, LoadOptions.defaults());
    }

    /**
     * Loads binary-name class bytes with explicit metadata and debug-map bytes.
     * This is the in-memory equivalent of a class directory.
     */
    public static LoadedArtifact load(Map<String, byte[]> classes,
                                      ArtifactMetadata metadata,
                                      byte[] debugMapUtf8,
                                      LoadOptions options) {
        return load(ArtifactSource.inMemory(metadata, classes, debugMapUtf8), options);
    }

    /** Loads binary-name class bytes using default options. */
    public static LoadedArtifact load(Map<String, byte[]> classes,
                                      ArtifactMetadata metadata,
                                      byte[] debugMapUtf8) {
        return load(classes, metadata, debugMapUtf8, LoadOptions.defaults());
    }

    /** Loads binary-name class bytes with a structured debug map. */
    public static LoadedArtifact load(Map<String, byte[]> classes,
                                      ArtifactMetadata metadata,
                                      DebugMapMetadata debugMap,
                                      LoadOptions options) {
        return load(ArtifactSource.inMemory(metadata, classes, debugMap), options);
    }

    /** Loads already normalized artifact entries from memory. */
    public static LoadedArtifact loadEntries(Map<String, byte[]> entries,
                                             LoadOptions options) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(options, "options");
        byte[] metadataBytes = requireEntry(entries, LyraRuntimeConstants.ARTIFACT_METADATA_PATH);
        ArtifactMetadata metadata = readMetadata(metadataBytes, options);
        return load(new MemoryArtifactSource(metadata, entries), options);
    }

    /**
     * Tooling-only extraction of an already executed submission's exact result.
     * Boxing occurs only here, after execution; generated storage and the accessor
     * keep their exact primitive/reference descriptors. No arbitrary member name
     * or user function can be invoked through this boundary.
     */
    public static Object readSubmissionResult(ModuleHandle module, LyraType expectedType) {
        Objects.requireNonNull(expectedType, "expectedType");
        if (!(Objects.requireNonNull(module, "module") instanceof ModuleHandleImpl handle)) {
            throw new LyraLinkException("submission result requires a runtime-owned module");
        }
        handle.requireOwner();
        if (handle.isClosed() || handle.context.isClosed()) {
            throw new LyraClosedException("submission result module is closed");
        }
        try {
            Method accessor = handle.facade.getMethod("$lyra$sessionResult");
            LyraSubmissionResult contract = accessor.getAnnotation(LyraSubmissionResult.class);
            if (contract == null || !expectedType.canonicalSpelling().equals(contract.value())
                    || Modifier.isStatic(accessor.getModifiers()) || accessor.getReturnType() == void.class) {
                throw new LyraLinkException("submission result contract mismatch");
            }
            MethodHandle reader = MethodHandles.publicLookup().unreflect(accessor)
                    .bindTo(handle.instance).asType(MethodType.methodType(Object.class));
            return (Object) reader.invokeExact();
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new LyraLinkException("module has no generated submission result", List.of(), failure);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new LyraInternalException("submission result extraction failed", List.of(), List.of(), failure);
        }
    }

    /** Loads one new submission with a separately authenticated typed link table. */
    public static LoadedArtifact loadSubmission(ArtifactSource artifact, LoadOptions options,
                                               SessionStorageDomain.Linkage linkage) {
        Objects.requireNonNull(linkage, "linkage").validate(artifact.metadata());
        return load(copyArtifact(artifact), options, Container.IN_MEMORY, linkage);
    }

    /**
     * Constructs an authenticated source-local submission shell without executing source.
     * The owner retains this OPEN generation before its one-shot execution, so values
     * escaping a later failed evaluation keep their original lifecycle and storage.
     */
    public static ModuleHandle prepareSubmission(LoadedArtifact artifact) {
        if (!(Objects.requireNonNull(artifact, "artifact") instanceof LoadedArtifactImpl loaded)
                || loaded.artifactKey.sessionLinkage() == null || loaded.metadata.modules().size() != 1) {
            throw new LyraLinkException("submission preparation requires a source-local session artifact");
        }
        loaded.artifactKey.sessionLinkage().validate(loaded.metadata);
        Class<?> facade = loaded.facades.get(loaded.metadata.rootModuleId());
        try {
            Method result = facade.getMethod("$lyra$sessionResult");
            Method run = facade.getMethod("$lyra$sessionRun");
            if (result.getAnnotation(LyraSubmissionResult.class) == null
                    || Modifier.isStatic(run.getModifiers()) || run.getReturnType() != void.class) {
                throw new LyraLinkException("artifact has no guarded submission entry point");
            }
        } catch (ReflectiveOperationException failure) {
            throw new LyraLinkException("artifact has no guarded submission entry point", List.of(), failure);
        }
        return loaded.instantiate(loaded.metadata.rootModuleId(), true);
    }

    /** Executes only the prepared generation's new forms; failure does not close it. */
    public static void executeSubmission(ModuleHandle module) {
        if (!(Objects.requireNonNull(module, "module") instanceof ModuleHandleImpl handle)) {
            throw new LyraLinkException("submission execution requires a runtime-owned module");
        }
        handle.requireOwner();
        handle.requireOpen();
        if (!handle.deferredSubmission) throw new LyraLinkException("submission was not prepared for execution");
        try {
            MethodHandle run = MethodHandles.publicLookup().findVirtual(handle.facade,
                    "$lyra$sessionRun", MethodType.methodType(void.class)).bindTo(handle.instance);
            run.invokeExact();
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new LyraLinkException("submission entry point is not callable", List.of(), failure);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new LyraInternalException("submission execution failed", List.of(), List.of(), failure);
        }
    }

    static void requireSessionDataGeneration(ModuleHandle module, SessionStorageDomain domain) {
        if (!(module instanceof ModuleHandleImpl handle)) {
            throw new LyraLinkException("aggregate storage requires a runtime-owned generation");
        }
        handle.requireOwner();
        SessionStorageDomain.Linkage linkage = handle.context.artifactKey.sessionLinkage();
        // Until imported provenance is retained in session snapshots, only a
        // source-local generation in this exact domain can certify data storage.
        if (linkage == null || !linkage.belongsTo(domain)
                || handle.context.metadata.modules().size() != 1) {
            throw new LyraLinkException("aggregate storage requires a source-local generation in this session domain");
        }
    }

    static MethodHandle submissionStorageAccessor(ModuleHandle module,
            SessionStorageDomain.Requirement requirement, boolean write) {
        if (!(Objects.requireNonNull(module, "module") instanceof ModuleHandleImpl handle)) {
            throw new LyraLinkException("session storage requires a runtime-owned generation");
        }
        handle.requireOwner();
        if (handle.isClosed() || handle.context.isClosed()) throw new LyraClosedException("storage generation is closed");
        try {
            Method reader = handle.facade.getMethod("$lyra$sessionRead$binding$" + requirement.id());
            LyraSessionBinding binding = reader.getAnnotation(LyraSessionBinding.class);
            if (binding == null || binding.id() != requirement.id()
                    || binding.storageIdentity() != requirement.storageIdentity() || !binding.name().equals(requirement.name())
                    || !binding.type().equals(requirement.type()) || binding.mutable() != requirement.writable()
                    || Modifier.isStatic(reader.getModifiers())
                    || reader.getReturnType() != SessionStorageDomain.storageClass(LyraType.parse(requirement.type()),
                            handle.context.loader, handle.context.metadata.javaPackage())) {
                throw new LyraLinkException("generated storage contract mismatch");
            }
            Method accessor = write ? handle.facade.getMethod("$lyra$sessionWrite$binding$" + requirement.id(),
                    reader.getReturnType()) : reader;
            if (write && (!binding.mutable() || accessor.getReturnType() != void.class
                    || Modifier.isStatic(accessor.getModifiers()))) throw new LyraLinkException("invalid storage setter");
            return MethodHandles.publicLookup().unreflect(accessor).bindTo(handle.instance);
        } catch (ReflectiveOperationException failure) {
            throw new LyraLinkException("generated session storage accessor is absent", List.of(), failure);
        }
    }

    private static LoadedArtifact load(ArtifactData data, LoadOptions options, Container container) {
        return load(data, options, container, null);
    }

    private static LoadedArtifact load(ArtifactData data, LoadOptions options,
                                       Container container, SessionStorageDomain.Linkage linkage) {
        ArtifactMetadata metadata = preflight(data, options, container);
        if (linkage != null) linkage.validate(metadata);
        // Bundled artifacts carry a copy of the runtime for java -jar, but
        // generated classes must still resolve against this one shared parent
        // runtime.  classEntries() therefore excludes bundled runtime classes
        // instead of defining a duplicate runtime domain in the child loader.
        Map<String, byte[]> classes = classEntries(data.entries(), metadata);
        ClassLoader parent = linkage == null ? SHARED_RUNTIME_LOADER : linkage.typeLoader(classes);
        ArtifactClassLoader loader = new ArtifactClassLoader(parent, classes);
        try {
            Map<String, Class<?>> defined = loader.defineAll();
            validateDebugMap(data.entries(), metadata, defined, loader);
            Map<ModuleId, Class<?>> facades = validateFacades(metadata, defined, loader);
            LoadedArtifact result = new LoadedArtifactImpl(metadata, options, loader, facades, linkage);
            if (linkage != null) linkage.publishTypes(parent);
            return result;
        } catch (VerifyError failure) {
            loader.closeLoader();
            throw new LyraVerificationException(
                    "JVM verification failed while defining Lyra artifact classes", List.of(),
                    List.of(), failure);
        } catch (LinkageError failure) {
            rethrowHostIntegrityFailure(failure);
            loader.closeLoader();
            throw new LyraLinkException(
                    "JVM linkage failed while defining Lyra artifact classes", List.of(),
                    List.of(), failure);
        } catch (RuntimeException failure) {
            loader.closeLoader();
            throw failure;
        }
    }

    private static ArtifactData copyArtifact(ArtifactSource artifact) {
        ArtifactMetadata suppliedMetadata;
        Map<String, byte[]> suppliedEntries;
        try {
            suppliedMetadata = Objects.requireNonNull(artifact.metadata(), "artifact metadata");
            suppliedEntries = Objects.requireNonNull(artifact.entries(), "artifact entries");
        } catch (RuntimeException failure) {
            throw new LyraCompatibilityException("in-memory artifact has no valid artifact view",
                    List.of(), List.of(), failure);
        }
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, byte[]> entry : suppliedEntries.entrySet()) {
                String name = Objects.requireNonNull(entry.getKey(), "artifact entry name");
                byte[] bytes = Objects.requireNonNull(entry.getValue(), "artifact entry bytes");
                if (entries.put(name, bytes.clone()) != null) {
                    throw new IllegalArgumentException("duplicate artifact entry: " + name);
                }
            }
        } catch (RuntimeException failure) {
            throw compatibility("in-memory artifact contains an invalid entry", failure);
        }
        return new ArtifactData(suppliedMetadata,
                Collections.unmodifiableMap(entries), OptionalManifest.absent());
    }

    private static ArtifactData readDirectory(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new IOException("artifact directory does not exist: " + root);
        }
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparing(value -> root.relativize(value).toString()))
                    .toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw compatibility("artifact directory contains a non-regular entry: " + path, null);
                }
                Path relative = root.relativize(path);
                String name = entryName(relative);
                if (entries.put(name, Files.readAllBytes(path)) != null) {
                    throw compatibility("duplicate artifact directory entry: " + name, null);
                }
            }
        }
        return new ArtifactData(null, Collections.unmodifiableMap(entries), OptionalManifest.absent());
    }

    private static ArtifactData readJar(Path path) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        byte[] manifest = null;
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    throw compatibility("JAR contains a directory entry: " + entry.getName(), null);
                }
                String name = validateEntryName(entry.getName());
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                if (entries.put(name, bytes) != null) {
                    throw compatibility("duplicate JAR entry: " + name, null);
                }
                if (name.equals(MANIFEST_PATH)) {
                    manifest = bytes.clone();
                }
            }
        } catch (ZipException failure) {
            throw compatibility("artifact is not a valid JAR", failure);
        }
        return new ArtifactData(null, Collections.unmodifiableMap(entries),
                manifest == null ? OptionalManifest.absent() : OptionalManifest.of(manifest));
    }

    private static ArtifactMetadata preflight(ArtifactData data, LoadOptions options,
                                              Container container) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(options, "options");
        Map<String, byte[]> entries = data.entries();
        byte[] metadataBytes = requireEntry(entries, LyraRuntimeConstants.ARTIFACT_METADATA_PATH);
        ArtifactMetadata parsed = readMetadata(metadataBytes, options);
        if (data.suppliedMetadata() != null && !parsed.equals(data.suppliedMetadata())) {
            throw compatibility("in-memory artifact metadata object disagrees with its metadata entry", null);
        }
        byte[] debugBytes = requireEntry(entries, LyraRuntimeConstants.DEBUG_MAP_PATH);
        try {
            DebugMapReader.read(debugBytes, parsed);
        } catch (LyraCompatibilityException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw compatibility("artifact debug map is malformed or inconsistent", failure);
        }
        Set<String> declaredSources = validateSources(entries, parsed);
        validateEntryInventory(entries, parsed, declaredSources, container);
        validateContainer(entries, parsed, data.manifest(), container);
        validateClassEntries(entries, parsed);
        return parsed;
    }

    private static ArtifactMetadata readMetadata(byte[] bytes, LoadOptions options) {
        try {
            ArtifactMetadata metadata = ArtifactMetadataReader.read(
                    bytes, options.profile(), options.runtimeAbi());
            if (metadata.previewRequired() && !options.previewEnabled()) {
                throw compatibility("artifact requires Java preview to be enabled", null);
            }
            return metadata;
        } catch (LyraCompatibilityException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw compatibility("artifact metadata is malformed or incompatible", failure);
        }
    }

    private static Set<String> validateSources(Map<String, byte[]> entries, ArtifactMetadata metadata) {
        Set<String> declared = new HashSet<>();
        for (SourceMetadata source : metadata.sources()) {
            source.entryName().ifPresent(name -> {
                if (!declared.add(name)) {
                    throw compatibility("artifact source entries are duplicated", null);
                }
                byte[] bytes = entries.get(name);
                if (bytes == null) {
                    throw compatibility("artifact is missing embedded source entry: " + name, null);
                }
                if (!sha256(bytes).equals(source.sha256())) {
                    throw compatibility("embedded source hash does not match metadata: " + name, null);
                }
            });
        }
        for (String name : entries.keySet()) {
            if (name.startsWith(SOURCES_PATH) && !declared.contains(name)) {
                throw compatibility("artifact contains an undeclared source entry: " + name, null);
            }
        }
        return Collections.unmodifiableSet(declared);
    }

    private static void validateEntryInventory(Map<String, byte[]> entries,
                                               ArtifactMetadata metadata,
                                               Set<String> declaredSources,
                                               Container container) {
        for (String name : entries.keySet()) {
            validateEntryName(name);
            if (name.equals(LyraRuntimeConstants.ARTIFACT_METADATA_PATH)
                    || name.equals(LyraRuntimeConstants.DEBUG_MAP_PATH)
                    || name.endsWith(".class")
                    || declaredSources.contains(name)) {
                continue;
            }
            if (container == Container.JAR && name.equals(MANIFEST_PATH)) {
                continue;
            }
            throw compatibility("artifact contains an unexpected entry: " + name, null);
        }
    }

    private static void validateContainer(Map<String, byte[]> entries, ArtifactMetadata metadata,
                                           OptionalManifest manifest, Container container) {
        switch (container) {
            case CLASS_DIRECTORY, IN_MEMORY -> {
                if (container == Container.CLASS_DIRECTORY && metadata.packagingMode() != PackagingMode.CLASSES) {
                    throw compatibility("class-directory artifacts must use classes packaging", null);
                }
                if (entries.containsKey(MANIFEST_PATH)) {
                    throw compatibility("class-directory artifacts must not contain a manifest", null);
                }
            }
            case JAR -> {
                if (metadata.packagingMode() == PackagingMode.CLASSES) {
                    throw compatibility("JAR artifacts must use thin-jar or bundled-jar packaging", null);
                }
                byte[] bytes = manifest.bytes().orElseThrow(() ->
                        compatibility("JAR is missing META-INF/MANIFEST.MF", null));
                validateManifest(bytes, metadata);
            }
        }
    }

    private static void validateManifest(byte[] bytes, ArtifactMetadata metadata) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw compatibility("JAR manifest is not valid UTF-8", failure);
        }
        if (!text.startsWith("Manifest-Version: 1.0\r\n")
                || !text.endsWith("\r\n\r\n")) {
            throw compatibility("JAR manifest is not canonical", null);
        }
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n'
                    && (index == 0 || text.charAt(index - 1) != '\r')) {
                throw compatibility("JAR manifest must use CRLF line endings", null);
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        List<String> attributeOrder = new ArrayList<>();
        String current = null;
        String[] physicalLines = text.substring(0, text.length() - 2).split("\\r\\n", -1);
        for (int lineIndex = 0; lineIndex < physicalLines.length; lineIndex++) {
            String line = physicalLines[lineIndex];
            if (line.getBytes(StandardCharsets.UTF_8).length + 2 > 72) {
                throw compatibility("JAR manifest line exceeds the 72-byte limit", null);
            }
            if (line.isEmpty()) {
                if (lineIndex != physicalLines.length - 1) {
                    throw compatibility("JAR manifest has an unexpected section separator", null);
                }
                continue;
            }
            if (line.charAt(0) == ' ') {
                if (current == null) {
                    throw compatibility("JAR manifest has an orphan continuation line", null);
                }
                attributes.put(current, attributes.get(current) + line.substring(1));
                continue;
            }
            int colon = line.indexOf(": ");
            if (colon <= 0 || attributes.put(line.substring(0, colon), line.substring(colon + 2)) != null) {
                throw compatibility("JAR manifest has duplicate or malformed attributes", null);
            }
            current = line.substring(0, colon);
            attributeOrder.add(current);
        }
        if (!"1.0".equals(attributes.remove("Manifest-Version"))) {
            throw compatibility("JAR manifest has an invalid Manifest-Version", null);
        }
        if (attributeOrder.isEmpty() || !attributeOrder.removeFirst().equals("Manifest-Version")) {
            throw compatibility("JAR manifest must start with Manifest-Version", null);
        }
        Map<String, String> expected = new TreeMap<>();
        expected.put("Lyra-Artifact-Id", metadata.artifactId());
        expected.put("Lyra-Artifact-Revision", metadata.artifactRevision().value());
        expected.put("Lyra-Java-Profile", metadata.profile().name());
        expected.put("Lyra-Packaging-Mode", metadata.packagingMode().canonicalSpelling());
        expected.put("Lyra-Preview-Required", Boolean.toString(metadata.previewRequired()));
        if (metadata.packagingMode() == PackagingMode.BUNDLED_JAR) {
            expected.put("Main-Class", "io.mindspice.lyra.runtime.LyraLauncher");
        }
        if (!attributeOrder.equals(attributeOrder.stream().sorted().toList())
                || !expected.equals(attributes)) {
            throw compatibility("JAR manifest disagrees with artifact metadata", null);
        }
    }

    private static void validateClassEntries(Map<String, byte[]> entries,
                                             ArtifactMetadata metadata) {
        if (metadata.packagingMode() == PackagingMode.BUNDLED_JAR
                && !entries.containsKey(REQUIRED_LAUNCHER_ENTRY)) {
            throw compatibility("bundled artifact is missing its Lyra launcher", null);
        }
        if (metadata.javaPackage().equals("java") || metadata.javaPackage().startsWith("java.")) {
            throw compatibility("generated artifact namespace is reserved by the JVM: "
                    + metadata.javaPackage(), null);
        }
        if (metadata.javaPackage().equals("io.mindspice.lyra.runtime")
                || metadata.javaPackage().startsWith("io.mindspice.lyra.runtime.")) {
            throw compatibility("generated artifact namespace conflicts with the shared runtime: "
                    + metadata.javaPackage(), null);
        }
        int expectedMajor = metadata.javaClassFileTarget() + 44;
        int classCount = 0;
        int generatedClassCount = 0;
        String packagePrefix = metadata.javaPackage() + ".";
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".class")) {
                continue;
            }
            classCount++;
            String binaryName = binaryNameForClassEntry(name);
            boolean bundledRuntime = metadata.packagingMode() == PackagingMode.BUNDLED_JAR
                    && binaryName.startsWith(RUNTIME_PACKAGE);
            if (!bundledRuntime) {
                generatedClassCount++;
                if (!binaryName.startsWith(packagePrefix)
                        || !binaryName.substring(packagePrefix.length()).startsWith(GENERATED_PREFIX)) {
                    throw compatibility("generated class is outside the artifact namespace: " + binaryName, null);
                }
            }
            byte[] bytes = entry.getValue();
            // Generated class bodies are checked at the controlled definition
            // boundary. Bundled runtime classes are intentionally not defined
            // by this child loader, so at least reject a visibly malformed
            // runtime entry during preflight instead of silently ignoring it.
            if (bytes.length < 8 || u4(bytes, 0) != CLASS_FILE_MAGIC) {
                if (bundledRuntime) {
                    throw compatibility("bundled runtime entry is not a class file: " + name, null);
                }
                continue;
            }
            int minor = u2(bytes, 4);
            int major = u2(bytes, CLASS_FILE_MAJOR_OFFSET);
            if (major != expectedMajor) {
                throw compatibility("class-file target disagrees with artifact metadata: " + binaryName, null);
            }
            if (minor != 0 && minor != PREVIEW_MINOR) {
                throw compatibility("class-file has an invalid preview minor version: " + binaryName, null);
            }
            if (minor == PREVIEW_MINOR && !metadata.previewRequired()) {
                throw compatibility("preview class is absent from preview-required metadata: " + binaryName, null);
            }
        }
        if (classCount == 0 || generatedClassCount == 0) {
            throw compatibility("artifact contains no generated class files", null);
        }
    }

    private static Map<String, byte[]> classEntries(Map<String, byte[]> entries,
                                                    ArtifactMetadata metadata) {
        TreeMap<String, byte[]> classes = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            String binaryName = binaryNameForClassEntry(entry.getKey());
            if (metadata.packagingMode() == PackagingMode.BUNDLED_JAR
                    && binaryName.startsWith(RUNTIME_PACKAGE)) {
                continue;
            }
            classes.put(binaryName, entry.getValue().clone());
        }
        return Collections.unmodifiableMap(classes);
    }

    private static void validateDebugMap(Map<String, byte[]> entries,
                                          ArtifactMetadata metadata,
                                          Map<String, Class<?>> defined,
                                          ArtifactClassLoader loader) {
        DebugMapMetadata debugMap = DebugMapReader.read(
                requireEntry(entries, LyraRuntimeConstants.DEBUG_MAP_PATH), metadata);
        for (DebugMapEntry entry : debugMap.entries()) {
            String binaryName = entry.className().replace('/', '.');
            Class<?> owner = defined.get(binaryName);
            if (owner == null) {
                throw compatibility("debug map refers to an absent generated class: " + binaryName, null);
            }
            MethodType type = methodType(entry.methodDescriptor(), loader);
            if (entry.methodName().equals("<clinit>")) {
                continue;
            }
            boolean found;
            if (entry.methodName().equals("<init>")) {
                found = type.returnType() == void.class
                        && java.util.Arrays.stream(owner.getDeclaredConstructors())
                        .anyMatch(value -> MethodType.methodType(void.class, value.getParameterTypes())
                                .equals(type));
            } else {
                found = java.util.Arrays.stream(owner.getDeclaredMethods())
                        .anyMatch(value -> value.getName().equals(entry.methodName())
                                && MethodType.methodType(value.getReturnType(), value.getParameterTypes())
                                .equals(type));
            }
            if (!found) {
                throw compatibility("debug map refers to an absent generated method: "
                        + binaryName + "#" + entry.methodName() + entry.methodDescriptor(), null);
            }
        }
    }

    private static Map<ModuleId, Class<?>> validateFacades(ArtifactMetadata metadata,
                                                             Map<String, Class<?>> defined,
                                                             ArtifactClassLoader loader) {
        Map<ModuleId, Class<?>> facades = new TreeMap<>();
        Set<String> claimed = new HashSet<>();
        for (ModuleMetadata module : metadata.modules()) {
            String base = facadeBaseName(metadata.javaPackage(), module.id());
            List<String> candidates = defined.keySet().stream()
                    .filter(name -> name.equals(base) || name.startsWith(base + "$"))
                    .sorted()
                    .toList();
            if (candidates.size() != 1) {
                throw compatibility("artifact facade inventory does not match module metadata: "
                        + module.id(), null);
            }
            String name = candidates.getFirst();
            if (!claimed.add(name)) {
                throw compatibility("one generated facade is assigned to multiple modules: " + name, null);
            }
            Class<?> facade = defined.get(name);
            validateFactoryMethods(facade, loader, metadata);
            facades.put(module.id(), facade);
        }
        long actualFacadeCount = defined.keySet().stream()
                .filter(name -> name.substring(name.lastIndexOf('.') + 1).startsWith(FACADE_PREFIX))
                .count();
        if (actualFacadeCount != facades.size()) {
            throw compatibility("artifact contains an unexpected generated facade", null);
        }
        for (ExportMetadata export : metadata.exports()) {
            Class<?> facade = facades.get(export.moduleId());
            if (facade == null) {
                throw compatibility("export facade module is absent: " + export, null);
            }
            MethodType type = methodType(export.jvmDescriptor(), loader);
            String methodName = export.isFunction() ? export.javaName() : export.getterName();
            findPublicVirtual(facade, methodName, type);
            if (export.isFunction()) {
                try {
                    Method valueGetter = facade.getMethod(export.functionValueName());
                    if (Modifier.isStatic(valueGetter.getModifiers())
                            || valueGetter.getParameterCount() != 0
                            || valueGetter.getReturnType() == void.class) {
                        throw compatibility("function-value getter has an invalid shape: " + export, null);
                    }
                } catch (NoSuchMethodException failure) {
                    throw compatibility("function-value getter is absent: " + export, failure);
                }
            }
            if (export.setterName().isPresent()) {
                if (!Modifier.isPublic(facade.getModifiers())) {
                    throw compatibility("export facade is not public: " + facade.getName(), null);
                }
                Class<?> valueType;
                try {
                    String getterName = export.isFunction()
                            ? export.functionValueName() : export.getterName();
                    Method getter = facade.getMethod(getterName);
                    if (Modifier.isStatic(getter.getModifiers())
                            || getter.getParameterCount() != 0
                            || getter.getReturnType() == void.class) {
                        throw compatibility("export getter has an invalid shape: " + export, null);
                    }
                    valueType = getter.getReturnType();
                } catch (NoSuchMethodException failure) {
                    throw compatibility("export getter is absent: " + export, failure);
                }
                findPublicVirtual(facade, export.setterName().orElseThrow(),
                        MethodType.methodType(void.class, valueType));
            }
        }
        return Collections.unmodifiableMap(facades);
    }

    private static void validateFactoryMethods(Class<?> facade, ArtifactClassLoader loader,
                                               ArtifactMetadata expected) {
        if (!Modifier.isPublic(facade.getModifiers())) {
            throw compatibility("generated facade is not public: " + facade.getName(), null);
        }
        if (!AutoCloseable.class.isAssignableFrom(facade)) {
            throw compatibility("generated facade is not AutoCloseable: " + facade.getName(), null);
        }
        try {
            Method factory = facade.getMethod("$lyra$create");
            Method factoryWithOptions = facade.getMethod("$lyra$create", RuntimeOptions.class);
            Method metadata = facade.getMethod("$lyra$metadata");
            if (!Modifier.isStatic(factory.getModifiers())
                    || !Modifier.isStatic(factoryWithOptions.getModifiers())
                    || !Modifier.isStatic(metadata.getModifiers())
                    || factory.getReturnType() != facade
                    || factoryWithOptions.getReturnType() != facade
                    || metadata.getReturnType() != ArtifactMetadata.class) {
                throw compatibility("generated facade factory surface is invalid: " + facade.getName(), null);
            }
            MethodHandle metadataHandle = MethodHandles.publicLookup().findStatic(
                    facade, "$lyra$metadata", MethodType.methodType(ArtifactMetadata.class));
            ArtifactMetadata embedded = (ArtifactMetadata) metadataHandle.invokeExact();
            if (embedded.schemaVersion() != LyraRuntimeConstants.ARTIFACT_SCHEMA_VERSION) {
                throw compatibility("generated facade metadata has an invalid schema", null);
            }
            if (!embedded.equals(expected)) {
                throw compatibility("generated facade metadata disagrees with artifact metadata", null);
            }
        } catch (LyraCompatibilityException failure) {
            throw failure;
        } catch (Throwable failure) {
            rethrowHostIntegrityFailure(failure);
            throw compatibility("generated facade factory surface is invalid: " + facade.getName(), failure);
        }
    }

    private static MethodType methodType(String descriptor, ArtifactClassLoader loader) {
        try {
            return MethodType.fromMethodDescriptorString(descriptor, loader);
        } catch (RuntimeException | LinkageError failure) {
            rethrowHostIntegrityFailure(failure);
            throw compatibility("export JVM descriptor cannot be linked: " + descriptor, failure);
        }
    }

    private static MethodHandle findPublicVirtual(Class<?> facade, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(facade, name, type);
        } catch (NoSuchMethodException | IllegalAccessException | RuntimeException failure) {
            throw compatibility("generated export method is absent or has the wrong descriptor: "
                    + facade.getName() + "#" + name + type, failure);
        } catch (LinkageError failure) {
            rethrowHostIntegrityFailure(failure);
            throw new LyraLinkException(
                    "JVM linkage failed while resolving generated export method: "
                            + facade.getName() + "#" + name + type,
                    List.of(), List.of(), failure);
        }
    }

    private static String facadeBaseName(String javaPackage, ModuleId module) {
        String moduleKey = (module.isUri() ? "uri:" : "path:") + module.value();
        return javaPackage + "." + FACADE_PREFIX
                + sha256("LYRA-JVM-GENERATED-CLASS", "facade", moduleKey).substring(0, 16);
    }

    private static String binaryNameForClassEntry(String entry) {
        String normalized = validateEntryName(entry);
        if (!normalized.endsWith(".class") || normalized.length() == ".class".length()) {
            throw compatibility("invalid class entry name: " + entry, null);
        }
        String binary = normalized.substring(0, normalized.length() - ".class".length())
                .replace('/', '.');
        if (binary.isBlank() || binary.startsWith(".") || binary.endsWith(".")
                || !isBinaryName(binary)
                || !normalized.equals(binary.replace('.', '/') + ".class")) {
            throw compatibility("invalid generated binary name: " + binary, null);
        }
        return binary;
    }

    private static boolean isBinaryName(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            int first = part.codePointAt(0);
            if (!Character.isJavaIdentifierStart(first)) {
                return false;
            }
            for (int offset = Character.charCount(first); offset < part.length();) {
                int codePoint = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(codePoint)) {
                    return false;
                }
                offset += Character.charCount(codePoint);
            }
        }
        return true;
    }

    private static String entryName(Path path) {
        return validateEntryName(path.toString().replace(path.getFileSystem().getSeparator(), "/"));
    }

    private static String validateEntryName(String value) {
        Objects.requireNonNull(value, "entry name");
        if (value.isEmpty() || value.startsWith("/") || value.matches("^[A-Za-z]:[/\\\\].*")
                || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0
                || value.contains("//") || value.startsWith("./") || value.contains("../")
                || value.endsWith("/.") || value.endsWith("/..") || value.endsWith("/")) {
            throw compatibility("invalid artifact entry name: " + value, null);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw compatibility("invalid artifact entry name: " + value, null);
            }
        }
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
        } catch (CharacterCodingException failure) {
            throw compatibility("artifact entry name is not valid UTF-8: " + value, failure);
        }
        for (String component : value.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw compatibility("invalid artifact entry name: " + value, null);
            }
        }
        return value;
    }

    private static byte[] requireEntry(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name);
        if (bytes == null) {
            throw compatibility("artifact is missing required entry: " + name, null);
        }
        return bytes.clone();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static String sha256(String domain, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, domain);
            for (String field : fields) {
                put(digest, field);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static int u2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int u4(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static LyraCompatibilityException compatibility(String summary, Throwable cause) {
        return cause == null ? new LyraCompatibilityException(summary)
                : new LyraCompatibilityException(summary, List.of(), List.of(), cause);
    }

    private static void rethrowHostIntegrityFailure(Throwable failure) {
        if (failure instanceof VirtualMachineError vm) {
            throw vm;
        }
        if (failure instanceof ThreadDeath death) {
            throw death;
        }
    }

    private enum Container {
        CLASS_DIRECTORY,
        JAR,
        IN_MEMORY
    }

    private record ArtifactData(ArtifactMetadata suppliedMetadata,
                                Map<String, byte[]> entries,
                                OptionalManifest manifest) {
        private ArtifactData {
            Objects.requireNonNull(entries, "entries");
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    private static final class OptionalManifest {
        private final byte[] bytes;

        private OptionalManifest(byte[] bytes) {
            this.bytes = bytes == null ? null : bytes.clone();
        }

        static OptionalManifest absent() {
            return new OptionalManifest(null);
        }

        static OptionalManifest of(byte[] bytes) {
            return new OptionalManifest(bytes);
        }

        java.util.Optional<byte[]> bytes() {
            return bytes == null ? java.util.Optional.empty()
                    : java.util.Optional.of(bytes.clone());
        }
    }

    private static final class ArtifactClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        private volatile boolean closed;

        private ArtifactClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
            super(Objects.requireNonNull(parent, "parent"));
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : definitions.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            this.definitions = copy;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Objects.requireNonNull(name, "name");
            synchronized (getClassLoadingLock(name)) {
                Class<?> already = findLoadedClass(name);
                if (already == null) {
                    try {
                        already = getParent().loadClass(name);
                    } catch (ClassNotFoundException ignored) {
                        already = findClass(name);
                    }
                }
                if (resolve) {
                    resolveClass(already);
                }
                return already;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (closed) {
                throw new ClassNotFoundException("Lyra artifact loader is closed: " + name);
            }
            byte[] bytes = definitions.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        private Map<String, Class<?>> defineAll() {
            TreeMap<String, Class<?>> classes = new TreeMap<>();
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(definitions).entrySet()) {
                classes.put(entry.getKey(), defineDirect(entry.getKey(), entry.getValue()));
            }
            for (Class<?> value : classes.values()) {
                resolveClass(value);
            }
            return Collections.unmodifiableMap(classes);
        }

        private Class<?> defineDirect(String name, byte[] bytes) {
            if (getParent() instanceof SessionTypeLoader && SessionTypeLoader.isShared(name)) {
                try {
                    return getParent().loadClass(name);
                } catch (ClassNotFoundException failure) {
                    throw new LyraLinkException("session structural type is absent: " + name, List.of(), failure);
                }
            }
            Class<?> existing = findLoadedClass(name);
            return existing == null ? defineClass(name, bytes, 0, bytes.length) : existing;
        }

        private void closeLoader() {
            closed = true;
            definitions.clear();
        }
    }

    private static final class LoadedArtifactImpl implements LoadedArtifact {
        private final ArtifactMetadata metadata;
        private final LoadOptions options;
        private final ArtifactClassLoader loader;
        private final Map<ModuleId, Class<?>> facades;
        private final LyraArtifactKey artifactKey;

        private final Set<ModuleHandleImpl> instances = new HashSet<>();
        private int activeInstantiations;
        private boolean closed;

        private LoadedArtifactImpl(ArtifactMetadata metadata, LoadOptions options,
                                   ArtifactClassLoader loader,
                                   Map<ModuleId, Class<?>> facades, SessionStorageDomain.Linkage linkage) {
            this.metadata = metadata;
            this.options = options;
            this.loader = loader;
            this.facades = facades;
            // The key is shared by every instance of this loaded artifact so
            // ordinary closure authentication remains artifact-local. Explicit
            // source-local session linkage grants separate cross-generation
            // authority without merging keys. The immutable I/O environment
            // is likewise shared, while each generated state
            // still captures its own caller/owner thread.
            this.artifactKey = new LyraArtifactKey(null, options.ioEnvironment(), linkage);
        }

        @Override
        public ArtifactMetadata metadata() {
            return metadata;
        }

        @Override
        public ModuleHandle instantiate(ModuleId moduleId) {
            return instantiate(moduleId, false);
        }

        private ModuleHandle instantiate(ModuleId moduleId, boolean deferredSubmission) {
            Objects.requireNonNull(moduleId, "rootModule");
            Class<?> facade;
            synchronized (this) {
                if (closed) {
                    throw new LyraLifecycleException("loaded artifact is closed");
                }
                facade = facades.get(moduleId);
                if (facade == null) {
                    throw new IllegalArgumentException("module is absent from loaded artifact: " + moduleId);
                }
            }
            Thread owner = Thread.currentThread();
            boolean instantiationReserved = false;
            try {
                synchronized (this) {
                    if (closed) {
                        throw new LyraLifecycleException("loaded artifact is closed");
                    }
                    activeInstantiations++;
                    instantiationReserved = true;
                }
                if (artifactKey.sessionLinkage() != null) artifactKey.sessionLinkage().validate(metadata);
                LyraArtifactKey instanceKey = deferredSubmission
                        ? new LyraArtifactKey(OwnerThread.of(owner), options.ioEnvironment(),
                                artifactKey.sessionLinkage(), true) : artifactKey;
                RuntimeOptions runtimeOptions = options.runtimeOptions(owner, instanceKey);
                MethodType factoryType = MethodType.methodType(facade, RuntimeOptions.class);
                MethodHandle factory = MethodHandles.publicLookup().findStatic(
                        facade, "$lyra$create", factoryType)
                        .asType(MethodType.methodType(Object.class, RuntimeOptions.class));
                Object instance = (Object) factory.invokeExact(runtimeOptions);
                ModuleHandleImpl handle = new ModuleHandleImpl(this, moduleId, facade, instance, owner, deferredSubmission);
                synchronized (this) {
                    activeInstantiations--;
                    instantiationReserved = false;
                    if (closed) {
                        handle.closeFromContext();
                        throw new LyraLifecycleException("loaded artifact was closed during instantiation");
                    }
                    instances.add(handle);
                }
                return handle;
            } catch (LyraRuntimeException failure) {
                releaseInstantiation(instantiationReserved);
                throw failure;
            } catch (VerifyError failure) {
                releaseInstantiation(instantiationReserved);
                rethrowHostIntegrityFailure(failure);
                throw new LyraVerificationException("JVM verification failed during module instantiation",
                        List.of(), List.of(), failure);
            } catch (LinkageError failure) {
                releaseInstantiation(instantiationReserved);
                rethrowHostIntegrityFailure(failure);
                throw new LyraLinkException("JVM linkage failed during module instantiation",
                        List.of(), List.of(), failure);
            } catch (NoSuchMethodException | IllegalAccessException failure) {
                releaseInstantiation(instantiationReserved);
                throw new LyraLinkException("generated module factory is not callable", List.of(), failure);
            } catch (Throwable failure) {
                releaseInstantiation(instantiationReserved);
                rethrowHostIntegrityFailure(failure);
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new LyraInternalException("module instantiation failed", List.of(), List.of(), failure);
            }
        }

        private synchronized void releaseInstantiation(boolean reserved) {
            if (reserved) {
                activeInstantiations--;
            }
        }

        private synchronized void remove(ModuleHandleImpl handle) {
            instances.remove(handle);
        }

        @Override
        public synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (!instances.isEmpty() || activeInstantiations != 0) {
                    throw new LyraLifecycleException(
                            "loaded artifact cannot close while module instances remain open or initializing");
                }
                closed = true;
                loader.closeLoader();
            }
        }
    }

    private static final class ModuleHandleImpl implements ModuleHandle {
        private final LoadedArtifactImpl context;
        private final ModuleId moduleId;
        private final Class<?> facade;
        private final Object instance;
        private final Thread owner;
        private final boolean deferredSubmission;
        private final Map<ExportKey, ExportHandle> exports = new HashMap<>();
        private boolean closed;

        private ModuleHandleImpl(LoadedArtifactImpl context, ModuleId moduleId,
                                 Class<?> facade, Object instance, Thread owner, boolean deferredSubmission) {
            this.context = context;
            this.moduleId = moduleId;
            this.facade = facade;
            this.instance = instance;
            this.owner = owner;
            this.deferredSubmission = deferredSubmission;
        }

        @Override
        public ModuleId moduleId() {
            return moduleId;
        }

        @Override
        public ArtifactMetadata metadata() {
            return context.metadata();
        }

        @Override
        public synchronized boolean isClosed() {
            requireOwner();
            return closed;
        }

        @Override
        public ExportHandle export(String name, LyraSignature signature) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(signature, "signature");
            synchronized (this) {
                requireOwner();
                requireOpen();
                ExportKey key = new ExportKey(name, signature);
                ExportHandle cached = exports.get(key);
                if (cached != null) {
                    return cached;
                }
                ExportMetadata metadata = context.metadata().exports().stream()
                        .filter(candidate -> candidate.moduleId().equals(moduleId))
                        .filter(candidate -> candidate.name().equals(name))
                        .filter(ExportMetadata::isFunction)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "callable export is absent: " + moduleId + "#" + name));
                if (!metadata.signature().equals(signature)) {
                    throw new IllegalArgumentException("export signature does not match metadata: "
                            + name + " expected " + metadata.signature());
                }
                ArtifactClassLoader loader = context.loader;
                MethodType type = methodType(metadata.jvmDescriptor(), loader);
                MethodHandle bound = findPublicVirtual(facade, metadata.javaName(), type)
                        .bindTo(instance);
                MethodHandle functionValue = null;
                if (metadata.isFunction()) {
                    try {
                        Method getter = facade.getMethod(metadata.functionValueName());
                        functionValue = findPublicVirtual(facade, metadata.functionValueName(),
                                MethodType.methodType(getter.getReturnType())).bindTo(instance);
                    } catch (NoSuchMethodException | RuntimeException failure) {
                        throw new LyraLinkException("generated function-value getter is not callable",
                                List.of(), failure);
                    }
                }
                ExportHandle handle = new ExportHandle(metadata, signature, type, bound, functionValue);
                exports.put(key, handle);
                return handle;
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                requireOwner();
                if (closed) {
                    return;
                }
                try {
                    MethodHandle close = MethodHandles.publicLookup().findVirtual(
                            facade, "close", MethodType.methodType(void.class));
                    close.bindTo(instance).invokeExact();
                    closed = true;
                    exports.clear();
                    context.remove(this);
                } catch (NoSuchMethodException | IllegalAccessException failure) {
                    throw new LyraLinkException("generated module close method is not callable",
                            List.of(), failure);
                } catch (LyraRuntimeException failure) {
                    throw failure;
                } catch (Throwable failure) {
                    rethrowHostIntegrityFailure(failure);
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new LyraInternalException("module close failed", List.of(), List.of(), failure);
                }
            }
        }

        private void closeFromContext() {
            if (Thread.currentThread() != owner) {
                return;
            }
            try {
                close();
            } catch (RuntimeException ignored) {
                // The context only reaches this path during a race after it
                // has already been closed; no partially visible instance is
                // published to the caller.
            }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new LyraThreadException("module handle accessed from a non-owner thread");
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new LyraClosedException("module is closed");
            }
            if (context.isClosed()) {
                throw new LyraClosedException("loaded artifact is closed");
            }
        }

        private record ExportKey(String name, LyraSignature signature) {
        }
    }
}
