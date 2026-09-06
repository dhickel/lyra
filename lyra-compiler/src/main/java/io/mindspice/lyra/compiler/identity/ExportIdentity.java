package io.mindspice.lyra.compiler.identity;

import io.mindspice.lyra.compiler.types.BindingMutability;

import java.util.Objects;

/** Export identity plus its separate declaration-binding mutation permission. */
public record ExportIdentity(ExportId id, BindingMutability bindingMutability) {
    public ExportIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bindingMutability, "bindingMutability");
    }

    public static ExportIdentity immutable(ExportId id) {
        return new ExportIdentity(id, BindingMutability.IMMUTABLE);
    }

    public static ExportIdentity mutable(ExportId id) {
        return new ExportIdentity(id, BindingMutability.MUTABLE);
    }

    public ExportId exportId() {
        return id;
    }

    public boolean isMutable() {
        return bindingMutability.isMutable();
    }

    public String canonicalSignature() {
        return id.signature().canonicalSpelling();
    }
}
