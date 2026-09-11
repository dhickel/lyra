package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.NominalType;

import java.util.Objects;

/** Abstract allocation-site identity, distinct from the nominal declaration identity. */
public record NominalObjectIdentity(ModuleId ownerModule, FlowSiteId allocationSite, NominalType type)
        implements Comparable<NominalObjectIdentity> {
    public NominalObjectIdentity {
        Objects.requireNonNull(ownerModule, "ownerModule");
        Objects.requireNonNull(allocationSite, "allocationSite");
        Objects.requireNonNull(type, "type");
    }
    public String canonicalKey() {
        return "object/" + ModuleIdentity.canonicalModuleKey(ownerModule) + "/" + allocationSite + "/" + type.canonical();
    }
    @Override public int compareTo(NominalObjectIdentity other) { return canonicalKey().compareTo(other.canonicalKey()); }
}
