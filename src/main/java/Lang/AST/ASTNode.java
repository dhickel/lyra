package Lang.AST;

import Lang.LangType;

import Lang.Symbol;


import java.util.List;


public sealed interface ASTNode permits ASTNode.Expression, ASTNode.Statement {
    MetaData metaData();

    record Program(List<ASTNode> topMost) { }

    record Parameter(List<Modifier> modifiers, Symbol identifier, LangType typ) { }

    record Argument(List<Modifier> modifiers, Expression expr) {}

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
        record Call(Symbol identifier, List<Expression> arguments) implements AccessType { }

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
        record PExpr(Expression predExpr, Expression thenExpr, Expression elseExpr,
                     MetaData metaData) implements Expression { }

        //lambda
        record LExpr(List<Parameter> parameters, Expression body, boolean isForm,
                     MetaData metaData) implements Expression { }


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