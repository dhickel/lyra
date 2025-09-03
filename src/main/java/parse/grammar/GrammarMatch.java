package parse.grammar;

import util.Result;
import util.exceptions.CompExcept;

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

    static <T extends GrammarForm> Gatherer<GrammarMatch, Void, T> takeWhileFoundOfMatch(Class<T> type) {
        return Gatherer.of(Gatherer.Integrator.of((state, match, downstream) ->
                switch (match) {
                    case Found(var form) when type.isInstance(form) -> downstream.push(type.cast(form));
                    default -> false; // Stop processing
                }));
    }
}
