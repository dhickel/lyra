package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Stable non-empty artifact identity kept separate from its content revision. */
public final class ArtifactId implements Comparable<ArtifactId> {
    private final String value;

    public ArtifactId(String value) {
        CanonicalJson.requireUtf8(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("artifact ID must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("artifact ID contains a control character");
            }
        }
        this.value = value;
    }

    public static ArtifactId of(String value) {
        return new ArtifactId(value);
    }

    public String value() {
        return value;
    }

    public String canonicalSpelling() {
        return value;
    }

    @Override
    public int compareTo(ArtifactId other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArtifactId id && value.equals(id.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
