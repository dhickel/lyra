package util.exceptions;

import lang.env.Symbol;

public class ResolutionError extends Error {
    public ResolutionError(String message) {
        super(message);
    }


    public static ResolutionError duplicateSymbol(Symbol symbol) {
        return new ResolutionError(String.format("Attempted to redeclare symbol:" + symbol));
    }

    public static ResolutionError of(String msg) {
        return new ResolutionError(msg);
    }

    @Override
    public int line() {
        return -1;
    }

    @Override
    public int column() {
        return -1;
    }
}
