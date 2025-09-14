package lang.env;

import compile.resolve.NsScope;
import lang.LineChar;
import lang.ast.ASTNode;

import java.util.Optional;
import java.util.Set;

public class SymbolRef {
    SymbolData resolveData = null;

    public SymbolRef(SymbolData resolveData) {
        this.resolveData = resolveData;
    }

    public boolean resolved() { return resolveData != null; }

    public static SymbolRef ofUnresolved() { return new SymbolRef(null); }

    public void resolve(SymbolData data) { this.resolveData = data; }

    public Optional<SymbolData> resolveData() {
        return resolveData == null ? Optional.empty() : Optional.of(resolveData);
    }


    public static class SymbolData {

        String identifier;
        Set<ASTNode.Modifier> modifiers;
        TypeRef typeRef;
        Meta metaData;
        LineChar lineChar;
        NsScope nsScope;

        public SymbolData(
                String identifier,
                Set<ASTNode.Modifier> modifiers,
                TypeRef typeRef,
                Meta metaData,
                LineChar lineChar,
                NsScope nsScope
        ) {
            this.identifier = identifier;
            this.typeRef = typeRef;
            this.modifiers = modifiers;
            this.metaData = metaData;
            this.lineChar = lineChar;
            this.nsScope = nsScope;
        }

        public static SymbolData of(
                String identifier,
                TypeRef typeRef,
                Set<ASTNode.Modifier> modifiers,
                Meta metaData,
                LineChar lineChar,
                NsScope nsScope
        ) {
            return new SymbolData(identifier, modifiers, typeRef, metaData, lineChar, nsScope);
        }

        public sealed interface Meta {
            record Field() implements Meta { }

            record Function() implements Meta { }

        }

        public boolean isMutable() {
            return modifiers.contains(ASTNode.Modifier.MUTABLE);
        }

        public boolean isPublic() {
            return modifiers.contains(ASTNode.Modifier.PUBLIC);
        }

        public boolean isConst() {
            return modifiers.contains(ASTNode.Modifier.CONST);
        }

        public boolean isOptional() {
            return modifiers.contains(ASTNode.Modifier.OPTIONAL);
        }


    }


}

