package Lang.AST;

import Lang.LangType;
import Lang.LineChar;

import java.lang.reflect.Type;

public class MetaData {
    private final LineChar lineChar;
    private ResolutionState resolutionState;

    MetaData(LineChar lineChar, ResolutionState resolutionState) {
        this.lineChar = lineChar;
        this.resolutionState = resolutionState;
    }

    public LineChar lineChar() { return lineChar; }

    public ResolutionState resolutionState() { return resolutionState; }

    public static MetaData ofUnresolved(LineChar lineChar, LangType type) {
        return new MetaData(lineChar, new ResolutionState.Resolved(type));
    }

    public void setResolved(LangType type) {
        if (!(resolutionState instanceof ResolutionState.Unresolved)) {
            throw new IllegalStateException("Error<Internal>: Type already resolved");
        }

        if (type != LangType.UNDEFINED
            && (type == resolutionState.type() || resolutionState.type() == LangType.UNDEFINED)) {
            resolutionState = new ResolutionState.Resolved(type);
        }
    }


    private sealed interface ResolutionState {
        LangType type();

        record Resolved(LangType type) implements ResolutionState { }

        record Unresolved(LangType type) implements ResolutionState { }

    }

}
