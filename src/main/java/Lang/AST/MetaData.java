package Lang.AST;

import Lang.LineChar;

public class MetaData {
    private final LineChar lineChar;
    private ResolutionState resolutionState;

    MetaData(LineChar lineChar, ResolutionState resolutionState) {
        this.lineChar = lineChar;
        this.resolutionState = resolutionState;
    }

    public LineChar lineChar() { return lineChar; }

    public ResolutionState resolutionState() { return resolutionState; }

    public static MetaData ofUnresolved(LineChar lineChar) {
        return new MetaData(lineChar, ResolutionState.UNRESOLVED);
    }

    public void updateResolution(ResolutionState.Resolved resolution) {
        this.resolutionState = resolution;
    }

}
