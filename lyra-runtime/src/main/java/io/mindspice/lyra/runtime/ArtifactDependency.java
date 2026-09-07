package io.mindspice.lyra.runtime;

import java.util.Objects;

/** A reproducible production dependency required by a non-normal artifact. */
public record ArtifactDependency(String groupId, String artifactId, String version,
                                ArtifactProfile profile) implements Comparable<ArtifactDependency> {
    public ArtifactDependency {
        groupId = text(groupId, "groupId");
        artifactId = text(artifactId, "artifactId");
        version = text(version, "version");
        profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public int compareTo(ArtifactDependency other) {
        Objects.requireNonNull(other, "other");
        int result = groupId.compareTo(other.groupId);
        if (result != 0) return result;
        result = artifactId.compareTo(other.artifactId);
        if (result != 0) return result;
        result = version.compareTo(other.version);
        return result != 0 ? result : profile.compareTo(other.profile);
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-blank and printable");
        }
        return value;
    }
}
