package lang.env;

import lang.LangType;

public class TypeRef {
    private LangType type;
    private TypeState state;

    public TypeRef(LangType type) {
        this.type = type;
        this.state = TypeState.Unresolved;
    }

    public static TypeRef of(LangType type) { return new TypeRef(type); }

    public static TypeRef ofResolved(LangType type) {
        var ref = new TypeRef(type);
        ref.setResolved();
        return ref;
    }

    public enum TypeState {
        Unresolved,
        Resolved
    }

    public boolean isResolved() {
        return state == TypeState.Resolved;
    }

    public boolean isDefined() {
        return type != LangType.UNDEFINED;
    }

    public void setResolved() {
        state = TypeState.Resolved;

    }
}
