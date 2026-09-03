package io.mindspice.lyra.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return computeWithMapCount(compilerBuild, sortedModules, javaNameMap, profile, packagingMode,
                previewRequired);
    }

    private static ArtifactRevision computeWithMapCount(String compilerBuild,
                                                        List<ModuleMetadata> modules,
                                                        Map<String, String> javaNameMap,
                                                        RuntimeProfile profile,
                                                        PackagingMode packagingMode,
                                                        boolean previewRequired) {
        MessageDigest digest = sha256();
        putString(digest, DOMAIN_TAG);
        putInt(digest, LyraRuntimeConstants.LANGUAGE_CONTRACT_VERSION);
        putString(digest, compilerBuild);
        putString(digest, profile.name());
        putInt(digest, profile.javaClassFileTarget());
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
