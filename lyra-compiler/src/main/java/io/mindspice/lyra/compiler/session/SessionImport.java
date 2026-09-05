package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;

import java.util.Objects;

/** A previously committed module namespace alias. */
public record SessionImport(
        String name,
        LogicalModuleId logicalModule,
        ModuleId moduleId,
        String revision) {
    public SessionImport {
        name = token(name, "name");
        logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
        moduleId = Objects.requireNonNull(moduleId, "moduleId");
        revision = Objects.requireNonNull(revision, "revision");
        if (!ModuleRevision.isRevision(revision)) {
            throw new IllegalArgumentException("module revision must be a SHA-256 hexadecimal value");
        }
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }
}
