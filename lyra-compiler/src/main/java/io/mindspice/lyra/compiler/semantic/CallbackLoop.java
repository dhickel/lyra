package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.types.*;
import java.util.List;
import java.util.Optional;

/** Reserved source calls and their exact, non-overloaded callback contracts. */
public enum CallbackLoop {
    ITER, WHILE;

    public static Optional<CallbackLoop> of(SyntaxNode.Expression expression) {
        String name = switch (expression) {
            case SyntaxNode.CallableCall call when call.target() instanceof SyntaxNode.Identifier id -> id.name();
            case SyntaxNode.DirectCall call when call.receiver().isEmpty() -> call.name().name();
            default -> "";
        };
        return switch (name) {
            case "iter" -> Optional.of(ITER);
            case "while" -> Optional.of(WHILE);
            default -> Optional.empty();
        };
    }

    public static List<SyntaxNode.Expression> arguments(SyntaxNode.Expression expression) {
        return switch (expression) {
            case SyntaxNode.CallableCall call -> call.argumentExpressions();
            case SyntaxNode.DirectCall call -> call.argumentExpressions();
            default -> throw new IllegalArgumentException("not a callback loop call");
        };
    }

    public static int anonymousArity(SyntaxNode.Expression expression) {
        return switch (expression) {
            case SyntaxNode.Lambda lambda -> lambda.parameters().size();
            case SyntaxNode.CompactLambda lambda -> lambda.parameters().size();
            case SyntaxNode.Block block when !block.forms().isEmpty()
                    && block.forms().getLast() instanceof SyntaxNode.Expression last -> anonymousArity(last);
            case SyntaxNode.Conditional conditional -> anonymousArity(conditional.thenBranch()) >= 0
                    ? anonymousArity(conditional.thenBranch())
                    : conditional.elseBranch().map(CallbackLoop::anonymousArity).orElse(-1);
            case SyntaxNode.Match match -> match.arms().stream().mapToInt(arm -> anonymousArity(arm.result()))
                    .filter(arity -> arity >= 0).findFirst().orElse(-1);
            case SyntaxNode.Cond cond -> cond.arms().stream().mapToInt(arm -> anonymousArity(arm.result()))
                    .filter(arity -> arity >= 0).findFirst().orElse(-1);
            case SyntaxNode.Coalesce coalesce -> anonymousArity(coalesce.fallback());
            default -> -1;
        };
    }

    public static FunctionType predicateType() {
        return new FunctionType(List.of(), PrimitiveType.BOOL);
    }

    public static FunctionType actionType() {
        return new FunctionType(List.of(), PrimitiveType.UNIT);
    }

    public static boolean valid(TypedExpressionKind kind, LyraType result, List<LyraType> arguments) {
        if (result != PrimitiveType.UNIT || arguments.size() != 2) return false;
        if (kind == TypedExpressionKind.WHILE) {
            return arguments.getFirst().equals(predicateType()) && arguments.getLast().equals(actionType());
        }
        if (kind != TypedExpressionKind.ITER || !(arguments.getFirst() instanceof RangeType range)) return false;
        return arguments.getLast().equals(actionType())
                || arguments.getLast().equals(new FunctionType(List.of(range.elementType()), PrimitiveType.UNIT));
    }
}
