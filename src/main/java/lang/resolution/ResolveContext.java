package lang.resolution;

public record ResolveContext(int namespaceId, int scopeId, int depth) {
    
    public static ResolveContext of(int namespaceId, int scopeId, int depth) {
        return new ResolveContext(namespaceId, scopeId, depth);
    }
    
    public static ResolveContext global(int namespaceId) {
        return new ResolveContext(namespaceId, 0, 0);
    }
    
    @Override
    public String toString() {
        return "ResolveContext{ns=" + namespaceId + ", scope=" + scopeId + ", depth=" + depth + "}";
    }
}