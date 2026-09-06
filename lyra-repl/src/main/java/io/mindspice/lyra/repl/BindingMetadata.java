package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.BindingMutability;
import io.mindspice.lyra.runtime.LyraType;
import io.mindspice.lyra.runtime.TypeQualifier;

import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for a visible binding; it contains no binding value. */
public record BindingMetadata(
        String name,
        BindingIdentity identity,
        LyraType type,
        BindingVisibility visibility,
        BindingMutability mutability,
        Optional<StorageIdentity> storageIdentity) {
    public BindingMetadata {
        name = token(name, "name");
        identity = Objects.requireNonNull(identity, "identity");
        type = Objects.requireNonNull(type, "type");
        visibility = Objects.requireNonNull(visibility, "visibility");
        mutability = Objects.requireNonNull(mutability, "mutability");
        storageIdentity = Objects.requireNonNull(storageIdentity, "storageIdentity");
        if (type.hasQualifier(TypeQualifier.MUT)) {
            throw new IllegalArgumentException(
                    "binding mutability must be separate from the value type");
        }
        if (visibility == BindingVisibility.IMPORTED
                && mutability == BindingMutability.MUTABLE) {
            throw new IllegalArgumentException(
                    "imported bindings cannot expose mutable storage");
        }
        if (mutability == BindingMutability.MUTABLE && storageIdentity.isEmpty()) {
            throw new IllegalArgumentException("a mutable binding needs a storage identity");
        }
        if (mutability == BindingMutability.IMMUTABLE && storageIdentity.isPresent()) {
            throw new IllegalArgumentException(
                    "an immutable binding must not expose a storage identity");
        }
    }

    public String canonicalType() {
        return type.canonicalSpelling();
    }

    public boolean isMutable() {
        return mutability == BindingMutability.MUTABLE;
    }

    public boolean isPublic() {
        return visibility == BindingVisibility.PUBLIC;
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
        return value;
    }
}
