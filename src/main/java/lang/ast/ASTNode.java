package lang.ast;

import lang.LangType;

import lang.Symbol;


import java.util.List;
import java.util.Optional;


public sealed interface ASTNode permits ASTNode.Expr, ASTNode.Stmt {
    MetaData metaData();

    record CompilationUnit(List<ASTNode> rootExpressions) { }

    record Parameter(List<Modifier> modifiers, Symbol identifier, LangType typ) { }

    record Argument(List<Modifier> modifiers, Expr expr) { }

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

    interface Access {
        record FuncCall(Symbol identifier, List<Argument> arguments) implements Access { }

        record Identifier(Symbol identifier) implements Access { }

        record Namespace(Symbol identifier) implements Access { }

    }


    sealed interface Expr extends ASTNode {
        record S(Expr operation, List<Expr> operands, MetaData metaData) implements Expr { }

        record M(List<Access> expressionChain, MetaData metaData) implements Expr { }

        // Operation Expression
        record O(Operation op, List<Expr> operands, MetaData metaData) implements Expr { }

        // Block
        record B(List<ASTNode> expressions, MetaData metaData) implements Expr { }

        //value
        record V(Value value, MetaData metaData) implements Expr { }

        // predicate
        record P(Expr predExpr, PForm predForm,
                 MetaData metaData) implements Expr { }

        //lambda
        record L(List<Parameter> parameters, Expr body, boolean isForm,
                 MetaData metaData) implements Expr { }


        record PForm(Optional<Expr> thenExpr, Optional<Expr> elseExpr,
                     MetaData metaData) implements Expr {

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


    sealed interface Stmt extends ASTNode {
        record Let(Symbol identifier, List<Modifier> modifiers, Expr assignment,
                   MetaData metaData) implements Stmt { }

        // This only handles local reassignment, member reassignment is an operation expression
        record Assign(Symbol target, Expr assignment,
                      MetaData metaData) implements Stmt { }

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