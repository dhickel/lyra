package io.mindspice.lyra.runtime;

import java.util.Objects;

/** External runtime requirement recorded by a thin artifact. */
public final class RuntimeRequirement {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final RuntimeProfile profile;
    private final boolean previewRequired;
    private final RuntimeAbi minimumRuntimeAbi;

    public RuntimeRequirement(String groupId, String artifactId, String version,
                              RuntimeProfile profile, boolean previewRequired,
                              RuntimeAbi minimumRuntimeAbi) {
        this.groupId = text(groupId, "groupId");
        this.artifactId = text(artifactId, "artifactId");
        this.version = text(version, "version");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (profile.javaClassFileTarget() != LyraRuntimeConstants.JAVA_CLASS_FILE_TARGET) {
            throw new IllegalArgumentException("runtime requirement must use the Java-25 profile");
        }
        this.previewRequired = previewRequired;
        this.minimumRuntimeAbi = Objects.requireNonNull(minimumRuntimeAbi, "minimumRuntimeAbi");
        if (previewRequired && !profile.previewSupported()) {
            throw new IllegalArgumentException("a preview requirement needs a preview-capable profile");
        }
    }

    public RuntimeRequirement(String groupId, String artifactId, String version,
                              RuntimeProfile profile, RuntimeAbi minimumRuntimeAbi) {
        this(groupId, artifactId, version, profile, false, minimumRuntimeAbi);
    }

    public static RuntimeRequirement current() {
        return new RuntimeRequirement(LyraRuntimeConstants.RUNTIME_GROUP_ID,
                LyraRuntimeConstants.RUNTIME_ARTIFACT_ID, LyraRuntimeConstants.RUNTIME_VERSION,
                RuntimeProfile.CURRENT, false, RuntimeAbi.CURRENT);
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    public String version() {
        return version;
    }

    public RuntimeProfile profile() {
        return profile;
    }

    public boolean previewRequired() {
        return previewRequired;
    }

    public RuntimeAbi minimumRuntimeAbi() {
        return minimumRuntimeAbi;
    }

    public RuntimeAbi minimumAbi() {
        return minimumRuntimeAbi;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RuntimeRequirement requirement
                && groupId.equals(requirement.groupId) && artifactId.equals(requirement.artifactId)
                && version.equals(requirement.version) && profile.equals(requirement.profile)
                && previewRequired == requirement.previewRequired
                && minimumRuntimeAbi.equals(requirement.minimumRuntimeAbi);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, profile, previewRequired, minimumRuntimeAbi);
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
}
