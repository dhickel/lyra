import compile.Compiler;
import lang.env.Environment;
import util.Result;
import util.exceptions.Error;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        Environment environment = new Environment();

        
        List<Compiler.Step> steps = List.of(
            Compiler::readUnit,
            Compiler::lexUnit,
            Compiler::parseUnit
        );
        
        Compiler.Step pipeline = Compiler.createPipeline(steps);

        Result<Void, Error> result = environment.applyCompilerStep(pipeline);
        

    }
}
