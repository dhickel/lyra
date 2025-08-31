package Lang.AST;

import java.util.List;

public sealed interface Statement extends ASTNode {
    record Let(Symbol identifier, List<Modifiers> modifiers, Expression assignment, MetaData metaData) implements Statement { }

    record Assign(Namespace namespace, Symbol identifier, Expression assignment, MetaData metaData) implements Statement { }

}
