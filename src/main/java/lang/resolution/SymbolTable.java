package lang.resolution;

import util.Result;

import java.util.*;

public class SymbolTable {
    private final Map<Long, List<SymbolContext>> table;
    
    public SymbolTable() {
        this.table = new HashMap<>();
    }
    
    private static long generateKey(int namespaceId, int scopeId) {
        return ((long) namespaceId << 32) | (scopeId & 0xFFFFFFFFL);
    }
    
    public Result<Void, ResolutionError> insertSymbol(
        int namespaceId, int scopeId, SymbolContext context) {
        
        long key = generateKey(namespaceId, scopeId);
        List<SymbolContext> symbols = table.computeIfAbsent(key, 
            k -> new ArrayList<>());
        
        // Binary search for insertion point
        int insertionPoint = Collections.binarySearch(symbols, context, 
            Comparator.comparing(SymbolContext::identifier));
        
        if (insertionPoint >= 0) {
            return Result.err(ResolutionError.duplicateSymbol(
                context.resolveContext().namespaceId() == namespaceId ? null : null, // TODO: get proper LineChar
                context.identifier()));
        }
        
        symbols.add(-(insertionPoint + 1), context);
        return Result.ok(null);
    }
    
    public Optional<SymbolContext> findSymbol(
        int namespaceId, List<Integer> activeScopes, String identifier) {
        
        // Search from innermost to outermost scope
        for (int i = activeScopes.size() - 1; i >= 0; i--) {
            int scopeId = activeScopes.get(i);
            Optional<SymbolContext> found = getSymbol(namespaceId, scopeId, identifier);
            if (found.isPresent()) return found;
        }
        
        return Optional.empty();
    }
    
    public Optional<SymbolContext> getSymbol(
        int namespaceId, int scopeId, String identifier) {
        
        long key = generateKey(namespaceId, scopeId);
        List<SymbolContext> symbols = table.get(key);
        
        if (symbols == null) return Optional.empty();
        
        // Create a dummy context for binary search
        SymbolContext searchKey = SymbolContext.of(identifier, 
            null, Set.of(), null);
        
        int index = Collections.binarySearch(symbols, searchKey, 
            Comparator.comparing(SymbolContext::identifier));
        
        return index >= 0 ? Optional.of(symbols.get(index)) : Optional.empty();
    }
    
    public void clearScope(int namespaceId, int scopeId) {
        long key = generateKey(namespaceId, scopeId);
        table.remove(key);
    }
    
    public boolean hasSymbolsInScope(int namespaceId, int scopeId) {
        long key = generateKey(namespaceId, scopeId);
        List<SymbolContext> symbols = table.get(key);
        return symbols != null && !symbols.isEmpty();
    }
    
    public List<SymbolContext> getAllSymbolsInScope(int namespaceId, int scopeId) {
        long key = generateKey(namespaceId, scopeId);
        List<SymbolContext> symbols = table.get(key);
        return symbols != null ? new ArrayList<>(symbols) : new ArrayList<>();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SymbolTable{\n");
        for (Map.Entry<Long, List<SymbolContext>> entry : table.entrySet()) {
            long key = entry.getKey();
            int namespaceId = (int) (key >> 32);
            int scopeId = (int) (key & 0xFFFFFFFFL);
            sb.append(String.format("  [ns:%d, scope:%d] -> %s\n", 
                namespaceId, scopeId, entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }
}