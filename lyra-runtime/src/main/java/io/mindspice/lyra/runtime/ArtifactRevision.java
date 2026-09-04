package io.mindspice.lyra.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic revision of artifact compatibility inputs. */
public final class ArtifactRevision implements Comparable<ArtifactRevision> {
    public static final String DOMAIN_TAG = "LYRA-ARTIFACT-REVISION";
    private final String value;

    private ArtifactRevision(String value) {
        this.value = value;
    }

    public static ArtifactRevision of(String value) {
        Objects.requireNonNull(value, "revision");
        if (!ModuleRevision.isRevision(value)) {
            throw new IllegalArgumentException("invalid artifact revision: " + value);
        }
        return new ArtifactRevision(value);
    }

    public static ArtifactRevision from(String value) {
        return of(value);
    }

    public static ArtifactRevision compute(String compilerBuild,
                                           List<ModuleMetadata> modules,
                                           Map<String, String> javaNameMap,
                                           RuntimeProfile profile,
                                           PackagingMode packagingMode) {
        return compute(compilerBuild, modules, javaNameMap, profile, packagingMode, false);
    }

    public static ArtifactRevision compute(String compilerBuild,
                                           List<ModuleMetadata> modules,
                                           Map<String, String> javaNameMap,
                                           RuntimeProfile profile,
                                           PackagingMode packagingMode,
                                           boolean previewRequired) {
        return compute(compilerBuild, modules, javaNameMap, profile, packagingMode,
                previewRequired, List.of());
    }

    /**
     * Computes a revision including the complete source hash/entry projection.
     * The source projection is part of the packaging contract: adding or
     * removing embedded source bytes must not retain the revision of a
     * different published artifact.
     */
    public static ArtifactRevision compute(String compilerBuild,
                                           List<ModuleMetadata> modules,
                                           Map<String, String> javaNameMap,
                                           RuntimeProfile profile,
                                           PackagingMode packagingMode,
                                           boolean previewRequired,
                                           List<? extends SourceMetadata> sources) {
        return compute(compilerBuild, modules, javaNameMap, profile, packagingMode,
                previewRequired, sources, Optional.empty());
    }

    /** Computes a revision including the exact external runtime requirement. */
    public static ArtifactRevision compute(String compilerBuild,
                                           List<ModuleMetadata> modules,
                                           Map<String, String> javaNameMap,
                                           RuntimeProfile profile,
                                           PackagingMode packagingMode,
                                           boolean previewRequired,
                                           List<? extends SourceMetadata> sources,
                                           Optional<RuntimeRequirement> runtimeRequirement) {
        return compute(compilerBuild, modules, javaNameMap, profile, packagingMode,
                previewRequired, "lyra.generated", sources, runtimeRequirement);
    }

    /** Computes a revision including the configured generated Java package. */
    public static ArtifactRevision compute(String compilerBuild,
                                           List<ModuleMetadata> modules,
                                           Map<String, String> javaNameMap,
                                           RuntimeProfile profile,
                                           PackagingMode packagingMode,
                                           boolean previewRequired,
                                           String javaPackage,
                                           List<? extends SourceMetadata> sources,
                                           Optional<RuntimeRequirement> runtimeRequirement) {
        CanonicalJson.requireUtf8(compilerBuild, "compilerBuild");
        if (compilerBuild.isBlank()) {
            throw new IllegalArgumentException("compilerBuild must not be blank");
        }
        for (int index = 0; index < compilerBuild.length(); index++) {
            if (Character.isISOControl(compilerBuild.charAt(index))) {
                throw new IllegalArgumentException("compilerBuild contains a control character");
            }
        }
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(javaNameMap, "javaNameMap");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(packagingMode, "packagingMode");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(runtimeRequirement, "runtimeRequirement");
        requireJavaPackage(javaPackage);
        List<ModuleMetadata> sortedModules = new ArrayList<>(modules.size());
        for (ModuleMetadata module : modules) {
            sortedModules.add(Objects.requireNonNull(module, "modules must not contain null"));
        }
        sortedModules.sort((left, right) -> left.id().compareTo(right.id()));
        for (int index = 1; index < sortedModules.size(); index++) {
            if (sortedModules.get(index - 1).id().equals(sortedModules.get(index).id())) {
                throw new IllegalArgumentException("duplicate module metadata: "
                        + sortedModules.get(index).id());
            }
        }
        List<SourceMetadata> sortedSources = new ArrayList<>(sources.size());
        for (SourceMetadata source : sources) {
            sortedSources.add(Objects.requireNonNull(source, "sources must not contain null"));
        }
        sortedSources.sort(SourceMetadata::compareTo);
        for (int index = 1; index < sortedSources.size(); index++) {
            if (sortedSources.get(index - 1).sourceId().equals(sortedSources.get(index).sourceId())) {
                throw new IllegalArgumentException("duplicate source metadata: "
                        + sortedSources.get(index).sourceId());
            }
        }
        return computeWithMapCount(compilerBuild, sortedModules, javaNameMap, profile, packagingMode,
                previewRequired, javaPackage, sortedSources, runtimeRequirement);
    }

    private static ArtifactRevision computeWithMapCount(String compilerBuild,
                                                        List<ModuleMetadata> modules,
                                                        Map<String, String> javaNameMap,
                                                        RuntimeProfile profile,
                                                        PackagingMode packagingMode,
                                                        boolean previewRequired,
                                                        String javaPackage,
                                                        List<? extends SourceMetadata> sources,
                                                        Optional<RuntimeRequirement> runtimeRequirement) {
        MessageDigest digest = sha256();
        putString(digest, DOMAIN_TAG);
        putInt(digest, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
        putString(digest, compilerBuild);
        putString(digest, javaPackage);
        putString(digest, profile.name());
        putInt(digest, profile.javaClassFileTarget());
        putInt(digest, profile.previewSupported() ? 1 : 0);
        putInt(digest, profile.runtimeAbi().major());
        putInt(digest, profile.runtimeAbi().minor());
        putString(digest, packagingMode.canonicalSpelling());
        putInt(digest, previewRequired ? 1 : 0);
        putInt(digest, modules.size());
        for (ModuleMetadata module : modules) {
            putString(digest, module.id().canonicalSpelling());
            putString(digest, module.revision().value());
            putString(digest, module.sourceLabel());
        }
        List<Map.Entry<String, String>> names = new ArrayList<>(javaNameMap.entrySet());
        for (Map.Entry<String, String> entry : names) {
            CanonicalJson.requireUtf8(entry.getKey(), "Java name map key");
            CanonicalJson.requireUtf8(entry.getValue(), "Java name map value");
        }
        names.sort(Map.Entry.comparingByKey());
        putInt(digest, names.size());
        for (Map.Entry<String, String> entry : names) {
            putString(digest, entry.getKey());
            putString(digest, entry.getValue());
        }
        putInt(digest, sources.size());
        for (SourceMetadata source : sources) {
            putString(digest, source.sourceId().kindTag());
            putString(digest, source.sourceId().canonicalSpelling());
            putString(digest, source.sourceLabel());
            putString(digest, source.sha256());
            putString(digest, source.entryName().orElse(""));
        }
        putInt(digest, runtimeRequirement.isPresent() ? 1 : 0);
        runtimeRequirement.ifPresent(requirement -> {
            putString(digest, requirement.groupId());
            putString(digest, requirement.artifactId());
            putString(digest, requirement.version());
            putString(digest, requirement.profile().name());
            putInt(digest, requirement.profile().javaClassFileTarget());
            putInt(digest, requirement.profile().previewSupported() ? 1 : 0);
            putInt(digest, requirement.previewRequired() ? 1 : 0);
            putInt(digest, requirement.minimumRuntimeAbi().major());
            putInt(digest, requirement.minimumRuntimeAbi().minor());
        });
        return new ArtifactRevision(HexFormat.of().formatHex(digest.digest()));
    }

    public String value() {
        return value;
    }

    public String hex() {
        return value;
    }

    public String canonicalSpelling() {
        return value;
    }

    @Override
    public int compareTo(ArtifactRevision other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArtifactRevision revision && value.equals(revision.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static void requireJavaPackage(String value) {
        CanonicalJson.requireUtf8(value, "javaPackage");
        if (value.isBlank() || value.startsWith(".") || value.endsWith(".")
                || value.contains("/") || value.chars().anyMatch(character -> character == 92)) {
            throw new IllegalArgumentException("invalid Java package: " + value);
        }
        for (String part : value.split("[.]", -1)) {
            if (part.isEmpty() || isJavaKeyword(part)
                    || !Character.isJavaIdentifierStart(part.codePointAt(0))) {
                throw new IllegalArgumentException("invalid Java package: " + value);
            }
            for (int offset = Character.charCount(part.codePointAt(0)); offset < part.length();) {
                int codePoint = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(codePoint)) {
                    throw new IllegalArgumentException("invalid Java package: " + value);
                }
                offset += Character.charCount(codePoint);
            }
        }
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void putString(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
