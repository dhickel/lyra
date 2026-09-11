package io.mindspice.lyra.repl;

/** Scalar representation carried by a typed value snapshot. */
public enum ScalarKind {
    BOOLEAN,
    CHARACTER,
    STRING,
    SIGNED_INTEGER,
    UNSIGNED_INTEGER,
    FLOAT,
    RANGE
}
