package util;

import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Result<T, E extends Exception> permits Result.Err, Result.Ok {


    record Ok<T, E extends Exception>(T value) implements Result<T, E> { }

    record Err<T, E extends Exception>(E error) implements Result<T, E> { }

    static <T, E extends Exception> Result<T, E> ok(T value) {
        if (value == null) { throw new IllegalArgumentException("Null result value no allowed)"); }

        return new Ok<>(value);
    }

    static <Void, E extends Exception> Result<Void, E> okVoid() {
        return new Result.Ok<>(null);
    }



    static <T, E extends Exception> Result<T, E> err(E error) {
        return new Err<>(error);
    }


    default boolean isOk() { return this instanceof Ok; }

    default boolean isErr() { return this instanceof Err; }

    default T unwrap() {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> throw new IllegalStateException("Tried to unwrap Err: " + err.error());
        };
    }

    default T unwrapOr(T fallback) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> __ -> fallback;
        };
    }

    default T unwrapOrGet(Supplier<? extends T> supplier) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> __ -> supplier.get();
        };
    }

    // --- Mapping ---
    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<T, E> ok -> Result.ok(f.apply(ok.value()));
            case Err<T, E> err -> Result.err(err.error());
        };
    }

    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> f) {
        return switch (this) {
            case Ok<T, E> ok -> f.apply(ok.value());
            case Err<T, E> err -> Result.err(err.error());
        };
    }

    default Result<T, E> orElse(Supplier<Result<T, E>> alt) {
        return switch (this) {
            case Ok<T, E> ok -> ok;
            case Err<T, E> __ -> alt.get();
        };
    }


    // --- Error side ---
    default <F extends Exception> Result<T, F> mapErr(Function<? super E, ? extends F> f) {
        return switch (this) {
            case Ok<T, E> ok -> Result.ok(ok.value());
            case Err<T, E> err -> Result.err(f.apply(err.error()));
        };
    }

    default Result<T, E> recover(Function<? super E, ? extends T> f) {
        return switch (this) {
            case Ok<T, E> ok -> ok;
            case Err<T, E> err -> Result.ok(f.apply(err.error()));
        };
    }

    default T throwOnErr() throws E {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> throw err.error();
        };
    }

    @SuppressWarnings("unchecked")
    default <U> Result<U, E> castErr() {
        return switch (this) {
            case Ok<T, E> __ -> throw new IllegalStateException("Cannot cast error from Ok result");
            case Err<T, E> err -> (Result<U, E>) err; // Zero allocation };
        };
    }

//    default <U> Result<U, E> castErr() {
//        return switch (this) {
//            case Ok<T, E> __ -> throw new IllegalStateException("Cannot cast error from Ok result");
//            case Err<T, E> err -> Result.err(err.error());
//        };
//    }

    default <U> Result<U, E> asErr() {
        if (this instanceof Err<T, E> err) {
            return Result.err(err.error());
        }
        throw new IllegalStateException("Cannot cast error from Ok result");
    }
}

