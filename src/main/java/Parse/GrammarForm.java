package Parse;

import java.util.List;
import java.util.Optional;

public sealed interface GrammarForm {
    sealed interface Statement extends GrammarForm {
        record Reassign(Expression assignment) implements Statement { }

        record Let(boolean hasType, int modifierCount, Expression expression) implements Statement { }
    }

    sealed interface Expression extends GrammarForm {
        record SExpr(Operation operation, List<Expression> operands) implements Expression { }

        record VExpr() implements Expression { }

//        record FExpr(int namespaceDepth, boolean hasIdentifier,
//                     List<MemberAccess> accessChain) implements Expression { }

        record FExpr(int namespaceDepth, List<MemberAccess> accessChain) implements Expression { }


        // record BExpr() implements Expression { } // FIXME we are also calling in the ast block expression BExpr

        record BlockExpr(List<GrammarForm> members) implements Expression { }

        record CondExpr(Expression predicateExpression, PredicateForm predicateForm) implements Expression { }

        record LambdaExpr(boolean hasType, LambdaForm form) implements Expression { }

        record LambdaFormExpr(LambdaForm form) implements Expression { }

        record MatchExpr() implements Expression { }

        record IterExpr() implements Expression { }
    }

    sealed interface Operation extends GrammarForm {
        record Expr(Expression expression) implements Operation { }

        record Op() implements Operation { }
    }

    sealed interface MemberAccess extends GrammarForm {

        record Field() implements MemberAccess { }

        record MethodAccess() implements MemberAccess { }

        record MethodCall(List<Arg> arguments) implements MemberAccess { }
    }

    record Arg(int modifierCount, Expression expression) implements GrammarForm { }

    record Arguments(List<Arg> args) implements GrammarForm {
        public static Arguments EMPTY = new Arguments(List.of());
    }

    record Param(int modifierCount, boolean hasType) implements GrammarForm { }

    record Parameters(List<Param> params) implements GrammarForm {
        public static Parameters EMPTY = new Parameters(List.of());
    }

    record AccessChain(List<MemberAccess> accessChain) implements GrammarForm { }

    record PredicateForm(Expression thenForm, Optional<Expression> elseForm) implements GrammarForm { }

    record LambdaForm(Parameters parameters, Expression expression) implements GrammarForm { }

}
