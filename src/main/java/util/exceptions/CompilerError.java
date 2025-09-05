package util.exceptions;

/**
 * Sealed interface hierarchy for compiler errors.
 * This allows automatic covariance compatibility and eliminates the need for castErr() calls.
 */
public sealed interface CompilerError 
    permits CompilerError.ParseError, 
            CompilerError.ResolutionError, 
            CompilerError.NamespaceError,
            CompilerError.InternalError,
            CompilerError.GrammarError {
    
    int line();
    int column();
    String message();
    
    // Parse errors
    sealed interface ParseError extends CompilerError 
        permits ParseError.Expected, ParseError.Unexpected, ParseError.InvalidToken {
        
        record Expected(String expected, String found, int line, int column) implements ParseError {
            @Override
            public String message() {
                return String.format("Expected: %s, Found: %s", expected, found);
            }
        }
        
        record Unexpected(String token, int line, int column) implements ParseError {
            @Override
            public String message() {
                return "Unexpected token: " + token;
            }
        }
        
        record InvalidToken(String token, String reason, int line, int column) implements ParseError {
            @Override
            public String message() {
                return "Invalid token '" + token + "': " + reason;
            }
        }
    }
    
    // Resolution errors
    sealed interface ResolutionError extends CompilerError 
        permits ResolutionError.SymbolNotFound, 
                ResolutionError.AmbiguousSymbol, 
                ResolutionError.CircularDependency,
                ResolutionError.TypeMismatch,
                ResolutionError.EmptyNamespaceChain,
                ResolutionError.NamespaceNotFound,
                ResolutionError.AmbiguousResolution {
        
        record SymbolNotFound(String symbol, int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Symbol not found: " + symbol;
            }
        }
        
        record AmbiguousSymbol(String symbol, java.util.List<String> candidates, int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Ambiguous symbol '" + symbol + "'. Could be: " + String.join(", ", candidates);
            }
        }
        
        record CircularDependency(java.util.List<String> cycle, int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Circular dependency detected: " + String.join(" -> ", cycle);
            }
        }
        
        record TypeMismatch(String expected, String actual, int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Type mismatch: expected " + expected + ", got " + actual;
            }
        }
        
        record EmptyNamespaceChain(int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Empty namespace chain provided";
            }
        }
        
        record NamespaceNotFound(String namespace, int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Namespace not found: " + namespace;
            }
        }
        
        record AmbiguousResolution(String path, java.util.List<String> candidates, int line, int column) implements ResolutionError {
            @Override
            public String message() {
                return "Ambiguous resolution for '" + path + "'. Could be: " + String.join(", ", candidates);
            }
        }
    }
    
    // Namespace errors
    sealed interface NamespaceError extends CompilerError 
        permits NamespaceError.InvalidPath, NamespaceError.PathNotFound, NamespaceError.CircularReference {
        
        record InvalidPath(String path, String reason, int line, int column) implements NamespaceError {
            @Override
            public String message() {
                return "Invalid namespace path '" + path + "': " + reason;
            }
        }
        
        record PathNotFound(String path, int line, int column) implements NamespaceError {
            @Override
            public String message() {
                return "Namespace path not found: " + path;
            }
        }
        
        record CircularReference(String path, int line, int column) implements NamespaceError {
            @Override
            public String message() {
                return "Circular reference in namespace: " + path;
            }
        }
    }
    
    // Internal errors
    sealed interface InternalError extends CompilerError 
        permits InternalError.NullPointer, InternalError.InvalidState, InternalError.UnexpectedCondition {
        
        record NullPointer(String location, int line, int column) implements InternalError {
            @Override
            public String message() {
                return "Internal null pointer at: " + location;
            }
        }
        
        record InvalidState(String state, String expected, int line, int column) implements InternalError {
            @Override
            public String message() {
                return "Invalid internal state: " + state + ", expected: " + expected;
            }
        }
        
        record UnexpectedCondition(String condition, int line, int column) implements InternalError {
            @Override
            public String message() {
                return "Unexpected internal condition: " + condition;
            }
        }
    }
    
    // Grammar errors
    sealed interface GrammarError extends CompilerError 
        permits GrammarError.InvalidPattern, GrammarError.MalformedRule, GrammarError.ConflictingRules {
        
        record InvalidPattern(String pattern, String reason, int line, int column) implements GrammarError {
            @Override
            public String message() {
                return "Invalid grammar pattern '" + pattern + "': " + reason;
            }
        }
        
        record MalformedRule(String rule, int line, int column) implements GrammarError {
            @Override
            public String message() {
                return "Malformed grammar rule: " + rule;
            }
        }
        
        record ConflictingRules(String rule1, String rule2, int line, int column) implements GrammarError {
            @Override
            public String message() {
                return "Conflicting grammar rules: " + rule1 + " vs " + rule2;
            }
        }
    }
    
    // Utility methods for formatted messages
    default String getFormattedMessage() {
        if (line() >= 0 && column() >= 0) {
            return String.format("[Line: %d, Char: %d] %s", line(), column(), message());
        }
        return message();
    }
    
    // Convert to exception when needed for legacy compatibility
    default Exception toException() {
        return new RuntimeException(getFormattedMessage());
    }
}