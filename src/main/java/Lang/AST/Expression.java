package Lang.AST;

import Lang.Operation;

import java.util.List;
import java.util.Optional;

public sealed interface Expression extends ASTNode {
    record SExpr(Expression operation, List<Expression> operands) implements Expression { }

    record FExpr(List<FExpression.FAccess> accessors, Optional<FExpression.FCall> call) implements Expression { }

    // Operation Expression
    record OExpr(Operation op, List<Expression> operands) implements Expression { }

    // Block
    record BExpr(List<ASTNode> expressions) implements Expression { }

    //value
    record VExpr(Value value) { }

    // predicate
    record PExpr(Expression predExpr, Expression thenExpr, Expression elseExpr) implements Expression { }

    //lambda
    record LExpr(List<Parameter> parameters, Expression body, boolean isForm) implements Expression { }
    interface FExpression {
        record FCall(Symbol method, List<Expression> arguments) implements FExpression { }

        record FAccess(Symbol identifier) implements FExpression { }

    }
}
