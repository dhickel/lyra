package Lang.AST;

import Lang.LangType;
import Lang.Operation;
import Lang.Symbol;


import java.util.List;
import java.util.Optional;


public sealed interface ASTNode permits ASTNode.Expression, ASTNode.Statement {
    MetaData metaData();

    record Program(List<ASTNode> topMost) { }

    record Parameter(List<Modifier> modifiers, String identifier, LangType typ) { }

    enum Modifier {
        MUTABLE,
        PUBLIC,
        CONST,
        OPTIONAL
    }


    sealed interface Expression extends ASTNode {
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


    sealed interface Statement extends ASTNode {
        record Let(Symbol identifier, List<Modifier> modifiers, Expression assignment,
                   MetaData metaData) implements Statement { }

        record Assign(Symbol namespace, Symbol identifier, Expression assignment,
                      MetaData metaData) implements Statement { }

    }

    sealed interface Value {
        record I32(int i) implements Value { }

        record I64(long i) implements Value { }

        record F32(float f32) implements Value { }

        record F64(double f64) implements Value { }

        record Bool(boolean b) implements Value { }

        record Quote(ASTNode quotedNode) implements Value { }

        record Nil() implements Value { }

        record Array() implements Value { }

        record Str(String s) implements Value { }

        record Tuple() implements Value { }

        record Identifier(Symbol symbol) implements Value { }

    }


}