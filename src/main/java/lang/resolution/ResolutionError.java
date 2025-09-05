package lang.resolution;

import lang.LineChar;
import util.exceptions.CompExcept;

public abstract sealed class ResolutionError extends CompExcept {
    
    protected ResolutionError(String message) {
        super(message);
    }
    
    public static final class InvalidAssignment extends ResolutionError {
        private final LineChar location;
        
        public InvalidAssignment(LineChar location, String message) {
            super("Invalid assignment: " + message);
            this.location = location;
        }
        
        @Override public int line() { return location.line(); }
        @Override public int column() { return location.chr(); }
    }
    
    public static final class UnresolvedSymbol extends ResolutionError {
        private final LineChar location;
        
        public UnresolvedSymbol(LineChar location, String symbolName) {
            super("Unresolved symbol: " + symbolName);
            this.location = location;
        }
        
        @Override public int line() { return location.line(); }
        @Override public int column() { return location.chr(); }
    }
    
    public static final class TypeMismatch extends ResolutionError {
        private final LineChar location;
        
        public TypeMismatch(LineChar location, String expected, String actual) {
            super("Type mismatch: expected " + expected + " but found " + actual);
            this.location = location;
        }
        
        @Override public int line() { return location.line(); }
        @Override public int column() { return location.chr(); }
    }
    
    public static final class DuplicateSymbol extends ResolutionError {
        private final LineChar location;
        
        public DuplicateSymbol(LineChar location, String symbolName) {
            super("Duplicate symbol declaration: " + symbolName);
            this.location = location;
        }
        
        @Override public int line() { return location.line(); }
        @Override public int column() { return location.chr(); }
    }
    
    public static final class InvalidOperation extends ResolutionError {
        private final LineChar location;
        
        public InvalidOperation(LineChar location, String operation, String reason) {
            super("Invalid operation '" + operation + "': " + reason);
            this.location = location;
        }
        
        @Override public int line() { return location.line(); }
        @Override public int column() { return location.chr(); }
    }
    
    public static final class InvalidParameter extends ResolutionError {
        private final LineChar location;
        
        public InvalidParameter(LineChar location, String message) {
            super("Invalid parameter: " + message);
            this.location = location;
        }
        
        @Override public int line() { return location.line(); }
        @Override public int column() { return location.chr(); }
    }
    
    public static final class InvalidSymbol extends ResolutionError {
        public InvalidSymbol(String message) {
            super("Invalid symbol: " + message);
        }
        
        @Override public int line() { return -1; }
        @Override public int column() { return -1; }
    }
    
    // Convenience factory methods
    public static InvalidAssignment invalidAssignment(LineChar loc, String msg) {
        return new InvalidAssignment(loc, msg);
    }
    
    public static UnresolvedSymbol unresolvedSymbol(LineChar loc, String name) {
        return new UnresolvedSymbol(loc, name);
    }
    
    public static TypeMismatch typeMismatch(LineChar loc, String expected, String actual) {
        return new TypeMismatch(loc, expected, actual);
    }
    
    public static DuplicateSymbol duplicateSymbol(LineChar loc, String name) {
        return new DuplicateSymbol(loc, name);
    }
    
    public static InvalidOperation invalidOperation(LineChar loc, String op, String reason) {
        return new InvalidOperation(loc, op, reason);
    }
    
    public static InvalidParameter invalidParameter(LineChar loc, String msg) {
        return new InvalidParameter(loc, msg);
    }
}