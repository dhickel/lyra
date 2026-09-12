package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLink;
import io.mindspice.lyra.compiler.semantic.TypedLiteralValue;
import io.mindspice.lyra.compiler.semantic.TypedSemanticInput;
import io.mindspice.lyra.compiler.types.NominalType;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact routed storage target derivable from an expression: the declaration
 * identity plus the projection route into its stored value.
 *
 * <p>This is the bounded, non-executable replacement for retaining a producer
 * expression tree in a session proof.  A call argument that is a routed binding
 * is the only reason a callee's transferred parameter write can update the
 * caller's storage, so the proof keeps exactly this fact and nothing else about
 * the original argument expression.</p>
 */
public record WriteTarget(DeclarationId declaration, ProjectionPath route) {
    public WriteTarget {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(route, "route");
    }

    /**
     * Derives the exact write target of an expression, or an empty result when
     * the expression cannot denote stored caller state.
     */
    public static Optional<WriteTarget> of(TypedExpression expression, TypedSemanticInput graph) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(graph, "graph");
        return switch (expression.kind()) {
            case REFERENCE -> expression.link()
                    .flatMap(TypedLink::declarationId)
                    .map(declaration -> new WriteTarget(declaration, ProjectionPath.root()));
            case INDEX_ACCESS -> {
                if (expression.children().size() != 2) {
                    yield Optional.empty();
                }
                yield of(expression.children().getFirst(), graph).map(parent ->
                        new WriteTarget(parent.declaration(), parent.route().compose(
                                ProjectionPath.of(indexRoute(expression.children().get(1))))));
            }
            case MEMBER_ACCESS -> nominalMember(expression, graph);
            case CONVERSION, NARROWING -> expression.children().isEmpty()
                    ? Optional.empty()
                    : of(expression.children().getFirst(), graph);
            default -> Optional.empty();
        };
    }

    private static Optional<WriteTarget> nominalMember(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.tupleIndex().isPresent()) {
            int index = expression.tupleIndex().orElseThrow().intValueExact();
            return of(expression.children().getFirst(), graph).map(parent ->
                    new WriteTarget(parent.declaration(), parent.route().compose(
                            ProjectionPath.tupleMember(index))));
        }
        if (expression.declarationId().isEmpty() || expression.children().isEmpty()
                || !(expression.children().getFirst().type().withoutQualifiers()
                instanceof NominalType owner)) {
            return Optional.empty();
        }
        DeclarationId member = expression.declarationId().orElseThrow();
        var nominal = graph.resolvedGraph().nominals().stream()
                .filter(value -> value.schema().type().equals(owner))
                .findFirst().orElse(null);
        if (nominal == null) {
            return Optional.empty();
        }
        int index = nominal.members().indexOf(member);
        if (index < 0) {
            // A method selection is a callable, never stored field data.
            return Optional.empty();
        }
        ProjectionStep step = new ProjectionStep.NominalMember(
                owner, index, nominal.schema().members().get(index).type());
        return of(expression.children().getFirst(), graph).map(parent ->
                new WriteTarget(parent.declaration(), parent.route().compose(
                        ProjectionPath.of(step))));
    }

    private static ProjectionStep indexRoute(TypedExpression index) {
        if (index.literal().orElse(null) instanceof TypedLiteralValue.IntegerValue integer) {
            BigInteger value = integer.exactValue().integerValue();
            if (value.signum() >= 0 && value.bitLength() <= 31) {
                return ProjectionStep.arrayElement(value.intValue());
            }
        }
        return ProjectionStep.unknownArrayElement();
    }
}
