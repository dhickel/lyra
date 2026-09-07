package io.mindspice.lyra.runtime;

import java.util.Objects;

/**
 * Source-independent compatibility context for an attachable root.
 *
 * <p>It identifies the exact root graph and the reproducible analysis inputs;
 * it deliberately contains no AST, typed IR, resolver instance, initializer
 * value, or live Java object.</p>
 */
public record AttachmentContext(ModuleId rootModule, ModuleRevision rootRevision,
                               String graphRevision, String sourceInventoryRevision,
                               String optionsRevision, String javaPackage) {
    public AttachmentContext {
        rootModule = Objects.requireNonNull(rootModule, "rootModule");
        rootRevision = Objects.requireNonNull(rootRevision, "rootRevision");
        graphRevision = revision(graphRevision, "graphRevision");
        sourceInventoryRevision = revision(sourceInventoryRevision, "sourceInventoryRevision");
        optionsRevision = revision(optionsRevision, "optionsRevision");
        javaPackage = text(javaPackage, "javaPackage");
    }

    private static String revision(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!ModuleRevision.isRevision(value)) {
            throw new IllegalArgumentException(field + " must be a SHA-256 revision");
        }
        return value;
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-blank and printable");
        }
        return value;
    }
}
