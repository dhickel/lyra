package Lang.AST;

import java.util.List;

public sealed interface Statement extends ASTNode {
    record Let(Symbol identifier, List<Modifiers> modifiers, Expression assignment) implements Statement { }

    record Assign(Namespace namespace, Symbol identifier, Expression assignment) implements Statement { }

}
