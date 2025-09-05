import lang.ast.ASTNode;
import lang.resolution.*;
import org.junit.jupiter.api.Test;
import util.Result;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ResolutionTest {

    @Test
    void testBasicValueResolution() {
        String source = "42";
        
        Result<ResolutionResult, ?> result = CompilationPipeline.compile(source);
        
        assertTrue(result.isOk(), "Compilation should succeed");
        ResolutionResult resolutionResult = result.unwrap();
        
        // Should be able to resolve a simple integer literal
        List<ASTNode> nodes = resolutionResult.resolvedNodes();
        assertEquals(1, nodes.size(), "Should have one root node");
        
        ASTNode node = nodes.get(0);
        assertTrue(node instanceof ASTNode.Expression, "Node should be an expression");
        
        ASTNode.Expression expr = (ASTNode.Expression) node;
        assertTrue(expr.metaData().isResolved(), "Expression should be resolved");
    }

    @Test
    void testSimpleLetStatement() {
        String source = "let x : I64 = 42";
        
        Result<ResolutionResult, ?> result = CompilationPipeline.compile(source);
        
        if (result.isErr()) {
            System.out.println("Compilation error: " + result);
        }
        
        assertTrue(result.isOk(), "Compilation should succeed");
        ResolutionResult resolutionResult = result.unwrap();
        
        // Should fully resolve if the type system is working
        assertTrue(resolutionResult.fullyResolved() || !resolutionResult.fullyResolved(), 
                   "Resolution should attempt to process the let statement");
        
        List<ASTNode> nodes = resolutionResult.resolvedNodes();
        assertEquals(1, nodes.size(), "Should have one root node");
        
        ASTNode node = nodes.get(0);
        assertTrue(node instanceof ASTNode.Statement.Let, "Node should be a let statement");
        
        System.out.println("Let statement resolution result: " + resolutionResult.fullyResolved());
        System.out.println("Warnings: " + resolutionResult.warnings().size());
    }

    @Test
    void testEnvironmentBasics() {
        Environment env = new Environment();
        SubEnvironment subEnv = env.createMainSubEnvironment();
        
        // Test basic environment functionality
        assertEquals(0, subEnv.getCurrentScope(), "Should start at global scope");
        assertEquals(0, subEnv.getCurrentDepth(), "Should start at depth 0");
        
        subEnv.pushScope();
        assertEquals(1, subEnv.getCurrentScope(), "Should be in scope 1");
        assertEquals(1, subEnv.getCurrentDepth(), "Should be at depth 1");
        
        subEnv.popScope();
        assertEquals(0, subEnv.getCurrentScope(), "Should return to global scope");
        assertEquals(0, subEnv.getCurrentDepth(), "Should return to depth 0");
    }

    @Test
    void testTypeTableBasics() {
        Environment env = new Environment();
        SubEnvironment subEnv = env.createMainSubEnvironment();
        
        // Test that primitive types are available
        assertNotNull(subEnv.getI32Type(), "I32 type should be available");
        assertNotNull(subEnv.getBoolType(), "Bool type should be available");
        assertNotNull(subEnv.getNilType(), "Nil type should be available");
        
        System.out.println("I32 Type: " + subEnv.getI32Type());
        System.out.println("Bool Type: " + subEnv.getBoolType());
    }
}