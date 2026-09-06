package io.mindspice.lyra.compiler.identity;

import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/**
 * Stable external module identity used by semantic and later backend phases.
 * A source span contributes only its stable source/module ID; line and offset
 * data are deliberately excluded.
 */
public final class ModuleIdentity implements Comparable<ModuleIdentity> {
    private final ModuleId moduleId;
    private final String canonicalKey;

    public ModuleIdentity(ModuleId moduleId) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.canonicalKey = canonicalModuleKey(moduleId);
    }

    public static ModuleIdentity of(ModuleId moduleId) {
        return new ModuleIdentity(moduleId);
    }

    public static ModuleIdentity from(ModuleId moduleId) {
        return of(moduleId);
    }

    public static ModuleIdentity from(SourceSpan span) {
        Objects.requireNonNull(span, "span");
        return of(ModuleId.fromSourceId(span.sourceId()));
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public String value() {
        return moduleId.value();
    }

    /** A kind-tagged stable spelling suitable for deterministic sort/hash inputs. */
    public String canonicalKey() {
        return canonicalKey;
    }

    public String canonical() {
        return canonicalKey;
    }

    /** Logical import spelling is retained as a separate, non-identity key. */
    public static String logicalKey(LogicalModuleId logicalModule) {
        return "logical:" + Objects.requireNonNull(logicalModule, "logicalModule").value();
    }

    public static String canonicalModuleKey(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return (moduleId.isUri() ? "uri:" : "path:") + moduleId.value();
    }

    @Override
    public int compareTo(ModuleIdentity other) {
        return canonicalKey.compareTo(Objects.requireNonNull(other, "other").canonicalKey);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ModuleIdentity identity && moduleId.equals(identity.moduleId);
    }

    @Override
    public int hashCode() {
        return moduleId.hashCode();
    }

    @Override
    public String toString() {
        return canonicalKey;
    }
}
