import compile.Compiler;
import lang.env.Environment;
import util.Result;
import util.exceptions.CError;

import java.util.List;

public class Main {
    public static void main(String[] args) {
    Environment environment = new Environment();
    environment.addTextUnitToGlobalNs(Compiler.Unit.of("namespace->namespace->Type"));



    System.out.println(environment);


    List<Compiler.UnitTransform> unitTransforms = List.of(
            Compiler::readUnit,
            Compiler::lexUnit,
            Compiler::parseUnit
    );

    Compiler.UnitTransform pipeline = Compiler.createPipeline(unitTransforms);

    Result<Void, CError> result = environment.compileModulesWith(Compiler.ModuleTransform.ofUnitTransform(pipeline));

    System.out.println(result);

    }
}
