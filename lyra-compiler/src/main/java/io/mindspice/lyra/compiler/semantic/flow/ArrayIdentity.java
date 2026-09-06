package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.ArrayType;

import java.util.Objects;
import java.util.Optional;

/**
 * Canonical abstract identity of one identity-bearing array allocation or
 * cross-module origin.  The identity is compile-local and semantic only; it
 * is not a runtime object identity or serialized artifact identifier.
 */
public sealed interface ArrayIdentity extends Comparable<ArrayIdentity>
        permits ArrayIdentity.LocalAllocation, ArrayIdentity.CrossModuleOrigin, ArrayIdentity.SessionOrigin {
    ArrayType arrayType();

    ModuleId ownerModule();

    DeclarationId originDeclaration();

    Optional<ExportId> originExport();

    boolean isImported();

    default String canonicalKey() {
        String owner = ModuleIdentity.canonicalModuleKey(ownerModule());
        if (this instanceof LocalAllocation local) {
            return "local/" + owner + "/" + local.allocationSite()
                    + "/" + local.arrayType().canonicalSpelling();
        }
        if (this instanceof SessionOrigin session) {
            return "session/" + owner + "/" + session.originDeclaration() + "/" + session.sourceRoute()
                    + "/" + session.arrayType().canonicalSpelling();
        }
        CrossModuleOrigin imported = (CrossModuleOrigin) this;
        return "cross-module/" + owner + "/" + imported.exportId()
                + "/" + imported.originDeclaration() + "/"
                + imported.arrayType().canonicalSpelling();
    }

    static LocalAllocation localAllocation(
            ModuleId ownerModule, DeclarationId allocationSite, ArrayType arrayType) {
        return new LocalAllocation(ownerModule, allocationSite, arrayType);
    }

    static CrossModuleOrigin crossModuleOrigin(
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            ExportId exportId,
            ArrayType arrayType) {
        return new CrossModuleOrigin(ownerModule, originDeclaration, exportId, arrayType);
    }

    @Override
    default int compareTo(ArrayIdentity other) {
        return canonicalKey().compareTo(Objects.requireNonNull(other, "other").canonicalKey());
    }

    /** Identity allocated at a local source allocation site. */
    record LocalAllocation(
            ModuleId ownerModule,
            DeclarationId allocationSite,
            ArrayType arrayType) implements ArrayIdentity {
        public LocalAllocation {
            Objects.requireNonNull(ownerModule, "ownerModule");
            Objects.requireNonNull(allocationSite, "allocationSite");
            Objects.requireNonNull(arrayType, "arrayType");
        }

        @Override
        public DeclarationId originDeclaration() {
            return allocationSite;
        }

        @Override
        public Optional<ExportId> originExport() {
            return Optional.empty();
        }

        @Override
        public boolean isImported() {
            return false;
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /**
     * Unknown initialized session-owned array at an external contract route.
     * Distinct origins of the same array type may alias; this is not a fresh
     * allocation or proof of runtime identity. Authority is certified separately.
     */
    record SessionOrigin(ModuleId ownerModule, DeclarationId originDeclaration,
                         ProjectionPath sourceRoute, ArrayType arrayType) implements ArrayIdentity {
        public SessionOrigin {
            Objects.requireNonNull(ownerModule, "ownerModule");
            Objects.requireNonNull(originDeclaration, "originDeclaration");
            Objects.requireNonNull(sourceRoute, "sourceRoute");
            Objects.requireNonNull(arrayType, "arrayType");
        }

        @Override public Optional<ExportId> originExport() { return Optional.empty(); }
        @Override public boolean isImported() { return false; }
        @Override public String toString() { return canonicalKey(); }
    }

    /** Identity imported from a canonical cross-module export origin. */
    record CrossModuleOrigin(
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            ExportId exportId,
            ArrayType arrayType) implements ArrayIdentity {
        public CrossModuleOrigin {
            Objects.requireNonNull(ownerModule, "ownerModule");
            Objects.requireNonNull(originDeclaration, "originDeclaration");
            Objects.requireNonNull(exportId, "exportId");
            Objects.requireNonNull(arrayType, "arrayType");
            if (!ownerModule.equals(exportId.moduleId())) {
                throw new IllegalArgumentException(
                        "cross-module export must belong to its owning module");
            }
        }

        @Override
        public Optional<ExportId> originExport() {
            return Optional.of(exportId);
        }

        @Override
        public boolean isImported() {
            return true;
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }
}
