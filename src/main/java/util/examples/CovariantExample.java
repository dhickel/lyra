package util.examples;

import util.CompilerResult;
import util.exceptions.CompilerError;

/**
 * Example showing how sealed interfaces eliminate castErr() calls
 */
public class CovariantExample {

    // BEFORE: With Exception-based hierarchy (requires castErr)
    public static class OldWay {
        // Helper method returns specific error type
        private static util.Result<String, util.exceptions.ParseError> parseToken() {
            var error = util.exceptions.ParseError.expected(
                new parse.Token(parse.TokenType.IDENTIFIER, "foo", 1, 1), 
                "NUMBER"
            );
            return util.Result.err(error);
        }

        // Main method expects broad error type  
        private static util.Result<String, util.exceptions.CompExcept> parseExpression() {
            var tokenResult = parseToken();
            // ❌ This requires castErr() because Result<T, ParseError> ≠ Result<T, CompExcept>
            if (tokenResult.isErr()) {
                return tokenResult.castErr(); // UGLY CAST REQUIRED
            }
            return util.Result.ok(tokenResult.unwrap());
        }
    }

    // AFTER: With sealed interface hierarchy (automatic covariance)
    public static class NewWay {
        // Helper method returns specific error type
        private static CompilerResult<String, CompilerError.ParseError> parseToken() {
            var error = new CompilerError.ParseError.Expected("NUMBER", "IDENTIFIER", 1, 1);
            return CompilerResult.err(error);
        }

        // Main method expects broad error type
        private static CompilerResult<String, CompilerError> parseExpression() {
            var tokenResult = parseToken();
            // ✅ This works automatically - no cast needed!
            // CompilerError.ParseError extends CompilerError, so this is legal
            if (tokenResult.isErr()) {
                return tokenResult.widen(); // Clean, explicit widening (or even automatic in many cases)
            }
            return CompilerResult.ok(tokenResult.unwrap());
        }

        // Even cleaner - often the widen() isn't needed due to type inference
        private static CompilerResult<String, CompilerError> parseExpressionClean() {
            CompilerResult<String, CompilerError.ParseError> tokenResult = parseToken();
            
            // The compiler can often infer the widening automatically in return statements
            return tokenResult.isOk() 
                ? CompilerResult.ok(tokenResult.unwrap())
                : CompilerResult.err(tokenResult.error()); // No cast needed!
        }
    }

    // Complex example showing multiple error types combining naturally
    public static class ComplexExample {
        
        private static CompilerResult<String, CompilerError.ParseError> parseIdentifier() {
            return CompilerResult.err(new CompilerError.ParseError.InvalidToken("123abc", "not a valid identifier", 1, 5));
        }
        
        private static CompilerResult<Integer, CompilerError.ResolutionError> resolveSymbol(String name) {
            return CompilerResult.err(new CompilerError.ResolutionError.SymbolNotFound(name, 1, 10));
        }
        
        private static CompilerResult<Void, CompilerError.NamespaceError> validateNamespace(String path) {
            return CompilerResult.err(new CompilerError.NamespaceError.InvalidPath(path, "empty segment", 1, 15));
        }

        // This method can handle all error types without any casts!
        public static CompilerResult<String, CompilerError> fullParse() {
            // Each of these can return different specific error types
            var idResult = parseIdentifier();
            if (idResult.isErr()) return idResult.widen(); // Explicit widen if needed
            
            var symResult = resolveSymbol(idResult.unwrap());
            if (symResult.isErr()) return symResult.widen(); // Different error type, same pattern
            
            var nsResult = validateNamespace("com.example");
            if (nsResult.isErr()) return nsResult.widen(); // Another error type
            
            return CompilerResult.ok("success");
        }

        // Or using flatMap for even cleaner composition
        public static CompilerResult<String, CompilerError> fullParseComposed() {
            return parseIdentifier()
                .<CompilerError>widen() // Widen once at the start of the chain
                .flatMap(id -> resolveSymbol(id).<CompilerError>widen())
                .flatMap(symId -> validateNamespace("com.example").<CompilerError>widen())
                .map(__ -> "success");
        }
    }
}