package compile;

import compile.resolution.Environment;
import lang.ast.ASTNode;
import parse.Lexer;
import parse.Parser;
import parse.Token;
import util.Result;
import util.exceptions.Error;
import util.exceptions.InternalError;
import util.exceptions.ResolutionError;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Main compilation pipeline that integrates lexing, parsing, and resolution.
 */
public class Compiler {

    public record CompUnit(
            Path file,
            String text,
            List<Token> tokens,
            List<ASTNode> rootExpressions,
            State state
    ) {
        public enum State {
            INIT(0), READ(1), LEXED(2), PARSED(3), PARTIALLY_RESOLVED(4), FULLY_RESOLVED(5);
            public final int value;

            State(int value) { this.value = value; }
        }


        public CompUnit asRead(String text) { return new CompUnit(this.file, text, List.of(), List.of(), State.READ); }

        public CompUnit asLexed(List<Token> tokens) { return new CompUnit(this.file, this.text, tokens, List.of(), State.LEXED); }

        public CompUnit asParsed(List<ASTNode> rootExpressions) {
            return new CompUnit(this.file, this.text, this.tokens, rootExpressions, State.PARSED);
        }

        public CompUnit asPartiallyResolved() {
            return new CompUnit(this.file, this.text, this.tokens, this.rootExpressions, State.PARTIALLY_RESOLVED);
        }

        public CompUnit asFullyResolved() {
            return new CompUnit(this.file, this.text, this.tokens, this.rootExpressions, State.FULLY_RESOLVED);
        }

    }

    public static class CompModule {
        private List<CompUnit> compUnits;

        public CompModule(List<CompUnit> compUnits) { this.compUnits = Collections.unmodifiableList(compUnits); }

        public CompModule of(List<CompUnit> compUnits) { return new CompModule(compUnits); }

        public Result<CompModule, Error> transform(Function<CompUnit, Result<CompUnit, Error>> func) {
            List<Result<CompUnit, Error>> results = compUnits.stream()
                    .map(Compiler::read)
                    .toList();

            return switch (results.stream().allMatch(Result::isOk)) {
                case true -> {
                    this.compUnits = unwrapUnitResults(results);
                    yield Result.ok(this);
                }
                case false -> getUnitError(results).castErr();
            };

        }


        public CompUnit.State getState() {
            return compUnits.stream()
                    .map(CompUnit::state)
                    .min(Comparator.comparingInt(s -> s.value))
                    .orElse(CompUnit.State.INIT);
        }
    }

    private static Result<CompUnit, Error> getUnitError(List<Result<CompUnit, Error>> results) {
        return results.stream()
                .filter(Result::isErr)
                .findFirst()
                .orElse(Result.err(InternalError.of("Invalid state in read, no error when expected")));
    }

    private static List<CompUnit> unwrapUnitResults(List<Result<CompUnit, Error>> results) {
        return results.stream().map(Result::unwrap).toList();
    }


    private static Result<CompUnit, Error> read(CompUnit compUnit) {
        try {
            String text = Files.readString(compUnit.file);
            return Result.ok(compUnit.asRead(text));
        } catch (IOException e) {
            return Result.err(InternalError.of("Failed to read file: " + compUnit.file));
        }
    }

    private static Result<CompUnit, Error> lex(CompUnit compUnit) {
        if (compUnit.state.value != CompUnit.State.READ.value) {
            Result.err(InternalError.of("Attempted to lex at invalid state: " + compUnit.state));
        }
        return switch (Lexer.process(compUnit.text)) {
            case Result.Err<List<Token>, Error> err -> err.castErr();
            case Result.Ok(List<Token> tokens) -> Result.ok(compUnit.asLexed(tokens));
        };
    }

    public static Result<CompUnit, Error> parse(CompUnit compUnit) {
        if (compUnit.state.value != CompUnit.State.LEXED.value) {
            Result.err(InternalError.of("Attempted to parse at invalid state: " + compUnit.state));
        }
        var parser = new Parser.LangParser(compUnit.tokens);
        return switch (parser.process()) {
            case Result.Err<ASTNode.CompilationUnit, Error> err -> err.castErr();
            case Result.Ok(ASTNode.CompilationUnit parsedUnit) -> Result.ok(compUnit.asParsed(parsedUnit.topMost()));
        };
    }


    public static Result<CompModule, Error> readModule(CompModule compModule) { return compModule.transform(Compiler::read); }

    public static Result<CompModule, Error> lexModule(CompModule compModule) { return compModule.transform(Compiler::lex); }

    public static Result<CompModule, Error> parseModule(CompModule compModule) { return compModule.transform(Compiler::parse); }


}