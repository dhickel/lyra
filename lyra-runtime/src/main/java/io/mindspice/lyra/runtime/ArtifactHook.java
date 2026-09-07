package io.mindspice.lyra.runtime;

import java.util.Objects;

/** One generated runtime hook declared by an artifact profile. */
public record ArtifactHook(String name, String descriptor) implements Comparable<ArtifactHook> {
    public ArtifactHook {
        name = text(name, "name");
        descriptor = JvmDescriptorValidator.requireMethodDescriptor(text(descriptor, "descriptor"));
        if (!name.startsWith("$lyra$")) {
            throw new IllegalArgumentException("artifact hooks must use the Lyra namespace: " + name);
        }
    }

    @Override
    public int compareTo(ArtifactHook other) {
        Objects.requireNonNull(other, "other");
        int result = name.compareTo(other.name);
        return result != 0 ? result : descriptor.compareTo(other.descriptor);
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-blank and printable");
        }
        return value;
    }
}
