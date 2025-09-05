package lang.resolution;

import util.exceptions.CompExcept;

public abstract sealed class NamespaceError extends CompExcept {
    
    protected NamespaceError(String message) {
        super(message);
    }
    
    public static final class InvalidPath extends NamespaceError {
        public InvalidPath(String path, String reason) {
            super("Invalid namespace path '" + path + "': " + reason);
        }
        
        @Override public int line() { return -1; }
        @Override public int column() { return -1; }
    }
    
    public static final class PathNotFound extends NamespaceError {
        public PathNotFound(String path) {
            super("Namespace path not found: " + path);
        }
        
        @Override public int line() { return -1; }
        @Override public int column() { return -1; }
    }
    
    public static final class CircularReference extends NamespaceError {
        public CircularReference(String path) {
            super("Circular reference in namespace path: " + path);
        }
        
        @Override public int line() { return -1; }
        @Override public int column() { return -1; }
    }
    
    public static InvalidPath invalidPath(String path, String reason) {
        return new InvalidPath(path, reason);
    }
    
    public static PathNotFound pathNotFound(String path) {
        return new PathNotFound(path);
    }
    
    public static CircularReference circularReference(String path) {
        return new CircularReference(path);
    }
}