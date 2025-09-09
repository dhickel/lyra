package compile;

import lang.ast.ASTNode;
import parse.Lexer;
import parse.Parser;
import parse.Token;
import util.Result;
import util.exceptions.CError;
import util.exceptions.InternalError;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public class Compiler {

    @FunctionalInterface
    public interface UnitTransform extends Function<Unit, Result<Unit, CError>> {
        @Override
        Result<Unit, CError> apply(Unit unit);
    }

    @FunctionalInterface
    public interface ModuleTransform extends Function<Module, Result<Void, CError>> {
        @Override
        Result<Void, CError> apply(Module mod);

        default ModuleTransform ofUnitTransform(UnitTransform transform) {
            return (mod -> mod.transform(transform));
        }
    }

    /**
     * Create a single Step from a list of steps that executes sequentially,
     * stopping on the first error encountered.
     */
    public static UnitTransform createPipeline(List<UnitTransform> unitTransforms) {
        return unit -> {
            Result<Unit, CError> current = Result.ok(unit);

            for (UnitTransform unitTransform : unitTransforms) {
                current = current.flatMap(unitTransform);
                if (current.isErr()) { return current; }
            }

            return current;
        };
    }

    public enum State {
        INIT(0), READ(1), LEXED(2), PARSED(3), PARTIALLY_RESOLVED(4), FULLY_RESOLVED(5);
        public final int value;

        State(int value) { this.value = value; }
    }

    public record Unit(
            Path file,
            String text,
            List<Token> tokens,
            List<ASTNode> rootExpressions,
            State state
    ) {


        public static Unit of(Path file) { return new Unit(file, "", List.of(), List.of(), State.INIT); }


        public Unit asRead(String text) { return new Unit(this.file, text, List.of(), List.of(), State.READ); }

        public Unit asLexed(List<Token> tokens) { return new Unit(this.file, this.text, tokens, List.of(), State.LEXED); }

        public Unit asParsed(List<ASTNode> rootExpressions) {
            return new Unit(this.file, this.text, this.tokens, rootExpressions, State.PARSED);
        }

        public Unit asPartiallyResolved() {
            return new Unit(this.file, this.text, this.tokens, this.rootExpressions, State.PARTIALLY_RESOLVED);
        }

        public Unit asFullyResolved() {
            return new Unit(this.file, this.text, this.tokens, this.rootExpressions, State.FULLY_RESOLVED);
        }

    }

    public static class Module {
        private List<Unit> units;

        public Module(List<Unit> units) { this.units = Collections.unmodifiableList(units); }

        public static Module of(List<Unit> units) { return new Module(units); }

        public Result<Void, CError> transform(UnitTransform func) {
            List<Result<Unit, CError>> results = units.stream()
                    .map(func)
                    .toList();

            return switch (results.stream().allMatch(Result::isOk)) {
                case true -> {
                    this.units = unwrapUnitResults(results);
                    yield Result.okVoid();
                }
                case false -> getUnitError(results).castErr();
            };
        }


        public State getState() {
            if (units.isEmpty()) { return State.FULLY_RESOLVED; }

            return units.stream()
                    .map(Unit::state)
                    .min(Comparator.comparingInt(s -> s.value))
                    .orElse(State.INIT);
        }

        @Override
        public String toString() {
            return "Module{" +
                   "units=" + units +
                   '}';
        }
    }

    private static Result<Unit, CError> getUnitError(List<Result<Unit, CError>> results) {
        return results.stream()
                .filter(Result::isErr)
                .findFirst()
                .orElse(Result.err(InternalError.of("Invalid state in read, no error when expected")));
    }

    private static List<Unit> unwrapUnitResults(List<Result<Unit, CError>> results) {
        return results.stream().map(Result::unwrap).toList();
    }


    public static Result<Unit, CError> readUnit(Unit unit) {
        try {
            String text = Files.readString(unit.file);
            return Result.ok(unit.asRead(text));
        } catch (IOException e) {
            return Result.err(InternalError.of("Failed to read file: " + unit.file));
        }
    }

    public static Result<Unit, CError> lexUnit(Unit unit) {
        if (unit.state.value < State.READ.value) {
            Result.err(InternalError.of("Attempted to lex at invalid state: " + unit.state));
        }
        return switch (Lexer.process(unit.text)) {
            case Result.Err<List<Token>, CError> err -> err.castErr();
            case Result.Ok(List<Token> tokens) -> Result.ok(unit.asLexed(tokens));
        };
    }

    public static Result<Unit, CError> parseUnit(Unit unit) {
        if (unit.state.value < State.LEXED.value) {
            Result.err(InternalError.of("Attempted to parse at invalid state: " + unit.state));
        }
        var parser = new Parser.LangParser(unit.tokens);
        return switch (parser.process()) {
            case Result.Err<ASTNode.CompilationUnit, CError> err -> err.castErr();
            case Result.Ok(ASTNode.CompilationUnit parsedUnit) -> Result.ok(unit.asParsed(parsedUnit.topMost()));
        };
    }


}