package io.mindspice.lyra.compiler.diagnostic;

/** Diagnostic severity. */
public enum Severity {
    ERROR,
    WARNING,
    INFO;

    public boolean isError() {
        return this == ERROR;
    }
}
