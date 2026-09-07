package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Canonical source import edge retained for attachable context reconstruction. */
public record ArtifactImport(ModuleId fromModule, String logicalTarget, ModuleId targetModule,
                             int startOffset, int endOffset) implements Comparable<ArtifactImport> {
    public ArtifactImport {
        fromModule = Objects.requireNonNull(fromModule, "fromModule");
        logicalTarget = text(logicalTarget, "logicalTarget");
        targetModule = Objects.requireNonNull(targetModule, "targetModule");
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("invalid import source span");
        }
    }

    @Override
    public int compareTo(ArtifactImport other) {
        Objects.requireNonNull(other, "other");
        int result = fromModule.compareTo(other.fromModule);
        if (result != 0) return result;
        result = logicalTarget.compareTo(other.logicalTarget);
        if (result != 0) return result;
        result = targetModule.compareTo(other.targetModule);
        if (result != 0) return result;
        result = Integer.compare(startOffset, other.startOffset);
        return result != 0 ? result : Integer.compare(endOffset, other.endOffset);
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-blank and printable");
        }
        return value;
    }
}
