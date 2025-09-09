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


    List<Compiler.Step> steps = List.of(
            Compiler::readUnit,
            Compiler::lexUnit,
            Compiler::parseUnit
    );

    Compiler.Step pipeline = Compiler.createPipeline(steps);

    Result<Void, CError> result = environment.applyCompilerStep(pipeline);

    System.out.println(environment);


}
