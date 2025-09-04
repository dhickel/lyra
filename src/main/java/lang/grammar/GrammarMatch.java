package lang.grammar;

import util.Result;
import util.exceptions.CompExcept;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Gatherer;

public sealed interface GrammarMatch {
    GrammarMatch NONE = new None();

    record Found(GrammarForm form) implements GrammarMatch { }

    record None() implements GrammarMatch { }

    static GrammarMatch of(GrammarForm form) {
        return new Found(form);
    }

    default boolean isFound() {
        return this instanceof Found;
    }

    default boolean isNone() {
        return this instanceof None;
    }

    default Result<GrammarMatch, CompExcept> intoResult() {
        return Result.ok(this);
    }



    static <T extends GrammarForm> Gatherer<Result<GrammarMatch, CompExcept>, Void, Result<T, CompExcept>>
    takeWhileFoundOfMatch(Class<T> type) {
        return Gatherer.of(Gatherer.Integrator.of((state, result, downstream) -> {
            if (result instanceof Result.Err<GrammarMatch, CompExcept>(CompExcept error)) {
                downstream.push(Result.err(error));
                return false; // short-circuit on error
            }
            switch (result.unwrap()) {
                case Found(var form) when type.isInstance(form) ->
                        downstream.push(Result.ok(type.cast(form)));
                default -> {
                    return false; // stop on None or wrong type
                }
            }
            return true;
        }));
    }

}
