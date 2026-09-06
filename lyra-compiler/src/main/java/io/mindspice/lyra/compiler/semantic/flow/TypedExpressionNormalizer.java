package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLiteralValue;
import io.mindspice.lyra.compiler.semantic.TypedLambda;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure adapter from the typed-expression tree to the summary-owned view. */
public final class TypedExpressionNormalizer {
    private TypedExpressionNormalizer() {
    }

    /** Normalizes one complete typed expression without executing it. */
    public static NormalizedExpression normalize(TypedExpression expression) {
        Objects.requireNonNull(expression, "expression");
        List<NormalizedExpression> children = new ArrayList<>();
        for (TypedExpression child : expression.children()) {
            children.add(normalize(child));
        }
        return new NormalizedExpression(
                expression.kind(),
                expression.span(),
                expression.type(),
                children,
                expression.link(),
                expression.declarationId(),
                expression.lambdaId(),
                expression.scopeId(),
                projectionStep(expression),
                callKind(expression.kind()),
                expression.captureIds(),
                expression.literal(),
                expression.conversion(),
                expression.operator(),
                expression.memberName(),
                expression.tupleIndex(),
                expression.signature(),
                expression.predicateBinding());
    }

    public static NormalizedExpression adapt(TypedExpression expression) {
        return normalize(expression);
    }

    /** Normalizes the body of one typed lambda without executing its body. */
    public static NormalizedExpression normalize(TypedLambda lambda) {
        Objects.requireNonNull(lambda, "lambda");
        return normalize(lambda.body());
    }

    public static NormalizedExpression normalizeBody(TypedLambda lambda) {
        return normalize(lambda);
    }

    private static Optional<NormalizedExpression.CallKind> callKind(
            TypedExpressionKind kind) {
        return switch (kind) {
            case DIRECT_CALL -> Optional.of(NormalizedExpression.CallKind.DIRECT);
            case NAMESPACE_DIRECT_CALL -> Optional.of(NormalizedExpression.CallKind.NAMESPACE);
            case CALLABLE_CALL -> Optional.of(NormalizedExpression.CallKind.CALLABLE);
            default -> Optional.empty();
        };
    }

    private static Optional<ProjectionStep> projectionStep(TypedExpression expression) {
        if (expression.kind() == TypedExpressionKind.MEMBER_ACCESS
                && expression.tupleIndex().isPresent()) {
            BigInteger index = expression.tupleIndex().orElseThrow();
            if (index.bitLength() > 31) {
                return Optional.empty();
            }
            return Optional.of(ProjectionStep.tupleMember(index.intValue()));
        }
        if (expression.kind() != TypedExpressionKind.INDEX_ACCESS
                || expression.children().size() != 2) {
            return Optional.empty();
        }
        if (expression.children().getFirst().type().withoutQualifiers() == PrimitiveType.STRING) {
            return Optional.empty();
        }
        if (!(expression.children().getFirst().type().withoutQualifiers() instanceof ArrayType)) {
            return Optional.empty();
        }
        TypedExpression index = expression.children().get(1);
        if (index.literal().orElse(null) instanceof TypedLiteralValue.IntegerValue integer) {
            BigInteger value = integer.exactValue().integerValue();
            if (value.signum() >= 0 && value.bitLength() <= 31) {
                return Optional.of(ProjectionStep.arrayElement(value.intValue()));
            }
        }
        return Optional.of(ProjectionStep.unknownArrayElement());
    }
}
