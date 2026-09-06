package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.semantic.TypedConversion;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLink;
import io.mindspice.lyra.compiler.semantic.TypedLiteralValue;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JVM-independent normalized view of one typed expression.
 *
 * <p>The typed graph remains the source of truth.  This adapter copies only
 * immutable identity/type/link data and records the one route step and call
 * kind needed by symbolic flow.  Children remain in strict source evaluation
 * order.</p>
 */
public record NormalizedExpression(
        TypedExpressionKind kind,
        SourceSpan span,
        LyraType type,
        List<NormalizedExpression> children,
        Optional<TypedLink> link,
        Optional<DeclarationId> declarationId,
        Optional<LambdaId> lambdaId,
        Optional<ScopeId> scopeId,
        Optional<ProjectionStep> projectionStep,
        Optional<CallKind> callKind,
        List<CaptureId> captureIds,
        Optional<TypedLiteralValue> literal,
        Optional<TypedConversion> conversion,
        Optional<String> operator,
        Optional<String> memberName,
        Optional<BigInteger> tupleIndex,
        Optional<LyraSignature> signature,
        Optional<DeclarationId> predicateBinding) implements Comparable<NormalizedExpression> {
    public enum CallKind {
        DIRECT,
        NAMESPACE,
        CALLABLE
    }

    public NormalizedExpression {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
        children = copy(children, "children");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(projectionStep, "projectionStep");
        Objects.requireNonNull(callKind, "callKind");
        captureIds = copy(captureIds, "captureIds");
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(conversion, "conversion");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(tupleIndex, "tupleIndex");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(predicateBinding, "predicateBinding");
    }

    /** Compatibility constructor for callers interested only in flow fields. */
    public NormalizedExpression(
            TypedExpressionKind kind,
            SourceSpan span,
            LyraType type,
            List<NormalizedExpression> children,
            Optional<TypedLink> link,
            Optional<DeclarationId> declarationId,
            Optional<LambdaId> lambdaId,
            Optional<ScopeId> scopeId,
            Optional<ProjectionStep> projectionStep,
            Optional<CallKind> callKind,
            List<CaptureId> captureIds) {
        this(kind, span, type, children, link, declarationId, lambdaId, scopeId,
                projectionStep, callKind, captureIds, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public List<NormalizedExpression> operands() {
        return children;
    }

    public List<NormalizedExpression> arguments() {
        return children;
    }

    public Optional<ProjectionStep> routeStep() {
        return projectionStep;
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder()
                .append(span.sourceId()).append(':').append(span.startOffset())
                .append("..").append(span.endOffset()).append('/')
                .append(kind).append('/').append(type.canonicalSpelling());
        projectionStep.ifPresent(step -> result.append("/step=").append(step));
        callKind.ifPresent(call -> result.append("/call=").append(call));
        link.ifPresent(value -> result.append("/link=").append(value));
        declarationId.ifPresent(id -> result.append("/decl=").append(id));
        lambdaId.ifPresent(id -> result.append("/lambda=").append(id));
        scopeId.ifPresent(id -> result.append("/scope=").append(id));
        literal.ifPresent(value -> result.append("/literal=").append(value));
        conversion.ifPresent(value -> result.append("/conversion=").append(value));
        operator.ifPresent(value -> result.append("/operator=").append(value));
        memberName.ifPresent(value -> result.append("/member=").append(value));
        tupleIndex.ifPresent(value -> result.append("/tuple=").append(value));
        signature.ifPresent(value -> result.append("/signature=").append(value));
        predicateBinding.ifPresent(value -> result.append("/predicate=").append(value));
        for (CaptureId capture : captureIds) {
            result.append("/capture=").append(capture);
        }
        for (NormalizedExpression child : children) {
            result.append("/child=").append(child.canonicalKey());
        }
        return result.toString();
    }

    @Override
    public int compareTo(NormalizedExpression other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> result = new ArrayList<>(values.size());
        for (T value : values) {
            result.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(result);
    }
}
