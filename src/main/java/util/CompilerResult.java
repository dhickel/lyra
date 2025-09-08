package util;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Modern Result type designed for sealed error hierarchies.
 * Eliminates the need for castErr() through automatic covariance.
 */
public sealed interface CompilerResult<T, E extends CompilerError> 
    permits CompilerResult.Ok, CompilerResult.Err {

    record Ok<T, E extends CompilerError>(T value) implements CompilerResult<T, E> {}

    record Err<T, E extends CompilerError>(E error) implements CompilerResult<T, E> {}

    // Factory methods
    static <T, E extends CompilerError> CompilerResult<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E extends CompilerError> CompilerResult<T, E> err(E error) {
        return new Err<>(error);
    }

    // Query methods
    default boolean isOk() { 
        return this instanceof Ok; 
    }

    default boolean isErr() { 
        return this instanceof Err; 
    }

    // Value extraction
    default T unwrap() {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> throw new IllegalStateException("Tried to unwrap Err: " + err.error().message());
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

    // Mapping operations
    default <U> CompilerResult<U, E> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<T, E> ok -> CompilerResult.ok(f.apply(ok.value()));
            case Err<T, E> err -> CompilerResult.err(err.error());
        };
    }

    default <U> CompilerResult<U, E> flatMap(Function<? super T, ? extends CompilerResult<U, E>> f) {
        return switch (this) {
            case Ok<T, E> ok -> f.apply(ok.value());
            case Err<T, E> err -> CompilerResult.err(err.error());
        };
    }

    // Error handling
    default CompilerResult<T, E> orElse(Supplier<CompilerResult<T, E>> alt) {
        return switch (this) {
            case Ok<T, E> ok -> ok;
            case Err<T, E> __ -> alt.get();
        };
    }

    // **KEY FEATURE: Automatic covariance for sealed interfaces**
    // This method allows widening the error type automatically
    @SuppressWarnings("unchecked")
    default <F extends CompilerError> CompilerResult<T, F> widen() {
        return switch (this) {
            case Ok<T, E> ok -> (CompilerResult<T, F>) ok;
            case Err<T, E> err -> (CompilerResult<T, F>) err;
        };
    }

    // Error transformation
    default <F extends CompilerError> CompilerResult<T, F> mapErr(Function<? super E, ? extends F> f) {
        return switch (this) {
            case Ok<T, E> ok -> CompilerResult.ok(ok.value());
            case Err<T, E> err -> CompilerResult.err(f.apply(err.error()));
        };
    }

    // Recovery
    default CompilerResult<T, E> recover(Function<? super E, ? extends T> f) {
        return switch (this) {
            case Ok<T, E> ok -> ok;
            case Err<T, E> err -> CompilerResult.ok(f.apply(err.error()));
        };
    }

    // Legacy compatibility - convert to old Result when needed
    default Result<T, Exception> toResult() {
        return switch (this) {
            case Ok<T, E> ok -> Result.ok(ok.value());
            case Err<T, E> err -> Result.err(err.error().toException());
        };
    }

    // Get the error if this is an Err, otherwise throw
    default E error() {
        return switch (this) {
            case Ok<T, E> __ -> throw new IllegalStateException("Cannot get error from Ok result");
            case Err<T, E> err -> err.error();
        };
    }
}