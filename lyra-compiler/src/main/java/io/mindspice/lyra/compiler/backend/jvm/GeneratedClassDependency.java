package io.mindspice.lyra.compiler.backend.jvm;

import java.util.Objects;

/**
 * Immutable class-plan edge.  A linkage edge is explicit but does not impose
 * an emission predecessor, which is how legal recursive functions avoid a
 * false class-order cycle.
 */
record GeneratedClassDependency(
        String targetBinaryName,
        GeneratedDependencyKind kind,
        boolean orderingRequired,
        String reason) implements Comparable<GeneratedClassDependency> {
    public GeneratedClassDependency {
        JvmNames.requireBinaryName(targetBinaryName, "dependency target");
        Objects.requireNonNull(kind, "kind");
        boolean linkageOnly = kind == GeneratedDependencyKind.MODULE_IMPORT_LINKAGE
                || kind == GeneratedDependencyKind.NOMINAL_TYPE_LINKAGE
                || kind == GeneratedDependencyKind.RECURSIVE_FUNCTION_LINKAGE;
        if (linkageOnly ? orderingRequired : !orderingRequired) {
            throw new IllegalArgumentException(
                    "only module-import, recursive-function and nominal-type linkage edges may be ordering-free");
        }
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("dependency reason must not be blank");
        }
    }

    public String target() {
        return targetBinaryName;
    }

    public boolean requiresOrdering() {
        return orderingRequired;
    }

    public boolean isLinkageOnly() {
        return !orderingRequired;
    }

    @Override
    public int compareTo(GeneratedClassDependency other) {
        Objects.requireNonNull(other, "other");
        int target = targetBinaryName.compareTo(other.targetBinaryName);
        if (target != 0) {
            return target;
        }
        int kindOrder = kind.compareTo(other.kind);
        if (kindOrder != 0) {
            return kindOrder;
        }
        int required = Boolean.compare(orderingRequired, other.orderingRequired);
        return required != 0 ? required : reason.compareTo(other.reason);
    }
}
