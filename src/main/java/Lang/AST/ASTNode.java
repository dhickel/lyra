package Lang.AST;

import Lang.LangType;

import java.util.List;

public sealed interface ASTNode permits Expression, Statement {
    record Program(List<ASTNode> topMost) { }

    record Parameter(List<Modifiers> modifiers, Symbol identifier, LangType typ) { }

    record Symbol(String name, SymbolType symbolType) {
        public enum SymbolType {
            FUNCTION, // placeholder
        }
    }

    record Namespace() { }

     enum Modifiers {}

}