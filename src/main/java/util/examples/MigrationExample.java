package util.examples;

import util.CompilerResult;
import util.exceptions.CompilerError;

/**
 * Examples showing how to migrate existing castErr() patterns
 */
public class MigrationExample {

    // BEFORE: Your current parser pattern with castErr()
    public static class CurrentPattern {
        /*
        private Result<ASTNode.Stmt.Let, CompExcept> parseLetStatement(GForm.Stmt.Let letStatement) {
            // ::= let
            if (consume(TokenType.Definition.Let).isErr()) { 
                return consume(TokenType.Definition.Let).castErr(); // ❌ CAST REQUIRED
            }

            // ::= Identifier  
            var identifierResult = parseIdentifier();
            if (identifierResult.isErr()) { 
                return identifierResult.castErr(); // ❌ CAST REQUIRED
            }
            
            // ... more parsing with more castErr() calls
            
            return Result.ok(new ASTNode.Stmt.Let(...));
        }
        
        private Result<String, ParseError> parseIdentifier() {
            // Returns specific ParseError
        }
        
        private Result<Token, ParseError> consume(TokenType type) {
            // Returns specific ParseError  
        }
        */
    }

    // AFTER: Clean sealed interface pattern
    public static class NewPattern {
        
        // Main parsing method - returns broad error type
        private static CompilerResult<ASTNode, CompilerError> parseLetStatement() {
            // All helper methods can be called without casts!
            var letToken = consume(TokenType.LET);
            if (letToken.isErr()) return letToken.widen(); // Clean widening
            
            var identifier = parseIdentifier();  
            if (identifier.isErr()) return identifier.widen(); // No cast needed
            
            var equal = consume(TokenType.EQUAL);
            if (equal.isErr()) return equal.widen(); // Same pattern everywhere
            
            var expr = parseExpression();
            if (expr.isErr()) return expr.widen(); // Consistent
            
            return CompilerResult.ok(new ASTNode(identifier.unwrap(), expr.unwrap()));
        }

        // Helper methods return specific error types
        private static CompilerResult<String, CompilerError.ParseError> parseIdentifier() {
            // Specific parsing logic
            return CompilerResult.err(new CompilerError.ParseError.Expected("identifier", "number", 1, 5));
        }
        
        private static CompilerResult<Token, CompilerError.ParseError> consume(TokenType type) {
            // Specific token consumption
            return CompilerResult.err(new CompilerError.ParseError.Unexpected("EOF", 1, 10));
        }
        
        private static CompilerResult<Expression, CompilerError> parseExpression() {
            // This might call multiple sub-parsers with different error types
            return CompilerResult.err(new CompilerError.ResolutionError.SymbolNotFound("x", 1, 15));
        }

        // Dummy types for example
        record ASTNode(String id, Expression expr) {}
        record Expression(String value) {}
        record Token(String value) {}
        enum TokenType { LET, EQUAL }
    }

    // ADVANCED: Using flatMap for even cleaner composition (eliminates all error checking)
    public static class AdvancedPattern {
        
        // This version has ZERO explicit error handling - it's all automatic!
        private static CompilerResult<ASTNode, CompilerError> parseLetStatementComposed() {
            return consume(TokenType.LET)
                .<CompilerError>widen()  // Widen once at start of chain
                .flatMap(__ -> parseIdentifier().<CompilerError>widen())
                .flatMap(id -> consume(TokenType.EQUAL).<CompilerError>widen().map(__ -> id))
                .flatMap(id -> parseExpression().map(expr -> new ASTNode(id, expr)));
        }

        // Even cleaner with a utility method for widening
        private static <T> Function<CompilerResult<T, ? extends CompilerError>, CompilerResult<T, CompilerError>> 
        widen() {
            return result -> result.widen();
        }
        
        private static CompilerResult<ASTNode, CompilerError> parseLetStatementUltraClean() {
            return consume(TokenType.LET).widen()
                .flatMap(__ -> parseIdentifier().widen())  
                .flatMap(id -> consume(TokenType.EQUAL).widen().map(__ -> id))
                .flatMap(id -> parseExpression().map(expr -> new ASTNode(id, expr)));
        }

        // Helper methods (same as above)
        private static CompilerResult<String, CompilerError.ParseError> parseIdentifier() {
            return CompilerResult.ok("x");
        }
        
        private static CompilerResult<Token, CompilerError.ParseError> consume(TokenType type) {
            return CompilerResult.ok(new Token("token"));
        }
        
        private static CompilerResult<Expression, CompilerError> parseExpression() {
            return CompilerResult.ok(new Expression("expr"));
        }

        record ASTNode(String id, Expression expr) {}
        record Expression(String value) {}
        record Token(String value) {}
        enum TokenType { LET, EQUAL }
    }

    // MIGRATION STRATEGY: You can do this gradually
    public static class GradualMigration {
        
        // Step 1: Add CompilerResult versions alongside existing Result methods
        // (Keep both during transition)
        
        private static util.Result<String, util.exceptions.CompExcept> parseIdentifierOld() {
            // Keep existing implementation
            return util.Result.err(null);
        }
        
        private static CompilerResult<String, CompilerError.ParseError> parseIdentifierNew() {
            // New implementation
            return CompilerResult.err(new CompilerError.ParseError.Expected("id", "num", 1, 1));
        }
        
        // Step 2: Bridge methods for compatibility
        private static CompilerResult<String, CompilerError> parseIdentifierBridge() {
            // Call old method and convert
            var oldResult = parseIdentifierOld();
            return oldResult.isOk() 
                ? CompilerResult.ok(oldResult.unwrap())
                : CompilerResult.err(new CompilerError.ParseError.Expected("converted", "error", 0, 0));
        }
        
        // Step 3: Gradually migrate callers to use new methods
        // Step 4: Remove old methods once all callers are migrated
    }
}