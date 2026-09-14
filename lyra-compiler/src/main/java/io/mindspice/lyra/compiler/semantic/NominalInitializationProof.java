package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.NominalSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Producer-issued definite-initialization proof bound to the exact typed initializer tree. */
public final class NominalInitializationProof implements ImmutablePhaseArtifact {
    private final ResolvedNominal nominal;
    private final List<TypedExpression> children;

    private NominalInitializationProof(ResolvedNominal nominal, List<TypedExpression> children) {
        this.nominal = nominal;
        this.children = List.copyOf(children);
    }

    public ResolvedNominal nominal() { return nominal; }

    public void requireMatches(DeclarationId declaration, List<TypedExpression> expressions) {
        if (!nominal.declaration().equals(declaration) || expressions.size() != children.size()) {
            throw new IllegalArgumentException("nominal initialization proof belongs to another declaration/tree");
        }
        for (int index = 0; index < children.size(); index++) {
            if (children.get(index) != expressions.get(index)) {
                throw new IllegalArgumentException("nominal initializer changed after initialization certification");
            }
        }
    }

    static PhaseResult<NominalInitializationProof> analyze(ResolvedSemanticGraph graph,
            ResolvedNominal nominal, List<TypedExpression> children) {
        Analyzer analyzer = new Analyzer(graph, nominal);
        State state = new State();
        if (nominal.schema().kind() == NominalSchema.Kind.STRUCT) {
            for (int index = 0; index < nominal.members().size(); index++) {
                if (!nominal.schema().members().get(index).hasInitializer()) {
                    state.initialize(nominal.members().get(index), Value.NONE);
                }
            }
        }
        int child = 0;
        for (int index = 0; index < nominal.members().size(); index++) {
            if (!nominal.schema().members().get(index).hasInitializer()) continue;
            TypedExpression initializer = children.get(child++);
            Value value = analyzer.evaluate(initializer, state);
            analyzer.initialize(nominal.members().get(index), value, initializer.span(), state);
        }
        if (nominal.constructor().isPresent()) {
            TypedExpression constructor = children.get(child++);
            if (constructor.kind() != TypedExpressionKind.LAMBDA
                    || !constructor.lambdaId().equals(nominal.constructor())) {
                throw new IllegalArgumentException("nominal constructor child does not match its resolved lambda");
            }
            Value result = analyzer.evaluate(constructor.children().getFirst(), state);
            analyzer.escape(result, constructor.span(), state);
        }
        if (child != children.size()) throw new IllegalArgumentException("unexpected nominal initializer children");
        if (analyzer.diagnostic == null && !analyzer.ready(state)) {
            List<String> missing = nominal.members().stream().filter(id -> !state.definite.contains(id))
                    .map(id -> graph.declaration(id).orElseThrow().name()).toList();
            analyzer.fail(graph.declaration(nominal.declaration()).orElseThrow().span(),
                    "constructor does not initialize every field on every completing path: " + missing);
        }
        return analyzer.diagnostic == null ? PhaseResult.success(new NominalInitializationProof(nominal, children))
                : PhaseResult.failure(analyzer.diagnostic);
    }

    private record Value(boolean self, boolean containsSelf, Set<DeclarationId> cells) {
        private static final Value NONE = new Value(false, false, Set.of());
        private static final Value SELF = new Value(true, true, Set.of());
        private Value { cells = Set.copyOf(cells); }
        Value merge(Value other) {
            Set<DeclarationId> joined = new HashSet<>(cells);
            joined.addAll(other.cells);
            return new Value(self && other.self, containsSelf || other.containsSelf, joined);
        }
        Value aggregate() { return new Value(false, containsSelf, cells); }
        boolean exposes(State state, Set<DeclarationId> visited) {
            if (containsSelf) return true;
            for (DeclarationId cell : cells) {
                if (visited.add(cell) && state.locals.getOrDefault(cell, NONE).exposes(state, visited)) return true;
            }
            return false;
        }
    }

    private static final class State {
        final Set<DeclarationId> definite = new HashSet<>();
        final Set<DeclarationId> possible = new HashSet<>();
        final Map<DeclarationId, Value> fields = new HashMap<>();
        final Map<DeclarationId, Value> locals = new HashMap<>();
        void initialize(DeclarationId field, Value value) {
            definite.add(field);
            possible.add(field);
            fields.put(field, value);
        }
        State copy() {
            State result = new State();
            result.definite.addAll(definite);
            result.possible.addAll(possible);
            result.fields.putAll(fields);
            result.locals.putAll(locals);
            return result;
        }
        void join(State left, State right) {
            definite.clear(); definite.addAll(left.definite); definite.retainAll(right.definite);
            possible.clear(); possible.addAll(left.possible); possible.addAll(right.possible);
            fields.clear(); fields.putAll(left.fields); right.fields.forEach((id, value) -> fields.merge(id, value, Value::merge));
            locals.clear(); locals.putAll(left.locals); right.locals.forEach((id, value) -> locals.merge(id, value, Value::merge));
        }
    }

    private static final class Analyzer {
        final ResolvedSemanticGraph graph;
        final ResolvedNominal nominal;
        final SourceSpan declarationSpan;
        Diagnostic diagnostic;
        Analyzer(ResolvedSemanticGraph graph, ResolvedNominal nominal) {
            this.graph = Objects.requireNonNull(graph);
            this.nominal = Objects.requireNonNull(nominal);
            declarationSpan = graph.declaration(nominal.declaration()).orElseThrow().span();
        }
        boolean ready(State state) { return state.definite.containsAll(nominal.members()); }
        void fail(SourceSpan span, String message) {
            if (diagnostic == null) diagnostic = Diagnostic.error(CompilerDiagnosticCodes.TYPE_INVALID_BINDING, span, message);
        }
        void escape(Value value, SourceSpan span, State state) {
            if (!ready(state) && value.exposes(state, new HashSet<>())) fail(span, "incomplete self cannot escape or be invoked");
        }
        void initialize(DeclarationId field, Value value, SourceSpan span, State state) {
            if (state.possible.contains(field) && !graph.declaration(field).orElseThrow().isMutable()) {
                fail(span, "immutable field is initialized more than once: " + graph.declaration(field).orElseThrow().name());
            }
            state.initialize(field, value);
        }
        Value evaluate(TypedExpression expression, State state) {
            if (diagnostic != null) return Value.NONE;
            var children = expression.children();
            switch (expression.kind()) {
                case REFERENCE -> {
                    DeclarationId id = expression.link().orElseThrow().declarationId().orElseThrow();
                    return id.equals(nominal.self()) ? Value.SELF : state.locals.getOrDefault(id, Value.NONE);
                }
                case LAMBDA -> {
                    Value captures = Value.NONE;
                    for (var id : expression.captureIds()) {
                        var capture = graph.capture(id).orElseThrow();
                        DeclarationId declaration = capture.declarationId();
                        Value value = declaration.equals(nominal.self()) ? Value.SELF
                                : graph.declaration(declaration).orElseThrow().isMutable()
                                ? new Value(false, false, Set.of(declaration))
                                : state.locals.getOrDefault(declaration, Value.NONE);
                        captures = captures.merge(value);
                    }
                    return captures.aggregate();
                }
                case DECLARATION -> {
                    Value value = evaluate(children.getFirst(), state);
                    state.locals.put(expression.declarationId().orElseThrow(), value);
                    return Value.NONE;
                }
                case MEMBER_ACCESS -> {
                    Value receiver = evaluate(children.getFirst(), state);
                    if (receiver.exposes(state, new HashSet<>()) && expression.declarationId().filter(nominal.members()::contains).isPresent()) {
                        DeclarationId field = expression.declarationId().orElseThrow();
                        if (!state.definite.contains(field)) fail(expression.span(), "field is read before initialization: "
                                + graph.declaration(field).orElseThrow().name());
                        Value value = state.fields.getOrDefault(field, Value.NONE);
                        return expression.type().withoutQualifiers() instanceof FunctionType ? value.merge(Value.SELF).aggregate() : value;
                    }
                    return receiver.aggregate();
                }
                case REBINDING -> {
                    TypedExpression target = children.getFirst();
                    Value receiver = target.kind() == TypedExpressionKind.MEMBER_ACCESS
                            || target.kind() == TypedExpressionKind.INDEX_ACCESS
                            ? evaluate(target.children().getFirst(), state) : Value.NONE;
                    if (target.kind() == TypedExpressionKind.INDEX_ACCESS) evaluate(target.children().get(1), state);
                    Value value = evaluate(children.get(1), state);
                    if (receiver.self && target.declarationId().filter(nominal.members()::contains).isPresent()) {
                        initialize(target.declarationId().orElseThrow(), value, target.span(), state);
                    } else if (target.kind() == TypedExpressionKind.REFERENCE && local(expression.declarationId().orElseThrow())) {
                        state.locals.put(expression.declarationId().orElseThrow(), value);
                    } else {
                        if (receiver.exposes(state, new HashSet<>()) && target.declarationId().filter(nominal.members()::contains).isPresent()) {
                            DeclarationId field = target.declarationId().orElseThrow();
                            if (state.possible.contains(field) && !graph.declaration(field).orElseThrow().isMutable()) {
                                fail(target.span(), "immutable field may be initialized more than once");
                            }
                            state.possible.add(field);
                        }
                        escape(value, expression.span(), state);
                    }
                    return Value.NONE;
                }
                case CONDITIONAL -> {
                    evaluate(children.getFirst(), state);
                    State left = state.copy(), right = state.copy();
                    Value thenValue = evaluate(children.get(1), left);
                    Value elseValue = children.size() == 3 ? evaluate(children.get(2), right) : Value.NONE;
                    state.join(left, right);
                    return thenValue.merge(elseValue);
                }
                case COALESCE -> {
                    Value value = evaluate(children.getFirst(), state);
                    State left = state.copy(), right = state.copy();
                    Value fallback = evaluate(children.get(1), right);
                    state.join(left, right);
                    return value.merge(fallback);
                }
                case CALLABLE_CALL, DIRECT_CALL, NAMESPACE_DIRECT_CALL, CONSTRUCTION, ITER, WHILE -> {
                    for (TypedExpression child : children) {
                        Value value = evaluate(child, state);
                        escape(value, child.span(), state);
                    }
                    return Value.NONE;
                }
                case MATCH, COND -> {
                    var match = expression.match().orElseThrow();
                    match.subjectChild().ifPresent(index -> evaluate(children.get(index), state));
                    List<State> completed = new ArrayList<>();
                    Value result = Value.NONE;
                    State continuation = state.copy();
                    for (var arm : match.arms()) {
                        if (arm.patternChild().isPresent()) evaluate(children.get(arm.patternChild().getAsInt()), continuation);
                        State matched = continuation.copy();
                        arm.guardChild().ifPresent(index -> evaluate(children.get(index), matched));
                        State next = new State(); next.join(continuation, matched); continuation = next;
                        result = result.merge(evaluate(children.get(arm.resultChild()), matched));
                        completed.add(matched);
                    }
                    if (!completed.isEmpty()) {
                        State merged = completed.getFirst();
                        for (int index = 1; index < completed.size(); index++) {
                            State next = new State(); next.join(merged, completed.get(index)); merged = next;
                        }
                        state.join(merged, merged);
                    }
                    return result;
                }
                case BLOCK -> {
                    Value value = Value.NONE;
                    for (TypedExpression child : children) value = evaluate(child, state);
                    return value;
                }
                case SHORT_CIRCUIT -> {
                    evaluate(children.getFirst(), state);
                    for (int index = 1; index < children.size(); index++) {
                        State skipped = state.copy(), evaluated = state.copy();
                        evaluate(children.get(index), evaluated);
                        state.join(skipped, evaluated);
                    }
                    return Value.NONE;
                }
                default -> {
                    Value value = null;
                    for (TypedExpression child : children) {
                        Value childValue = evaluate(child, state);
                        value = value == null ? childValue : value.merge(childValue);
                    }
                    if (value == null) return Value.NONE;
                    return expression.kind() == TypedExpressionKind.CONVERSION || expression.kind() == TypedExpressionKind.NARROWING
                            ? value
                            : value.aggregate();
                }
            }
        }
        private boolean local(DeclarationId id) {
            SourceSpan span = graph.declaration(id).orElseThrow().span();
            return span.sourceId().equals(declarationSpan.sourceId()) && span.startOffset() >= declarationSpan.startOffset()
                    && span.endOffset() <= declarationSpan.endOffset();
        }
    }
}
