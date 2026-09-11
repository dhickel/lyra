package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An immutable semantic failure site.  It describes a later runtime check;
 * semantic analysis never executes the operation.
 */
public record TypedFailureSite(
        SourceSpan span,
        FailureSiteKind kind,
        String failureCode,
        TypedExpressionKind expressionKind) {
    public TypedFailureSite {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(expressionKind, "expressionKind");
        if (failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
    }

    public String code() {
        return failureCode;
    }

    public FailureSiteKind failureKind() {
        return kind;
    }

    public static List<TypedFailureSite> fromExpressions(List<TypedExpression> expressions) {
        Objects.requireNonNull(expressions, "expressions");
        return expressions.stream()
                .map(TypedFailureSite::fromExpression)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((TypedFailureSite value) -> value.span().sourceId().value())
                        .thenComparingInt(value -> value.span().startOffset())
                        .thenComparingInt(value -> value.span().endOffset())
                        .thenComparing(value -> value.kind().name())
                        .thenComparing(value -> value.expressionKind().name()))
                .toList();
    }

    /** Returns the canonical runtime-check record for one typed source expression. */
    public static java.util.Optional<TypedFailureSite> forExpression(TypedExpression expression) {
        return java.util.Optional.ofNullable(fromExpression(expression));
    }

    private static TypedFailureSite fromExpression(TypedExpression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.kind() == TypedExpressionKind.RANGE) {
            return new TypedFailureSite(expression.span(), FailureSiteKind.ARITHMETIC,
                    "LYR-ARITH", expression.kind());
        }
        if (expression.kind() == TypedExpressionKind.INDEX_ACCESS) {
            return new TypedFailureSite(
                    expression.span(), FailureSiteKind.BOUNDS, "LYR-BOUNDS", expression.kind());
        }
        if (expression.kind() == TypedExpressionKind.CONVERSION
                && expression.conversion().filter(value -> value.kind()
                == io.mindspice.lyra.compiler.types.ConversionKind.EXPLICIT
                && value.step() == io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_EXPLICIT)
                .isPresent()) {
            return new TypedFailureSite(
                    expression.span(), FailureSiteKind.CONVERSION, "LYR-CONVERT", expression.kind());
        }
        if (expression.kind() == TypedExpressionKind.OPERATOR
                && expression.type().isNumeric()
                && expression.operator().isPresent()
                && !expression.operator().orElseThrow().equals("+")) {
            String operator = expression.operator().orElseThrow();
            if (operator.equals("/")) {
                return new TypedFailureSite(
                        expression.span(), FailureSiteKind.DIVISION, "LYR-ARITH", expression.kind());
            }
            if (List.of("-", "*", "%", "^", "++", "--").contains(operator)) {
                return new TypedFailureSite(
                        expression.span(), FailureSiteKind.ARITHMETIC, "LYR-ARITH", expression.kind());
            }
        }
        if (expression.kind() == TypedExpressionKind.OPERATOR
                && expression.type().isNumeric()
                && expression.operator().filter("+"::equals).isPresent()) {
            return new TypedFailureSite(
                    expression.span(), FailureSiteKind.ARITHMETIC, "LYR-ARITH", expression.kind());
        }
        return null;
    }
}
