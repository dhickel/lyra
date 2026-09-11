package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conservative producer-backed fact conversion for values that cross the
 * registered-root boundary into a trusted session view.
 *
 * <p>Reads of a public root binding never assume the current aggregate value
 * still equals its initializer allocation: main or an evaluation may have
 * replaced the binding or changed owned content between safe points.  This
 * helper converts producer-issued local allocation facts into imported
 * cross-module identities (immutable bindings) or attachable-boundary
 * identities (public {@code @mut} bindings), mirroring the canonical flow
 * conversion used by attachable compilation itself.</p>
 */
public final class BoundaryFacts {
    private BoundaryFacts() {
    }

    /**
     * Converts local aggregate facts in {@code values} into conservative
     * imported facts for the supplied root-scope export contract.
     *
     * @param typed the sealed producer graph whose flow facts produced the values
     * @param values the producer-issued value alternatives
     * @param ownerModule module that owns the exported binding slot
     * @param originDeclaration declaration that owns the underlying value
     * @param export stable export identity when one exists
     * @param useSpan span of the boundary read
     * @param attachable true for public mutable bindings whose contents may be
     *        replaced at any dispatch safe point
     */
    public static ValueAlternatives conservativeExternal(
            TypedSemanticGraph typed,
            ValueAlternatives values,
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            Optional<ExportId> export,
            SourceSpan useSpan,
            boolean attachable) {
        if (values.isEmpty()) {
            return values;
        }
        ArrayList<ValueAlternative> alternatives = new ArrayList<>();
        for (ValueAlternative value : values.alternatives()) {
            ArrayList<AggregateIdentityFact> facts = new ArrayList<>();
            for (AggregateIdentityFact fact : value.aggregateIdentities()) {
                if (fact.isImported()) {
                    facts.add(fact);
                    continue;
                }
                ExportId exportId;
                ModuleId identityOwner;
                DeclarationId identityOrigin;
                if (attachable) {
                    exportId = export
                            .filter(id -> id.moduleId().equals(ownerModule))
                            .orElseGet(() -> exportFor(typed, ownerModule,
                                    originDeclaration, fact.identity().arrayType()));
                    identityOwner = ownerModule;
                    identityOrigin = originDeclaration;
                } else {
                    exportId = export
                            .filter(id -> id.moduleId().equals(fact.identity().ownerModule()))
                            .orElseGet(() -> exportFor(typed, fact.identity().ownerModule(),
                                    fact.identity().originDeclaration(),
                                    fact.identity().arrayType()));
                    identityOwner = fact.identity().ownerModule();
                    identityOrigin = fact.identity().originDeclaration();
                }
                ArrayIdentity identity = attachable
                        ? ArrayIdentity.attachableBoundary(
                        identityOwner, identityOrigin, exportId, fact.identity().arrayType())
                        : ArrayIdentity.crossModuleOrigin(
                        identityOwner, identityOrigin, exportId, fact.identity().arrayType());
                OwnershipWitness witness = OwnershipWitness.crossModule(
                                identityOwner, identityOrigin,
                                fact.ownershipWitness().scopeId(),
                                fact.ownershipWitness().sourceSpan(), exportId)
                        .atUse(useSpan);
                if (fact.ownershipWitness().originSite().isPresent()) {
                    witness = witness.withOriginSite(
                            fact.ownershipWitness().originSite().orElseThrow());
                } else {
                    // Sealed IR provenance requires an exact origin site even
                    // when the producer's initializer witness omitted one.
                    witness = witness.withOriginSite(
                            siteOf(typed, identityOrigin, useSpan));
                }
                facts.add(new AggregateIdentityFact(identity, fact.route(), witness));
            }
            addPotentialBoundaryFacts(typed, value.type(), ProjectionPath.root(),
                    ownerModule, originDeclaration, export, useSpan, facts, attachable);
            alternatives.add(ValueAlternative.of(
                    value.type(), facts, value.callableFlows(), value.nilProvenance(), value.objects()));
        }
        return new ValueAlternatives(alternatives);
    }

    private static void addPotentialBoundaryFacts(
            TypedSemanticGraph typed,
            LyraType type,
            ProjectionPath route,
            ModuleId ownerModule,
            DeclarationId originDeclaration,
            Optional<ExportId> export,
            SourceSpan useSpan,
            List<AggregateIdentityFact> facts,
            boolean attachable) {
        LyraType base = type.withoutQualifiers();
        if (base instanceof ArrayType array) {
            boolean represented = facts.stream().anyMatch(fact ->
                    fact.route().depth() == route.depth() && fact.route().overlaps(route));
            if (!represented) {
                ExportId exportId = export
                        .filter(id -> id.moduleId().equals(ownerModule))
                        .orElseGet(() -> exportFor(typed, ownerModule, originDeclaration, array));
                OwnershipWitness witness = OwnershipWitness.crossModule(
                                ownerModule, originDeclaration,
                                scopeOf(typed, originDeclaration, useSpan),
                                spanOf(typed, originDeclaration, useSpan), exportId)
                        .atUse(useSpan)
                        .withOriginSite(siteOf(typed, originDeclaration, useSpan));
                facts.add(new AggregateIdentityFact(
                        attachable
                                ? ArrayIdentity.attachableBoundary(
                                ownerModule, originDeclaration, exportId, array)
                                : ArrayIdentity.crossModuleOrigin(
                                ownerModule, originDeclaration, exportId, array),
                        route, witness));
            }
            addPotentialBoundaryFacts(typed, array.elementType(),
                    route.append(ProjectionStep.unknownArrayElement()),
                    ownerModule, originDeclaration, export, useSpan, facts, attachable);
            return;
        }
        if (base instanceof TupleType tuple) {
            for (int index = 0; index < tuple.arity(); index++) {
                addPotentialBoundaryFacts(typed, tuple.memberType(index),
                        route.append(ProjectionStep.tupleMember(index)),
                        ownerModule, originDeclaration, export, useSpan, facts, attachable);
            }
        }
    }

    private static ExportId exportFor(TypedSemanticGraph typed, ModuleId module,
                                      DeclarationId declaration, ArrayType type) {
        return typed.resolvedGraph().declaration(declaration)
                .flatMap(value -> typed.resolvedGraph().export(module, value.name()))
                .flatMap(ResolvedExport::exportId)
                .orElseGet(() -> ExportId.of(module, "_flow_" + declaration.ordinal(),
                        LyraSignature.of(List.of(), type)));
    }

    private static io.mindspice.lyra.compiler.identity.ScopeId scopeOf(
            TypedSemanticGraph typed, DeclarationId declaration, SourceSpan useSpan) {
        return typed.resolvedGraph().declaration(declaration)
                .map(io.mindspice.lyra.compiler.semantic.ResolvedDeclaration::scopeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "boundary conversion has no canonical owner scope: " + declaration
                                + " at " + useSpan));
    }

    private static SourceSpan spanOf(TypedSemanticGraph typed, DeclarationId declaration,
                                     SourceSpan useSpan) {
        TypedDeclaration typedDeclaration = typed.declaration(declaration).orElse(null);
        if (typedDeclaration != null) {
            return typedDeclaration.initializer().map(
                    io.mindspice.lyra.compiler.semantic.TypedExpression::span)
                    .orElse(typedDeclaration.span());
        }
        return typed.resolvedGraph().declaration(declaration)
                .map(io.mindspice.lyra.compiler.semantic.ResolvedDeclaration::span)
                .orElseThrow(() -> new IllegalArgumentException(
                        "boundary conversion has no canonical origin span: " + declaration
                                + " at " + useSpan));
    }

    private static io.mindspice.lyra.compiler.identity.FlowSiteId siteOf(
            TypedSemanticGraph typed, DeclarationId declaration, SourceSpan useSpan) {
        TypedDeclaration typedDeclaration = typed.declaration(declaration).orElse(null);
        if (typedDeclaration != null && typedDeclaration.initializer().isPresent()) {
            return typed.flowSiteId(typedDeclaration.initializer().orElseThrow());
        }
        var resolved = typed.resolvedGraph().declaration(declaration).orElse(null);
        if (resolved != null && resolved.originDeclaration().isPresent()
                && !resolved.originDeclaration().orElseThrow().equals(declaration)) {
            return siteOf(typed, resolved.originDeclaration().orElseThrow(), useSpan);
        }
        throw new IllegalArgumentException(
                "boundary conversion has no canonical origin site: " + declaration
                        + " at " + useSpan);
    }
}
