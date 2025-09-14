package util.exceptions;

import lang.env.SymbolRef;

public class ResolutionError extends CError {
    public ResolutionError(String message) {
        super(message);
    }


    public static ResolutionError duplicateSymbol(SymbolRef SymbolRef) {
        return new ResolutionError(String.format("Attempted to redeclare symbol:" + SymbolRef));
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
