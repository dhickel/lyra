package lang.resolution;

import lang.ast.ASTNode;
import parse.Lexer;
import parse.Parser;
import parse.Token;
import util.Result;
import util.exceptions.CompExcept;

import java.util.List;

/**
 * Main compilation pipeline that integrates lexing, parsing, and resolution.
 */
public class CompilationPipeline {
    
    public static Result<ResolutionResult, CompExcept> compile(String source) {
        return compile(source, 3); // Default to 3 resolution attempts
    }
    
    public static Result<ResolutionResult, CompExcept> compile(String source, int maxResolutionAttempts) {
        // Phase 1: Lexing
        List<Token> tokens = Lexer.process(source);
        
        // Phase 2: Parsing
        Result<ASTNode.CompilationUnit, CompExcept> parseResult = 
            new Parser.LangParser(tokens).process();
        
        if (parseResult.isErr()) {
            return parseResult.castErr();
        }
        
        ASTNode.CompilationUnit compilationUnit = parseResult.unwrap();
        
        // Phase 3: Symbol Resolution
        Environment environment = new Environment();
        SubEnvironment subEnv = environment.createMainSubEnvironment();
        Resolver resolver = new Resolver(subEnv);
        
        Result<ResolutionResult, ResolutionError> resolutionResult = 
            resolver.resolve(compilationUnit.rootExpressions(), maxResolutionAttempts);
        
        if (resolutionResult.isErr()) {
            return resolutionResult.mapErr(err -> (CompExcept) err);
        }
        
        return Result.ok(resolutionResult.unwrap());
    }
}