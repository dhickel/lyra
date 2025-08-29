package Parse;

import java.util.List;
import java.util.Optional;

public sealed interface Form {
    sealed interface Statement extends Form {
        record Reassign(Expression assignment) implements Statement { }

        record Let(boolean hasType, int modifierCount, Expression expression) { }
    }

    sealed interface Expression extends Form {
        record SExpr(Operation operation, List<Expression> operands) implements Expression { }

        record VExpr() implements Expression { }

        record FExpr(int namespace, boolean hasIdentifier, List<MemberAccess> accessChain) implements Expression { }

        // record BExpr() implements Expression { } // FIXME we are also calling in the ast block expression BExpr

        record BlockExpr(List<Form> members) implements Expression { }

        record CondExpr(Expression predicateExpression, PredicateForm predicateForm) implements Expression { }

        record LambdaExpr(boolean hasType, LambdaForm form) implements Expression { }

        record LambdaFormExpr(LambdaForm form) implements Expression { }

        record MatchExpr() implements Expression { }

        record IterExpr() implements Expression { }
    }

    sealed interface Operation extends Form {
        record Expr(Expression expression) implements Operation { }

        record Builtin() implements Operation { }
    }

    sealed interface MemberAccess extends Form {
        record Field() implements MemberAccess { }

        record MethodAccess() implements MemberAccess { }

        record MethodCall(List<Arg> arguments) implements MemberAccess { }
    }

    record Arg(int modifierCount, Expression expression) implements Form { }

    record Param(int modifierCount, boolean hasType) implements Form { }

    record Parameters(List<Param> params) implements Form {
        public static Parameters EMPTY = new Parameters(List.of());
    }

    record PredicateForm(Expression thenForm, Optional<Expression> elseForm) implements Form { }

    record LambdaForm(Parameters parameters, Expression expression) implements Form { }

}
