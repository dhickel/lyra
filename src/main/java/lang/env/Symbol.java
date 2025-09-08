package lang.env;

import compile.resolution.NsScope;
import lang.LineChar;
import lang.ast.ASTNode;

import java.util.Set;

public record Symbol(
    String identifier,
    int typeId,
    Set<ASTNode.Modifier> modifiers,
    Meta metaData,
    LineChar lineChar,
    NsScope nsScope
) {
    
    public static Symbol of(
            String identifier,
            int typeId,
            Set<ASTNode.Modifier> modifiers,
            Meta metaData,
            LineChar lineChar,
            NsScope nsScope
    ) {
        return new Symbol(identifier, typeId, modifiers, metaData, lineChar, nsScope);
    }

    public sealed interface Meta {
        record Field() implements Meta {};
        record Function() implements Meta {};
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