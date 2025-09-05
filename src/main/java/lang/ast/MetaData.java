package lang.ast;

import lang.LangType;
import lang.LineChar;
import lang.resolution.ResolveContext;
import lang.types.TypeConversion;
import lang.types.TypeId;

public class MetaData {
    private final LineChar lineChar;
    private ResolutionState resolutionState;
    private TypeConversion typeConversion;
    private ResolveContext resolveContext;

    MetaData(LineChar lineChar, ResolutionState resolutionState) {
        this.lineChar = lineChar;
        this.resolutionState = resolutionState;
        this.typeConversion = TypeConversion.none();
        this.resolveContext = null;
    }

    public LineChar lineChar() { return lineChar; }

    public ResolutionState resolutionState() { return resolutionState; }
    
    public TypeConversion typeConversion() { return typeConversion; }
    
    public ResolveContext resolveContext() { return resolveContext; }

    public static MetaData ofUnresolved(LineChar lineChar, LangType type) {
        return new MetaData(lineChar, new ResolutionState.Unresolved(type));
    }

    public static MetaData ofResolved(LineChar lineChar, LangType type) {
        return new MetaData(lineChar, new ResolutionState.Resolved(type, null));
    }
    
    public static MetaData ofResolved(LineChar lineChar, LangType type, TypeId typeId) {
        return new MetaData(lineChar, new ResolutionState.Resolved(type, typeId));
    }

    public void setResolved(LangType type) {
        if (!(resolutionState instanceof ResolutionState.Unresolved)) {
            throw new IllegalStateException("Error<Internal>: Type already resolved");
        }

        if (type != LangType.UNDEFINED
            && (type.equals(resolutionState.type()) || resolutionState.type() == LangType.UNDEFINED)) {
            resolutionState = new ResolutionState.Resolved(type, null);
        }
    }
    
    public void setResolved(LangType type, TypeId typeId) {
        if (!(resolutionState instanceof ResolutionState.Unresolved)) {
            throw new IllegalStateException("Error<Internal>: Type already resolved");
        }

        if (type != LangType.UNDEFINED
            && (type.equals(resolutionState.type()) || resolutionState.type() == LangType.UNDEFINED)) {
            resolutionState = new ResolutionState.Resolved(type, typeId);
        }
    }
    
    public void setTypeConversion(TypeConversion conversion) {
        this.typeConversion = conversion;
    }
    
    public void setResolveContext(ResolveContext context) {
        this.resolveContext = context;
    }
    
    public boolean isResolved() {
        return resolutionState instanceof ResolutionState.Resolved;
    }


    public sealed interface ResolutionState {
        LangType type();

        record Resolved(LangType type, TypeId typeId) implements ResolutionState { }

        record Unresolved(LangType type) implements ResolutionState { }

    }

    @Override
    public String toString() {
        return "MetaData{" +
               "lineChar=" + lineChar +
               ", resolutionState=" + resolutionState +
               ", typeConversion=" + typeConversion +
               ", resolveContext=" + resolveContext +
               '}';
    }
}
