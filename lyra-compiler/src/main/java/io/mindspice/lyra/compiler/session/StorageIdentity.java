package io.mindspice.lyra.compiler.session;

import java.util.Objects;

/** Stable identity of mutable storage shared by session bindings and closures. */
public record StorageIdentity(long ordinal) implements Comparable<StorageIdentity> {
    public StorageIdentity {
        if (ordinal < 0) {
            throw new IllegalArgumentException("storage ordinal must not be negative");
        }
    }

    public static StorageIdentity forDeclaration(io.mindspice.lyra.compiler.identity.DeclarationId id) {
        Objects.requireNonNull(id, "id");
        return new StorageIdentity(id.ordinal());
    }

    public long value() {
        return ordinal;
    }

    @Override
    public int compareTo(StorageIdentity other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "storage#" + ordinal;
    }
}
