package lang.grammar;

import java.util.List;
import java.util.Optional;

public sealed interface GForm {
    sealed interface Stmt extends GForm {
        record Reassign(Expr assignment) implements Stmt { }

        record Let(boolean hasType, int modifierCount, Expr expr) implements Stmt { }
    }

    sealed interface Expr extends GForm {
        record S(Operation operation, List<Expr> operands) implements Expr { }

        record V() implements Expr { }

//        record FExpr(int namespaceDepth, boolean hasIdentifier,
//                     List<MemberAccess> accessChain) implements Expression { }

        record M(int namespaceDepth, List<Access> accessChain) implements Expr { }


        // record BExpr() implements Expression { } // FIXME we are also calling in the ast block expression BExpr

        record B(List<GForm> members) implements Expr { }

        record Cond(Expr predicateExpr, PForm pForm) implements Expr { }

        record L(boolean hasType, LForm form) implements Expr { }

        record LForm(Parameters parameters, Expr expr) implements Expr { }

        record Match() implements Expr { }

        record Iter() implements Expr { }
    }

    sealed interface Operation extends GForm {
        record ExprOp(Expr expr) implements Operation { }

        record Op() implements Operation { }
    }

    sealed interface Access extends GForm {

        record Identifier() implements Access { }

        record FuncCall(List<Arg> arguments) implements Access { }

        record FunctionAccess() implements Access {}
    }

    record Arg(int modifierCount, Expr expr) implements GForm { }

    record Arguments(List<Arg> args) implements GForm {
        public static Arguments EMPTY = new Arguments(List.of());
    }

    record Param(int modifierCount, boolean hasType) implements GForm { }

    record Parameters(List<Param> params) implements GForm {
        public static Parameters EMPTY = new Parameters(List.of());
    }

    record AccessChain(List<Access> accessChain) implements GForm { }

    record PForm(Optional<Expr> thenForm, Optional<Expr> elseForm) implements GForm { }



}
