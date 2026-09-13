package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable-{@code self} alias provenance recomputed from a sealed resolved
 * graph.
 *
 * <p>The resolver enforces the same rule live during resolution with draft
 * state and closes with this exact engine on the frozen graph; topology and
 * IR validation construct their own instances from the same sealed artifacts
 * so every layer re-checks the invariant without importing resolver-local
 * flow.</p>
 *
 * <p>{@code self} provenance is a finite declaration-keyed may-alias lattice
 * over four value-position facts: receiver aliases, storage reached through
 * a receiver, nominal member storage origins, and known callable identities.
 * Local bindings, rebinds, aggregate construction/projection, captures,
 * branches and calls join these facts monotonically. A lambda value carries
 * its identity while its body result is tracked separately, so calling an
 * aliased or aggregate-held closure materializes the body-result provenance
 * without treating the closure object itself as the receiver it may return.
 * Calls join targets, arguments and resolved body results; constructions
 * still return a fresh value while propagating arguments into constructor
 * parameters.</p>
 *
 * <p>Aggregate positions are conservatively collapsed to their reachable
 * union. Member reads add the exact member declaration as a back-reference;
 * element/member writes weakly update every possible originating member and
 * the local aggregate root. Copying a member aggregate through a local,
 * tuple, array, call or another member therefore cannot erase the storage it
 * may mutate. Each concrete assignment target also retains its joined
 * provenance, so a routed write such as `other:.values[0]` is checked at that
 * position rather than only against the fresh root declaration `other`. The
 * member map is declaration-keyed and deliberately
 * instance-insensitive: an assignment through one instance may taint reads
 * through every instance, which is a sound loss of precision rather than an
 * escape.</p>
 *
 * <p>The iteration either converges or fails closed: when the bound is
 * reached with provenance still changing, {@link Analysis#undecidedSite()}
 * names the forwarding site that could not be decided and the resolver turns
 * it into a structured diagnostic instead of publishing a partial result.</p>
 */
public final class SelfAliasProvenance {
    /**
     * One point in the finite may-alias lattice. {@code selfs} identifies
     * receiver values, {@code selfBackedStorage} identifies storage reached
     * through them, {@code memberOrigins} identifies member storage that a
     * collapsed aggregate position may alias, and {@code callableValues}
     * preserves lambda identity until a call materializes its body result.
     *
     * <p>Aggregate positions are intentionally collapsed to their reachable
     * union. This loses sibling precision but preserves the property needed by
     * mutation validation: projecting, passing, returning, or nesting an
     * aggregate can never erase the member storage it may write back to.</p>
     */
    private record ValueProvenance(
            Set<DeclarationId> selfs,
            Set<DeclarationId> selfBackedStorage,
            Set<DeclarationId> memberOrigins,
            Set<LambdaId> callableValues) {
        private static final ValueProvenance EMPTY =
                new ValueProvenance(Set.of(), Set.of(), Set.of(), Set.of());

        private ValueProvenance {
            selfs = Set.copyOf(Objects.requireNonNull(selfs, "selfs"));
            selfBackedStorage = Set.copyOf(Objects.requireNonNull(
                    selfBackedStorage, "selfBackedStorage"));
            memberOrigins = Set.copyOf(Objects.requireNonNull(
                    memberOrigins, "memberOrigins"));
            callableValues = Set.copyOf(Objects.requireNonNull(
                    callableValues, "callableValues"));
        }

        private static ValueProvenance self(DeclarationId declaration) {
            return new ValueProvenance(
                    Set.of(declaration), Set.of(), Set.of(), Set.of());
        }

        private static ValueProvenance callable(LambdaId lambda) {
            return new ValueProvenance(
                    Set.of(), Set.of(), Set.of(), Set.of(lambda));
        }

        private ValueProvenance withMemberOrigin(DeclarationId declaration) {
            LinkedHashSet<DeclarationId> origins = new LinkedHashSet<>(memberOrigins);
            origins.add(declaration);
            return new ValueProvenance(
                    selfs, selfBackedStorage, origins, callableValues);
        }

        private ValueProvenance backedBy(Set<DeclarationId> receivers) {
            if (receivers.isEmpty()) {
                return this;
            }
            LinkedHashSet<DeclarationId> backing =
                    new LinkedHashSet<>(selfBackedStorage);
            backing.addAll(receivers);
            return new ValueProvenance(
                    selfs, backing, memberOrigins, callableValues);
        }

        private ValueProvenance join(ValueProvenance other) {
            if (this.equals(other) || other.equals(EMPTY)) {
                return this;
            }
            if (this.equals(EMPTY)) {
                return other;
            }
            LinkedHashSet<DeclarationId> joinedSelfs = new LinkedHashSet<>(selfs);
            joinedSelfs.addAll(other.selfs);
            LinkedHashSet<DeclarationId> joinedBacking =
                    new LinkedHashSet<>(selfBackedStorage);
            joinedBacking.addAll(other.selfBackedStorage);
            LinkedHashSet<DeclarationId> joinedOrigins =
                    new LinkedHashSet<>(memberOrigins);
            joinedOrigins.addAll(other.memberOrigins);
            LinkedHashSet<LambdaId> joinedCallables =
                    new LinkedHashSet<>(callableValues);
            joinedCallables.addAll(other.callableValues);
            return new ValueProvenance(
                    joinedSelfs, joinedBacking, joinedOrigins, joinedCallables);
        }
    }

    private final Map<DeclarationId, ValueProvenance> declarationValues;
    private final Map<SourceSpan, ValueProvenance> mutationPositions;

    private SelfAliasProvenance(
            Map<DeclarationId, ValueProvenance> declarationValues,
            Map<SourceSpan, ValueProvenance> mutationPositions) {
        this.declarationValues = Collections.unmodifiableMap(
                new LinkedHashMap<>(declarationValues));
        this.mutationPositions = Collections.unmodifiableMap(
                new LinkedHashMap<>(mutationPositions));
    }

    /**
     * One bounded fixed-point analysis over a resolved graph.  The
     * provenance map is the converged map when {@code undecidedSite} is
     * empty; otherwise the map is the last partial state and the site names
     * where provenance was still moving when the bound was reached.
     */
    public record Analysis(
            SelfAliasProvenance provenance, Optional<SourceSpan> undecidedSite) {
        public Analysis {
            Objects.requireNonNull(provenance, "provenance");
            Objects.requireNonNull(undecidedSite, "undecidedSite");
        }

        /** True when the fixed point converged within the bound. */
        public boolean converged() {
            return undecidedSite.isEmpty();
        }
    }

    /** Runs the bounded fixed-point analysis for one resolved graph. */
    public static Analysis analyze(ResolvedSemanticGraph resolved) {
        return new Builder(Objects.requireNonNull(resolved, "resolved")).build();
    }

    /**
     * Recomputes the may-alias provenance for one resolved graph.
     * Internal validation layers call this on already-published graphs, which
     * the resolver only publishes after a converged sweep; non-convergence
     * here is therefore an implementation invariant.
     */
    public static SelfAliasProvenance of(ResolvedSemanticGraph resolved) {
        Analysis analysis = analyze(resolved);
        if (analysis.undecidedSite().isPresent()) {
            throw new IllegalStateException(
                    "immutable-self alias provenance did not converge within the bounded analysis");
        }
        return analysis.provenance();
    }

    /** The {@code SELF} declarations one declaration may currently alias. */
    public Set<DeclarationId> selfs(DeclarationId declaration) {
        return declarationValues.getOrDefault(
                Objects.requireNonNull(declaration, "declaration"),
                ValueProvenance.EMPTY).selfs();
    }

    /**
     * The immutable-{@code self} rule: an array-element mutation whose root
     * may alias {@code self} is legal only when every aliased {@code self}
     * belongs to a nominal whose exact constructor lambda contains the
     * mutation.  A nominal's own {@code self} declaration and a contextual
     * replacement {@code self} carrying the nominal's exact schema contract
     * both belong to the nominal.  Member-field and rebinding mutations are
     * owned by the ordinary mutability checks.
     */
    public boolean permitsMutation(
            ResolvedSemanticGraph resolved, ResolvedMutation mutation) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(mutation, "mutation");
        if (mutation.kind() != MutationKind.ARRAY_ELEMENT) {
            return true;
        }
        ResolvedDeclaration rootDeclaration = resolved.declaration(
                mutation.rootDeclaration()).orElse(null);
        ValueProvenance rootValue = declarationValues.getOrDefault(
                        mutation.rootDeclaration(), ValueProvenance.EMPTY)
                .join(mutationPositions.getOrDefault(
                        mutation.span(), ValueProvenance.EMPTY));
        Set<DeclarationId> selfs = new LinkedHashSet<>(
                rootValue.selfBackedStorage());
        Set<DeclarationId> directSelfs = new LinkedHashSet<>(rootValue.selfs());
        if (rootDeclaration != null && rootDeclaration.effectiveContract().isPresent()) {
            var rootType = rootDeclaration.effectiveContract().orElseThrow()
                    .valueType().withoutQualifiers();
            directSelfs.removeIf(self -> resolved.nominals().stream().noneMatch(nominal -> {
                ResolvedDeclaration selfDeclaration =
                        resolved.declaration(self).orElse(null);
                return nominal.schema().type().equals(rootType)
                        && (nominal.self().equals(self)
                        || selfDeclaration != null
                        && selfDeclaration.effectiveContract()
                                .map(BindingContract::valueType)
                                .map(type -> type.withoutQualifiers().equals(rootType))
                                .orElse(false));
            }));
        }
        selfs.addAll(directSelfs);
        if (selfs.isEmpty()) {
            return true;
        }
        Optional<LambdaId> lambda = mutation.rootReference()
                .flatMap(resolved::reference)
                .flatMap(ResolvedReference::fromLambda);
        if (lambda.isEmpty()) {
            return false;
        }
        for (DeclarationId self : selfs) {
            ResolvedDeclaration selfDeclaration =
                    resolved.declaration(self).orElse(null);
            boolean owned = resolved.nominals().stream().anyMatch(nominal ->
                    nominal.constructor().equals(lambda) && (
                            nominal.self().equals(self)
                            || selfDeclaration != null
                            && selfDeclaration.effectiveContract()
                                    .map(BindingContract::valueType)
                                    .map(nominal.schema().type()::equals)
                                    .orElse(false)));
            if (!owned) {
                return false;
            }
        }
        return true;
    }

    private static final class Builder {
        /**
         * Small explicit bound on the propagation fixed point.  Each pass
         * carries provenance one hop further through backward call chains,
         * captured rebinds and closure bodies; the iteration stops as soon
         * as a pass adds no new provenance, and the analysis fails closed
         * when the bound is exhausted with facts still changing.
         */
        private static final int MAX_PROPAGATION_PASSES = 8;

        private final ResolvedSemanticGraph resolved;
        private final Map<SourceSpan, DeclarationId> declarationBySpan = new HashMap<>();
        private final Map<SourceSpan, LambdaId> lambdaBySpan = new HashMap<>();
        private final Map<DeclarationId, List<ResolvedLambda>> lambdasByOwner = new HashMap<>();
        private final Set<DeclarationId> selfIds = new LinkedHashSet<>();
        private final Map<DeclarationId, ResolvedNominal> nominalByDeclaration = new HashMap<>();
        /** Declaration-keyed value-position lattice. */
        private final Map<DeclarationId, ValueProvenance> values = new LinkedHashMap<>();
        /** Provenance at each concrete aggregate mutation target position. */
        private final Map<SourceSpan, ValueProvenance> mutationPositions =
                new LinkedHashMap<>();
        /**
         * Body-result provenance per analyzed lambda.  Union-merged and
         * carried across passes like the declaration map, so call results
         * through chains of self-returning closures join the fixed point.
         */
        private final Map<LambdaId, ValueProvenance> lambdaResults = new LinkedHashMap<>();
        /** First call site that added new provenance in the current pass. */
        private SourceSpan changedCallSpanThisPass;
        /** First lambda whose body-result provenance grew in the current pass. */
        private SourceSpan changedLambdaSpanThisPass;
        /** First member-slot assignment that grew taint in the current pass. */
        private SourceSpan changedMemberSpanThisPass;
        /**
         * Values that may have been stored in each nominal member declaration.
         * This is both self taint and a conservative member-back-reference
         * graph: assigning an aggregate derived from member A into member B
         * makes every later read of B retain A as a possible storage origin.
         */
        private final Map<DeclarationId, ValueProvenance> memberValues =
                new LinkedHashMap<>();
        /** Member-access spans to their resolved member declarations. */
        private final Map<SourceSpan, DeclarationId> memberDeclarationBySpan =
                new HashMap<>();

        private Builder(ResolvedSemanticGraph resolved) {
            this.resolved = resolved;
            for (SyntaxLink link : resolved.syntaxLinks()) {
                if (link.declarationId().isPresent()) {
                    declarationBySpan.put(link.span(), link.declarationId().orElseThrow());
                }
                if (link.kind() == SyntaxLinkKind.LAMBDA && link.lambdaId().isPresent()) {
                    lambdaBySpan.put(link.span(), link.lambdaId().orElseThrow());
                }
                if (link.kind() == SyntaxLinkKind.ACCESS
                        && link.declarationId().isPresent()) {
                    memberDeclarationBySpan.put(
                            link.span(), link.declarationId().orElseThrow());
                }
            }
            for (ResolvedNominal nominal : resolved.nominals()) {
                selfIds.add(nominal.self());
                nominalByDeclaration.put(nominal.declaration(), nominal);
            }
            for (ResolvedDeclaration declaration : resolved.declarations()) {
                if (declaration.kind() == DeclarationKind.SELF
                        && !selfIds.contains(declaration.id())) {
                    selfIds.add(declaration.id());
                }
            }
            for (ResolvedLambda lambda : resolved.lambdas()) {
                lambda.ownerDeclaration().ifPresent(owner ->
                        lambdasByOwner.computeIfAbsent(owner, ignored -> new ArrayList<>())
                                .add(lambda));
            }
        }

        private Analysis build() {
            Map<DeclarationId, ValueProvenance> seededValues = new LinkedHashMap<>();
            Map<LambdaId, ValueProvenance> seededLambdaResults = new LinkedHashMap<>();
            Map<DeclarationId, ValueProvenance> seededMemberValues =
                    new LinkedHashMap<>();
            Map<SourceSpan, ValueProvenance> seededMutationPositions =
                    new LinkedHashMap<>();
            Optional<SourceSpan> undecided = Optional.empty();
            for (int pass = 0; pass < MAX_PROPAGATION_PASSES; pass++) {
                values.clear();
                values.putAll(seededValues);
                lambdaResults.clear();
                lambdaResults.putAll(seededLambdaResults);
                memberValues.clear();
                memberValues.putAll(seededMemberValues);
                mutationPositions.clear();
                mutationPositions.putAll(seededMutationPositions);
                changedCallSpanThisPass = null;
                changedLambdaSpanThisPass = null;
                changedMemberSpanThisPass = null;
                for (ModuleGraph.Node node : resolved.moduleGraph().modules()) {
                    if (resolved.isRetained(node.moduleId())) {
                        continue;
                    }
                    for (SyntaxNode.Form form : node.program().forms()) {
                        walkForm(form);
                    }
                }
                Map<DeclarationId, ValueProvenance> nextValues = snapshot();
                Map<LambdaId, ValueProvenance> nextLambdaResults =
                        new LinkedHashMap<>(lambdaResults);
                Map<DeclarationId, ValueProvenance> nextMemberValues =
                        new LinkedHashMap<>(memberValues);
                Map<SourceSpan, ValueProvenance> nextMutationPositions =
                        new LinkedHashMap<>(mutationPositions);
                if (nextValues.equals(seededValues)
                        && nextLambdaResults.equals(seededLambdaResults)
                        && nextMemberValues.equals(seededMemberValues)
                        && nextMutationPositions.equals(seededMutationPositions)) {
                    break;
                }
                seededValues = nextValues;
                seededLambdaResults = nextLambdaResults;
                seededMemberValues = nextMemberValues;
                seededMutationPositions = nextMutationPositions;
                if (pass == MAX_PROPAGATION_PASSES - 1) {
                    SourceSpan site = changedCallSpanThisPass != null
                            ? changedCallSpanThisPass
                            : changedMemberSpanThisPass != null
                            ? changedMemberSpanThisPass
                            : changedLambdaSpanThisPass;
                    if (site == null) {
                        site = resolved.mutations().stream()
                                .filter(mutation -> mutation.kind() == MutationKind.ARRAY_ELEMENT)
                                .map(ResolvedMutation::span)
                                .findFirst().orElse(null);
                    }
                    undecided = Optional.ofNullable(site);
                }
            }
            return new Analysis(new SelfAliasProvenance(
                    values, mutationPositions), undecided);
        }

        private void walkForm(SyntaxNode.Form form) {
            if (form instanceof SyntaxNode.LetBinding let) {
                DeclarationId declaration = declarationBySpan.get(let.name().span());
                ValueProvenance provenance = walkExpression(let.initializer());
                if (declaration != null) {
                    record(declaration, provenance);
                }
                return;
            }
            if (form instanceof SyntaxNode.NominalDeclaration nominal) {
                for (SyntaxNode.MemberDeclaration member : nominal.members()) {
                    member.initializer().ifPresent(initializer -> {
                        ValueProvenance provenance = walkExpression(initializer);
                        DeclarationId declaration = declarationBySpan.get(
                                member.name().span());
                        if (declaration != null) {
                            recordMemberValue(declaration, provenance, member.span());
                        }
                    });
                }
                nominal.constructor().ifPresent(constructor ->
                        walkExpression(constructor.initializer()));
                return;
            }
            if (form instanceof SyntaxNode.Expression expression) {
                walkExpression(expression);
                return;
            }
            throw new IllegalArgumentException(
                    "unrecognized source form in self-alias provenance");
        }

        private ValueProvenance walkExpression(SyntaxNode.Expression expression) {
            if (expression instanceof SyntaxNode.Identifier identifier) {
                DeclarationId target = declarationBySpan.get(identifier.span());
                if (target == null) {
                    return ValueProvenance.EMPTY;
                }
                if (selfIds.contains(target)) {
                    return ValueProvenance.self(target);
                }
                return values.getOrDefault(target, ValueProvenance.EMPTY);
            }
            if (expression instanceof SyntaxNode.MemberAccess access) {
                ValueProvenance provenance = walkExpression(access.receiver());
                DeclarationId member = memberDeclarationBySpan.get(access.span());
                if (member != null) {
                    provenance = provenance.join(memberValues.getOrDefault(
                                    member, ValueProvenance.EMPTY))
                            .backedBy(provenance.selfs())
                            .withMemberOrigin(member);
                }
                return provenance;
            }
            if (expression instanceof SyntaxNode.IndexAccess access) {
                ValueProvenance provenance = walkExpression(access.receiver());
                ValueProvenance indexProvenance = walkExpression(access.index());
                // A nominal target makes this form a single-argument
                // construction (`Box[value]`); propagate its argument into
                // the constructor parameters like any other call. Ordinary
                // projections retain the aggregate's collapsed reachable
                // provenance, including every member storage origin.
                if (propagateCall(callLink(access.span()), List.of(indexProvenance))) {
                    noteChangedCall(access.span());
                }
                return provenance;
            }
            if (expression instanceof SyntaxNode.Block block) {
                ValueProvenance result = ValueProvenance.EMPTY;
                for (int index = 0; index < block.forms().size(); index++) {
                    SyntaxNode.Form form = block.forms().get(index);
                    if (index == block.forms().size() - 1
                            && form instanceof SyntaxNode.Expression finalExpression) {
                        result = walkExpression(finalExpression);
                    } else {
                        walkForm(form);
                    }
                }
                return result;
            }
            if (expression instanceof SyntaxNode.Conditional conditional) {
                ValueProvenance predicateProvenance = walkExpression(
                        conditional.predicate());
                conditional.binding().ifPresent(binding -> {
                    DeclarationId bindingDeclaration = declarationBySpan.get(
                            binding.name().span());
                    if (bindingDeclaration != null) {
                        record(bindingDeclaration, predicateProvenance);
                    }
                });
                Map<DeclarationId, ValueProvenance> before = snapshot();
                ValueProvenance thenProvenance = walkExpression(
                        conditional.thenExpression());
                Map<DeclarationId, ValueProvenance> thenState = snapshot();
                if (conditional.elseExpression().isPresent()) {
                    restore(before);
                    ValueProvenance elseProvenance = walkExpression(
                            conditional.elseExpression().orElseThrow());
                    join(thenState, snapshot());
                    restore(thenState);
                    return thenProvenance.join(elseProvenance);
                }
                // A then-only conditional is an effect position: the branch
                // may not execute, so join the branch state with the state
                // before the predicate result.
                join(thenState, before);
                restore(thenState);
                return thenProvenance;
            }
            if (expression instanceof SyntaxNode.Coalesce coalesce) {
                ValueProvenance valueProvenance = walkExpression(coalesce.value());
                Map<DeclarationId, ValueProvenance> valueState = snapshot();
                ValueProvenance fallbackProvenance = walkExpression(coalesce.fallback());
                Map<DeclarationId, ValueProvenance> fallbackState = snapshot();
                Map<DeclarationId, ValueProvenance> joined = new LinkedHashMap<>();
                join(joined, valueState);
                join(joined, fallbackState);
                restore(joined);
                return valueProvenance.join(fallbackProvenance);
            }
            if (expression instanceof SyntaxNode.Match match) {
                match.subject().ifPresent(this::walkExpression);
                Map<DeclarationId, ValueProvenance> continuation = snapshot();
                ValueProvenance result = ValueProvenance.EMPTY;
                List<Map<DeclarationId, ValueProvenance>> armStates = new ArrayList<>();
                for (SyntaxNode.MatchArm arm : match.arms()) {
                    restore(continuation);
                    arm.pattern().ifPresent(this::walkExpression);
                    arm.guard().ifPresent(this::walkExpression);
                    result = result.join(walkExpression(arm.result()));
                    armStates.add(snapshot());
                }
                Map<DeclarationId, ValueProvenance> joined = new LinkedHashMap<>();
                for (Map<DeclarationId, ValueProvenance> armState : armStates) {
                    join(joined, armState);
                }
                restore(joined);
                return result;
            }
            if (expression instanceof SyntaxNode.ArrayLiteral array) {
                ValueProvenance result = ValueProvenance.EMPTY;
                for (SyntaxNode.Expression element : array.elements()) {
                    result = result.join(walkExpression(element));
                }
                return result;
            }
            if (expression instanceof SyntaxNode.TupleLiteral tuple) {
                ValueProvenance result = ValueProvenance.EMPTY;
                for (SyntaxNode.Expression element : tuple.elements()) {
                    result = result.join(walkExpression(element));
                }
                return result;
            }
            if (expression instanceof SyntaxNode.TypeConversion conversion) {
                return walkExpression(conversion.value());
            }
            if (expression instanceof SyntaxNode.Lambda lambda) {
                ValueProvenance result = walkExpression(lambda.body());
                recordLambdaResult(lambda.span(), result);
                LambdaId lambdaId = lambdaBySpan.get(lambda.span());
                return lambdaId == null
                        ? ValueProvenance.EMPTY
                        : ValueProvenance.callable(lambdaId);
            }
            if (expression instanceof SyntaxNode.CompactLambda lambda) {
                ValueProvenance result = walkExpression(lambda.body());
                recordLambdaResult(lambda.span(), result);
                LambdaId lambdaId = lambdaBySpan.get(lambda.span());
                return lambdaId == null
                        ? ValueProvenance.EMPTY
                        : ValueProvenance.callable(lambdaId);
            }
            if (expression instanceof SyntaxNode.CallableCall call) {
                ValueProvenance target = walkExpression(call.target());
                List<ValueProvenance> arguments = new ArrayList<>();
                for (SyntaxNode.Expression argument : call.arguments()) {
                    arguments.add(walkExpression(argument));
                }
                return callResult(call.span(), target, arguments);
            }
            if (expression instanceof SyntaxNode.DirectCall call) {
                ValueProvenance receiver = call.receiver()
                        .map(this::walkExpression)
                        .orElse(ValueProvenance.EMPTY);
                List<ValueProvenance> arguments = new ArrayList<>();
                for (SyntaxNode.Expression argument : call.argumentExpressions()) {
                    arguments.add(walkExpression(argument));
                }
                return callResult(call.span(),
                        receiver.join(callTarget(call.span())), arguments);
            }
            if (expression instanceof SyntaxNode.NamespaceDirectCall call) {
                List<ValueProvenance> arguments = new ArrayList<>();
                for (SyntaxNode.Expression argument : call.argumentExpressions()) {
                    arguments.add(walkExpression(argument));
                }
                return callResult(call.span(), ValueProvenance.EMPTY, arguments);
            }
            if (expression instanceof SyntaxNode.BracketApplication application) {
                ValueProvenance target = walkExpression(application.target());
                List<ValueProvenance> arguments = new ArrayList<>();
                for (SyntaxNode.Expression argument : application.arguments().expressions()) {
                    arguments.add(walkExpression(argument));
                }
                return callResult(application.span(), target, arguments);
            }
            if (expression instanceof SyntaxNode.Reassignment assignment) {
                applyAssignment(assignment.target(), assignment.value(), assignment.span());
                return ValueProvenance.EMPTY;
            }
            if (expression instanceof SyntaxNode.PrefixAssignment assignment) {
                applyAssignment(assignment.target(), assignment.value(), assignment.span());
                return ValueProvenance.EMPTY;
            }
            if (expression instanceof SyntaxNode.Range range) {
                walkExpression(range.start());
                walkExpression(range.end());
                walkExpression(range.step());
                return ValueProvenance.EMPTY;
            }
            if (expression instanceof SyntaxNode.OperatorSExpression operator) {
                operator.operands().forEach(this::walkExpression);
                return ValueProvenance.EMPTY;
            }
            if (expression instanceof SyntaxNode.OperatorBracket operator) {
                operator.operands().forEach(this::walkExpression);
                return ValueProvenance.EMPTY;
            }
            if (expression instanceof SyntaxNode.Literal
                    || expression instanceof SyntaxNode.NamespaceMemberAccess) {
                return ValueProvenance.EMPTY;
            }
            throw new IllegalArgumentException(
                    "unrecognized source expression in self-alias provenance: "
                            + expression.getClass().getSimpleName());
        }

        /**
         * Applies one weak update to the declaration/value-position lattice.
         * Identifier rebinds and writes into local aggregates monotonically
         * join the replacement into the root declaration. Member and nested
         * aggregate writes also join it into every member storage origin the
         * target may reference. This is the conservative back-reference rule
         * that prevents an alias copy or projection from laundering storage
         * identity before a write.
         */
        private void applyAssignment(
                SyntaxNode.Expression target,
                SyntaxNode.Expression replacement,
                SourceSpan assignmentSpan) {
            ValueProvenance targetProvenance = walkExpression(target);
            ValueProvenance replacementProvenance = walkExpression(replacement);
            if (target instanceof SyntaxNode.Identifier identifier) {
                DeclarationId declaration = declarationBySpan.get(identifier.span());
                if (declaration != null) {
                    record(declaration, replacementProvenance);
                }
                return;
            }

            mutationPositions.merge(
                    target.span(), targetProvenance, ValueProvenance::join);
            LinkedHashSet<DeclarationId> memberTargets =
                    new LinkedHashSet<>(targetProvenance.memberOrigins());
            if (target instanceof SyntaxNode.MemberAccess memberTarget) {
                DeclarationId member = memberDeclarationBySpan.get(memberTarget.span());
                if (member != null) {
                    memberTargets.add(member);
                }
            }
            for (DeclarationId member : memberTargets) {
                recordMemberValue(member, replacementProvenance, assignmentSpan);
            }

            // An element write changes the reachable contents of its root
            // aggregate. Collapse the written value into that declaration so
            // a later copy/projection from the local retains the may-alias fact.
            if (target instanceof SyntaxNode.IndexAccess) {
                DeclarationId root = rootDeclaration(target);
                if (root != null && !selfIds.contains(root)) {
                    record(root, replacementProvenance);
                }
            }
        }

        private DeclarationId rootDeclaration(SyntaxNode.Expression expression) {
            if (expression instanceof SyntaxNode.Identifier identifier) {
                return declarationBySpan.get(identifier.span());
            }
            if (expression instanceof SyntaxNode.MemberAccess access) {
                return rootDeclaration(access.receiver());
            }
            if (expression instanceof SyntaxNode.IndexAccess access) {
                return rootDeclaration(access.receiver());
            }
            return null;
        }

        /**
         * One call/construction site: propagate argument provenance into the
         * resolved callee parameters and compute the result provenance.
         * Constructions mint a fresh object and cannot alias {@code self};
         * every other call conservatively carries the union of its target and
         * argument provenance plus the body-result provenance of every callee
         * lambda in the analyzed graph (identity-returning and
         * captured-{@code self}-returning callables included).
         */
        private ValueProvenance callResult(
                SourceSpan span,
                ValueProvenance target,
                List<ValueProvenance> arguments) {
            SyntaxLink link = callLink(span);
            if (propagateCall(link, arguments)) {
                noteChangedCall(span);
            }
            if (isConstruction(link)) {
                // Construction returns a fresh nominal instance. Argument
                // provenance still reaches constructor parameters above and
                // member stores preserve any aggregate back-references.
                return ValueProvenance.EMPTY;
            }
            ValueProvenance result = target;
            LinkedHashSet<DeclarationId> opaqueReceiverSources =
                    new LinkedHashSet<>(target.selfs());
            for (ValueProvenance argument : arguments) {
                result = result.join(argument);
                opaqueReceiverSources.addAll(argument.selfs());
            }
            // Even when no current source body is available (for example a
            // retained callable), a target or argument that aliases self may
            // be projected to self-backed storage by the call. Known bodies
            // can add precision, but may not erase this conservative route.
            result = result.backedBy(opaqueReceiverSources);
            for (LambdaId callable : target.callableValues()) {
                result = result.join(lambdaResults.getOrDefault(
                        callable, ValueProvenance.EMPTY));
            }
            if (link != null) {
                for (ResolvedLambda callee : calleeLambdas(link)) {
                    result = result.join(lambdaResults.getOrDefault(
                            callee.id(), ValueProvenance.EMPTY));
                }
                // A declaration-resolved member call may select a rebound
                // callable. Include both its stored value provenance and its
                // storage origin rather than trusting the original lambda.
                if (link.declarationId().isPresent()) {
                    DeclarationId declaration = link.declarationId().orElseThrow();
                    if (resolved.declaration(declaration)
                            .map(candidate -> candidate.kind() == DeclarationKind.MEMBER)
                            .orElse(false)) {
                        result = result.join(memberValues.getOrDefault(
                                declaration, ValueProvenance.EMPTY))
                                .withMemberOrigin(declaration);
                    }
                }
            }
            // Rebound member storage may have introduced callable identities
            // after the initial target expansion. Materialize their current
            // body-result facts for this call result as well.
            for (LambdaId callable : result.callableValues()) {
                result = result.join(lambdaResults.getOrDefault(
                        callable, ValueProvenance.EMPTY));
            }
            return result;
        }

        private ValueProvenance callTarget(SourceSpan span) {
            SyntaxLink link = callLink(span);
            if (link != null && link.declarationId().isPresent()) {
                DeclarationId declaration = link.declarationId().orElseThrow();
                return values.getOrDefault(declaration, ValueProvenance.EMPTY);
            }
            return ValueProvenance.EMPTY;
        }

        private SyntaxLink callLink(SourceSpan span) {
            return resolved.syntaxLinks().stream()
                    .filter(candidate -> candidate.kind() == SyntaxLinkKind.CALL
                            && candidate.span().equals(span))
                    .findFirst().orElse(null);
        }

        private boolean isConstruction(SyntaxLink link) {
            return link != null && link.declarationId().isPresent()
                    && nominalByDeclaration.containsKey(link.declarationId().orElseThrow());
        }

        /**
         * Propagates argument provenance into the resolved callee parameters
         * and reports whether any parameter gained new provenance.
         */
        private boolean propagateCall(
                SyntaxLink link, List<ValueProvenance> arguments) {
            if (link == null) {
                return false;
            }
            List<ResolvedLambda> callees = calleeLambdas(link);
            boolean changed = false;
            for (ResolvedLambda callee : callees) {
                for (int index = 0;
                        index < Math.min(arguments.size(), callee.parameters().size());
                        index++) {
                    ValueProvenance argument = arguments.get(index);
                    if (argument.equals(ValueProvenance.EMPTY)) {
                        continue;
                    }
                    DeclarationId parameter = callee.parameters().get(index);
                    ValueProvenance before = values.getOrDefault(
                            parameter, ValueProvenance.EMPTY);
                    ValueProvenance merged = before.join(argument);
                    if (!merged.equals(before)) {
                        values.put(parameter, merged);
                        changed = true;
                    }
                }
            }
            return changed;
        }

        private void recordMemberValue(
                DeclarationId member,
                ValueProvenance provenance,
                SourceSpan span) {
            if (provenance.equals(ValueProvenance.EMPTY)) {
                return;
            }
            ValueProvenance previous = memberValues.getOrDefault(
                    member, ValueProvenance.EMPTY);
            ValueProvenance merged = previous.join(provenance);
            if (merged.equals(previous)) {
                return;
            }
            memberValues.put(member, merged);
            if (changedMemberSpanThisPass == null) {
                changedMemberSpanThisPass = span;
            }
        }

        private void noteChangedCall(SourceSpan span) {
            if (changedCallSpanThisPass == null) {
                changedCallSpanThisPass = span;
            }
        }

        private List<ResolvedLambda> calleeLambdas(SyntaxLink link) {
            if (link.declarationId().isPresent()) {
                DeclarationId declaration = link.declarationId().orElseThrow();
                List<ResolvedLambda> result = new ArrayList<>(
                        lambdasByOwner.getOrDefault(declaration, List.of()));
                ResolvedNominal nominal = nominalByDeclaration.get(declaration);
                if (nominal != null) {
                    nominal.constructor().flatMap(resolved::lambda)
                            .ifPresent(result::add);
                }
                return result;
            }
            if (link.referenceId().isPresent()) {
                ResolvedReference reference = resolved.reference(
                        link.referenceId().orElseThrow()).orElse(null);
                if (reference == null) {
                    return List.of();
                }
                if (reference.targetDeclaration().isPresent()) {
                    return lambdasByOwner.getOrDefault(
                            reference.targetDeclaration().orElseThrow(), List.of());
                }
                if (reference.targetModule().isPresent()
                        && reference.targetExport().isPresent()) {
                    ModuleId module = reference.targetModule().orElseThrow();
                    var export = reference.targetExport().orElseThrow();
                    ResolvedExport resolvedExport = resolved.exports().stream()
                            .filter(candidate -> candidate.moduleId().equals(module)
                                    && candidate.exportId().equals(Optional.of(export)))
                            .findFirst().orElse(null);
                    if (resolvedExport != null) {
                        return lambdasByOwner.getOrDefault(
                                resolvedExport.originDeclaration(), List.of());
                    }
                }
            }
            return List.of();
        }

        /**
         * Records a lambda's body-result provenance, union-merged with any
         * previously recorded facts so the per-pass recomputation only ever
         * grows the fixed point.
         */
        private void recordLambdaResult(
                SourceSpan span, ValueProvenance result) {
            LambdaId lambdaId = lambdaBySpan.get(span);
            if (lambdaId == null) {
                return;
            }
            ValueProvenance previous = lambdaResults.getOrDefault(
                    lambdaId, ValueProvenance.EMPTY);
            ValueProvenance merged = previous.join(result);
            if (merged.equals(previous)) {
                return;
            }
            lambdaResults.put(lambdaId, merged);
            if (changedLambdaSpanThisPass == null) {
                changedLambdaSpanThisPass = span;
            }
        }

        /** Monotone weak update: a declaration retains every reachable value. */
        private void record(
                DeclarationId declaration, ValueProvenance provenance) {
            if (provenance.equals(ValueProvenance.EMPTY)) {
                return;
            }
            values.merge(declaration, provenance, ValueProvenance::join);
        }

        private Map<DeclarationId, ValueProvenance> snapshot() {
            return new LinkedHashMap<>(values);
        }

        private void restore(Map<DeclarationId, ValueProvenance> snapshot) {
            values.clear();
            values.putAll(snapshot);
        }

        private void join(
                Map<DeclarationId, ValueProvenance> left,
                Map<DeclarationId, ValueProvenance> right) {
            for (Map.Entry<DeclarationId, ValueProvenance> entry : right.entrySet()) {
                left.merge(entry.getKey(), entry.getValue(), ValueProvenance::join);
            }
        }
    }
}
