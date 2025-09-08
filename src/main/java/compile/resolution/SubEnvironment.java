import lang.LangType;
import lang.ast.ASTNode;
import lang.types.*;
import util.Result;

import java.util.*;

public class SubEnvironment {
    private final int namespaceId;
    private int currentScope;
    private int currentDepth;
    private final List<Integer> activeScopes;
    private final SymbolTable symbolTable;
    private final TypeTable typeTable;
    
    public SubEnvironment(int namespaceId, SymbolTable symbolTable, TypeTable typeTable) {
        this.namespaceId = namespaceId;
        this.currentScope = 0;
        this.currentDepth = 0;
        this.activeScopes = new ArrayList<>(List.of(0)); // Global scope
        this.symbolTable = symbolTable;
        this.typeTable = typeTable;
    }
    
    // Scope Management
    public void pushScope() {
        currentScope++;
        currentDepth++;
        activeScopes.add(currentScope);
    }
    
    public void popScope() {
        if (activeScopes.size() <= 1) {
            throw new IllegalStateException("Cannot pop global scope");
        }
        activeScopes.removeLast();
        currentDepth--;
        
        // Update currentScope to be the top of the active scopes
        if (!activeScopes.isEmpty()) {
            currentScope = activeScopes.getLast();
        } else {
            currentScope = 0; // Should not happen given the check above, but for safety
        }
    }
    
    public void resetScopeForNextIteration() {
        if (currentDepth != 0) {
            throw new IllegalStateException("Reset scope at non-zero depth: " + currentDepth);
        }
        currentScope = 0;
        // Keep only global scope
        activeScopes.clear();
        activeScopes.add(0);
    }
    
    // Context Information
    public ResolveContext getCurrentContext() {
        return ResolveContext.of(namespaceId, currentScope, currentDepth);
    }
    
    public int getCurrentScope() {
        return currentScope;
    }
    
    public int getCurrentDepth() {
        return currentDepth;
    }
    
    public List<Integer> getActiveScopes() {
        return new ArrayList<>(activeScopes);
    }
    
    // Symbol Management
    public Result<Void, ResolutionError> addSymbol(
            String symbol, TypeId typeId, Set<ASTNode.Modifier> modifiers) {
        

        
        SymbolContext context = SymbolContext.of(
            symbol,
            typeId,
            modifiers,
            getCurrentContext()
        );
        
        return symbolTable.insertSymbol(namespaceId, currentScope, context);
    }
    
    public Optional<SymbolContext> findSymbolInScope(String identifier) {
        return symbolTable.findSymbol(namespaceId, activeScopes, identifier);
    }
    
    public Optional<SymbolContext> getSymbolInCurrentScope(String identifier) {
        return symbolTable.getSymbol(namespaceId, currentScope, identifier);
    }
    
    // Type Management
    public Optional<TypeEntry> getTypeEntry(TypeId typeId) {
        return typeTable.getEntry(typeId);
    }
    
    public Optional<TypeEntry> lookupType(LangType type) {
        return typeTable.lookupByType(type);
    }
    
    public Optional<TypeEntry> resolveType(LangType type) {
        return typeTable.resolveType(type);
    }
    
    public Optional<TypeId> lookupTypeByName(String name) {
        return typeTable.lookupByName(name);
    }
    
    public boolean isTypeResolved(LangType type) {
        return typeTable.isResolved(type);
    }
    
    // Type Compatibility
    public TypeCompatibility.Result checkTypeCompatibility(
        TypeId sourceType, TypeId targetType) {
        return typeTable.checkCompatibility(sourceType, targetType);
    }
    
    public TypeCompatibility.Result checkTypeCompatibility(
        LangType sourceType, LangType targetType) {
        
        Optional<TypeEntry> sourceEntry = typeTable.lookupByType(sourceType);
        Optional<TypeEntry> targetEntry = typeTable.lookupByType(targetType);
        
        if (sourceEntry.isEmpty() || targetEntry.isEmpty()) {
            return TypeCompatibility.Result.incompatible();
        }
        
        return checkTypeCompatibility(sourceEntry.get().id(), targetEntry.get().id());
    }
    
    // Convenience methods for common primitive types
    public TypeEntry getNilType() {
        return TypeTable.NIL;
    }
    
    public TypeEntry getBoolType() {
        return TypeTable.BOOL;
    }
    
    public TypeEntry getI32Type() {
        return TypeTable.I32;
    }
    
    public TypeEntry getI64Type() {
        return TypeTable.I64;
    }
    
    public TypeEntry getF32Type() {
        return TypeTable.F32;
    }
    
    public TypeEntry getF64Type() {
        return TypeTable.F64;
    }
    
    // Operation type resolution
    public Optional<LangType.Primitive> getWidestPrimitiveType(List<LangType> types) {
        return typeTable.getWidestPrimitiveType(types);
    }
    
    @Override
    public String toString() {
        return "SubEnvironment{" +
               "ns=" + namespaceId +
               ", scope=" + currentScope +
               ", depth=" + currentDepth +
               ", activeScopes=" + activeScopes +
               '}';
    }
}