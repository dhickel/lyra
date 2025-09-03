package lang.ast;

import lang.LangType;

import lang.Symbol;


import java.util.List;
import java.util.Optional;


public sealed interface ASTNode permits ASTNode.Expression, ASTNode.Statement {
    MetaData metaData();

    record CompilationUnit(List<ASTNode> rootExpressions) { }

    record Parameter(List<Modifier> modifiers, Symbol identifier, LangType typ) { }

    record Argument(List<Modifier> modifiers, Expression expr) { }

    enum PredicateType {
        COALESCE,
        MATCH,
        THEN_ELSE
    }


    enum Modifier {
        MUTABLE,
        PUBLIC,
        CONST,
        OPTIONAL
    }

    enum Operation {
        List,
        And,
        Or,
        Nor,
        Xor,
        Xnor,
        Nand,
        Negate,
        Plus,
        Minus,
        Asterisk,
        Slash,
        Caret,
        Percent,
        PlusPlus,
        MinusMinus,
        Greater,
        Less,
        GreaterEqual,
        LessEqual,
        Equals,
        BangEqual,
        EqualEqual,
        ReAssign,
    }

    interface AccessType {
        record FunctionCall(Symbol identifier, List<Argument> arguments) implements AccessType { }

        record Identifier(Symbol identifier) implements AccessType { }

        record Namespace(Symbol identifier) implements AccessType { }

    }


    sealed interface Expression extends ASTNode {
        record SExpr(Expression operation, List<Expression> operands, MetaData metaData) implements Expression { }

        record MExpr(List<AccessType> expressionChain, MetaData metaData) implements Expression { }

        // Operation Expression
        record OExpr(Operation op, List<Expression> operands, MetaData metaData) implements Expression { }

        // Block
        record BExpr(List<ASTNode> expressions, MetaData metaData) implements Expression { }

        //value
        record VExpr(Value value, MetaData metaData) implements Expression { }

        // predicate
        record PExpr(Expression predExpr, PredicateForm predForm,
                     MetaData metaData) implements Expression { }

        //lambda
        record LExpr(List<Parameter> parameters, Expression body, boolean isForm,
                     MetaData metaData) implements Expression { }


        record PredicateForm(Optional<Expression> thenExpr, Optional<Expression> elseExpr,
                             MetaData metaData) implements Expression {

            PredicateType predType() {
                if (thenExpr().isPresent() && elseExpr().isPresent()) {
                    return PredicateType.THEN_ELSE;
                } else if (thenExpr.isPresent()) {
                    return PredicateType.MATCH;
                } else if (elseExpr().isPresent()) {
                    return PredicateType.COALESCE;
                }
                throw new IllegalStateException("Error<Internal>: No then or else branch, invalid state");
            }

        }
    }


    sealed interface Statement extends ASTNode {
        record Let(Symbol identifier, List<Modifier> modifiers, Expression assignment,
                   MetaData metaData) implements Statement { }

        // This only handles local reassignment, member reassignment is an operation expression
        record Assign(Symbol target, Expression assignment,
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