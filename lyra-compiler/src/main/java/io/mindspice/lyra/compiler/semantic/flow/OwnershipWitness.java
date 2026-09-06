package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable source and scope evidence for an aggregate ownership fact.
 * {@code sourceSpan}/{@code originSite} identify the canonical allocation
 * origin; {@code useSpan} identifies the current occurrence. Binding-local
 * mutability is intentionally not a field here.
 */
public record OwnershipWitness(
        ModuleId ownerModule,
        DeclarationId originDeclaration,
        ScopeId scopeId,
        SourceSpan sourceSpan,
        Optional<ExportId> originExport,
        SourceSpan useSpan,
        Optional<FlowSiteId> originSite)
        implements Comparable<OwnershipWitness> {
    public OwnershipWitness {
        Objects.requireNonNull(ownerModule, "ownerModule");
        Objects.requireNonNull(originDeclaration, "originDeclaration");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(originExport, "originExport");
        Objects.requireNonNull(useSpan, "useSpan");
        Objects.requireNonNull(originSite, "originSite");
        originExport.ifPresent(export -> {
            if (!ownerModule.equals(export.moduleId())) {
                throw new IllegalArgumentException(
                        "ownership export must belong to its owning module");
            }
        });
    }

    /** Compatibility constructor whose origin and occurrence are the same site. */
    public OwnershipWitness(
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            ScopeId scopeId,
            SourceSpan sourceSpan,
            Optional<ExportId> originExport) {
        this(ownerModule, originDeclaration, scopeId, sourceSpan, originExport,
                sourceSpan, Optional.empty());
    }

    /** Compatibility constructor retaining a distinct use span without a site ID. */
    public OwnershipWitness(
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            ScopeId scopeId,
            SourceSpan sourceSpan,
            Optional<ExportId> originExport,
            SourceSpan useSpan) {
        this(ownerModule, originDeclaration, scopeId, sourceSpan, originExport,
                useSpan, Optional.empty());
    }

    public static OwnershipWitness local(
            ModuleId ownerModule,
            DeclarationId allocationSite,
            ScopeId scopeId,
            SourceSpan sourceSpan) {
        return new OwnershipWitness(
                ownerModule, allocationSite, scopeId, sourceSpan, Optional.empty(),
                sourceSpan, Optional.empty());
    }

    public static OwnershipWitness crossModule(
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            ScopeId scopeId,
            SourceSpan sourceSpan,
            ExportId originExport) {
        return new OwnershipWitness(
                ownerModule, originDeclaration, scopeId, sourceSpan,
                Optional.of(originExport), sourceSpan, Optional.empty());
    }

    public OwnershipWitness atUse(SourceSpan span) {
        return new OwnershipWitness(ownerModule, originDeclaration, scopeId,
                sourceSpan, originExport, Objects.requireNonNull(span, "span"),
                originSite);
    }

    public OwnershipWitness withOriginSite(FlowSiteId site) {
        return new OwnershipWitness(ownerModule, originDeclaration, scopeId,
                sourceSpan, originExport, useSpan,
                Optional.of(Objects.requireNonNull(site, "site")));
    }

    public ModuleId moduleId() {
        return ownerModule;
    }

    public Optional<ExportId> exportId() {
        return originExport;
    }

    public String canonicalKey() {
        String source = ModuleIdentity.canonicalModuleKey(
                ModuleId.fromSourceId(sourceSpan.sourceId()));
        return ModuleIdentity.canonicalModuleKey(ownerModule) + "/" + originDeclaration
                + "/" + scopeId + "/" + source + ":" + sourceSpan.startOffset()
                + ".." + sourceSpan.endOffset() + "/use=" + useSpan
                + "/site=" + originSite.map(Object::toString).orElse("-") + "/"
                + originExport.map(ExportId::toString).orElse("local");
    }

    @Override
    public int compareTo(OwnershipWitness other) {
        return canonicalKey().compareTo(Objects.requireNonNull(other, "other").canonicalKey());
    }
}
