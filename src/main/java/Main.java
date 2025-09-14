import compile.Compiler;
import lang.env.RootEnv;
import util.Result;
import util.exceptions.CError;

import java.util.List;

public class Main {
    public static void main(String[] args) {
    RootEnv rootEnv = new RootEnv();
    rootEnv.addTextUnitToGlobalNS(Compiler.Unit.of("import package.other.now as new"));






    List<Compiler.UnitTransform> unitTransforms = List.of(
            Compiler::readUnit,
            Compiler::lexUnit,
            Compiler::parseUnit
    );

    Compiler.UnitTransform pipeline = Compiler.createPipeline(unitTransforms);

    Result<Void, CError> result = rootEnv.compileModulesWith(Compiler.ModuleTransform.ofUnitTransform(pipeline));

    System.out.println(result);
        System.out.println(rootEnv);

    }
}
