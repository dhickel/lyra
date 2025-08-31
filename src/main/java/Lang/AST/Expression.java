package Lang.AST;

import Lang.Operation;

import java.util.List;
import java.util.Optional;

public sealed interface Expression extends ASTNode {
    record SExpr(Expression operation, List<Expression> operands, MetaData metaData) implements Expression { }

    record FExpr(List<FExpression.FAccess> accessors, Optional<FExpression.FCall> call,
                 MetaData metaData) implements Expression { }

    // Operation Expression
    record OExpr(Operation op, List<Expression> operands, MetaData metaData) implements Expression { }

    // Block
    record BExpr(List<ASTNode> expressions, MetaData metaData) implements Expression { }

    //value
    record VExpr(Value value, MetaData metaData) implements Expression { }

    // predicate
    record PExpr(Expression predExpr, Expression thenExpr, Expression elseExpr,
                 MetaData metaData) implements Expression { }

    //lambda
    record LExpr(List<Parameter> parameters, Expression body, boolean isForm,
                 MetaData metaData) implements Expression { }

    interface FExpression {
        record FCall(Symbol method, List<Expression> arguments) implements FExpression { }

        record FAccess(Symbol identifier) implements FExpression { }

    }
}
