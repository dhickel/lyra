package lang.resolution;

import lang.ast.ASTNode;
import lang.types.TypeId;

import java.util.Set;

public record SymbolContext(
    String identifier,
    TypeId typeId,
    Set<ASTNode.Modifier> modifiers,
    ResolveContext resolveContext
) {
    
    public static SymbolContext of(String identifier, TypeId typeId, 
                                  Set<ASTNode.Modifier> modifiers, 
                                  ResolveContext resolveContext) {
        return new SymbolContext(identifier, typeId, modifiers, resolveContext);
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
    
    @Override
    public String toString() {
        return "SymbolContext{" +
               "id='" + identifier + '\'' +
               ", type=" + typeId +
               ", mods=" + modifiers +
               ", ctx=" + resolveContext +
               '}';
    }
}