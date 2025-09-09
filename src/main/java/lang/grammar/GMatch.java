package lang.grammar;

import util.Result;
import util.exceptions.CError;

import java.util.stream.Gatherer;

public sealed interface GMatch {
    GMatch NONE = new None();

    record Found(GForm form) implements GMatch { }

    record None() implements GMatch { }

    static GMatch of(GForm form) {
        return new Found(form);
    }

    default boolean isFound() {
        return this instanceof Found;
    }

    default boolean isNone() {
        return this instanceof None;
    }

    default Result<GMatch, CError> intoResult() {
        return Result.ok(this);
    }



    static <T extends GForm> Gatherer<Result<GMatch, CError>, Void, Result<T, CError>>
    takeWhileFoundOfMatch(Class<T> type) {
        return Gatherer.of(Gatherer.Integrator.of((state, result, downstream) -> {
            if (result instanceof Result.Err<GMatch, CError>(CError CError)) {
                downstream.push(Result.err(CError));
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
