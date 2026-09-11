package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One fully typed source expression.  The children list is in evaluation
 * order; conversion and link metadata are explicit rather than inferred by a
 * later backend.
 */
public record TypedExpression(
        TypedExpressionKind kind,
        SourceSpan span,
        LyraType type,
        List<TypedExpression> children,
        Optional<TypedLiteralValue> literal,
        Optional<TypedLink> link,
        Optional<TypedConversion> conversion,
        Optional<String> operator,
        Optional<String> memberName,
        Optional<BigInteger> tupleIndex,
        Optional<DeclarationId> declarationId,
        Optional<LambdaId> lambdaId,
        Optional<ScopeId> scopeId,
        Optional<LyraSignature> signature,
        List<CaptureId> captureIds,
        Optional<DeclarationId> predicateBinding,
        Optional<TypedMatch> match) implements ImmutablePhaseArtifact {
    public TypedExpression {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
        children = copy(children, "children");
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(conversion, "conversion");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(tupleIndex, "tupleIndex");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(signature, "signature");
        captureIds = copy(captureIds, "captureIds");
        Objects.requireNonNull(predicateBinding, "predicateBinding");
        Objects.requireNonNull(match, "match");
        if (memberName.isPresent() == tupleIndex.isPresent()) {
            if (memberName.isPresent()) {
                throw new IllegalArgumentException("a typed member is either a name or tuple index");
            }
        }
        tupleIndex.ifPresent(index -> {
            if (index.signum() < 0) {
                throw new IllegalArgumentException("tuple member index must not be negative");
            }
        });
    }

    /** Compatibility constructor for non-match expression fixtures and adapters. */
    public TypedExpression(
            TypedExpressionKind kind, SourceSpan span, LyraType type,
            List<TypedExpression> children, Optional<TypedLiteralValue> literal,
            Optional<TypedLink> link, Optional<TypedConversion> conversion,
            Optional<String> operator, Optional<String> memberName,
            Optional<BigInteger> tupleIndex, Optional<DeclarationId> declarationId,
            Optional<LambdaId> lambdaId, Optional<ScopeId> scopeId,
            Optional<LyraSignature> signature, List<CaptureId> captureIds,
            Optional<DeclarationId> predicateBinding) {
        this(kind, span, type, children, literal, link, conversion, operator,
                memberName, tupleIndex, declarationId, lambdaId, scopeId,
                signature, captureIds, predicateBinding, Optional.empty());
    }

    public SourceSpan sourceSpan() {
        return span;
    }

    public LyraType valueType() {
        return type;
    }

    public List<TypedExpression> operands() {
        return children;
    }

    public List<TypedExpression> arguments() {
        return children;
    }

    public TypedLiteralValue literalValue() {
        return literal.orElseThrow(() -> new IllegalStateException("expression is not a literal"));
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
