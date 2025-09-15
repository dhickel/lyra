import compile.Compiler;
import compile.resolve.Resolver;
import lang.ast.ASTNode;
import lang.env.ModuleEnv;
import lang.env.RootEnv;
import org.junit.jupiter.api.Test;
import util.Result;
import util.exceptions.CError;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResolutionTest {

    private ModuleEnv runPipeline(String code) {
        RootEnv rootEnv = new RootEnv();
        Compiler.Unit unit = Compiler.Unit.of(code);
        rootEnv.addTextUnitToGlobalNS(unit);

        List<Compiler.UnitTransform> transforms = List.of(
                Compiler::readUnit,
                Compiler::lexUnit,
                Compiler::parseUnit
        );
        Compiler.UnitTransform pipeline = Compiler.createPipeline(transforms);

        Result<Void, CError> result = rootEnv.compileModulesWith(Compiler.ModuleTransform.ofUnitTransform(pipeline));
        if (result.isErr()) {
            fail("Pipeline failed at parse stage: " + result.unwrapErr());
        }

        ModuleEnv moduleEnv = rootEnv.getNewModuleEnv(0);
        Resolver resolver = new Resolver(moduleEnv);

        Result<Void, CError> resolveResult = resolver.resolve();
        if (resolveResult.isErr()) {
            fail("Resolution failed: " + resolveResult.unwrapErr());
        }
        return moduleEnv;
    }

    @Test
    void testSimpleLet() {
        String code = "let x = 10";
        runPipeline(code);
    }

    @Test
    void testForwardReference() {
        String code = """
        let g = (=> | | (f))
        let f = (=> | | 10)
        """;
        ModuleEnv env = runPipeline(code);

        // Find the call to f() in g's body
        var expressions = env.getExpressions().unwrap();
        var letG = (ASTNode.Stmt.Let) expressions.get(0);
        var lambdaG = (ASTNode.Expr.L) letG.assignment();
        var bodyG = (ASTNode.Expr.S) lambdaG.body();
        var callF = (ASTNode.Expr.V) bodyG.operation();
        var idF = (ASTNode.Value.Identifier) callF.value();

        assertTrue(callF.metaData().isSymbolResolved(), "Symbol for f should be resolved");
    }

    @Test
    void testSelfReference() {
        String code = "let f = (=> |n| (f (- n 1)))";
        runPipeline(code);
    }

    @Test
    void testMutualRecursion() {
        String code = """
        let is_even = (=> |n| (is_odd (- n 1)))
        let is_odd = (=> |n| (is_even (- n 1)))
        """;
        runPipeline(code);
    }

    @Test
    void testShadowing() {
        String code = """
        let x = 10
        let y = {
            let x = 20
            x
        }
        """;
        runPipeline(code);
    }
}
