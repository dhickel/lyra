import compile.Compiler;
import lang.env.Environment;
import util.Result;
import util.exceptions.CError;

void main() {
    Environment environment = new Environment();
    Result<Void, IOException> envResult = environment
            .buildNamespaceTree(
                    "/home/hickelpickle/Code/Java/mylang-compiler/src/test/resources/project"
            );


    System.out.println(envResult);
    System.out.println(environment);


    List<Compiler.UnitTransform> unitTransforms = List.of(
            Compiler::readUnit,
            Compiler::lexUnit,
            Compiler::parseUnit
    );

    Compiler.UnitTransform pipeline = Compiler.createPipeline(unitTransforms);

    Result<Void, CError> result = environment.compileModulesWith(pipeline);

    System.out.println(environment);


}
