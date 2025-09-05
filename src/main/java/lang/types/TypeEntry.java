package lang.types;

import lang.LangType;

public record TypeEntry(TypeId id, LangType type) {
    
    public static TypeEntry of(TypeId id, LangType type) {
        return new TypeEntry(id, type);
    }
    
    public static TypeEntry of(int id, LangType type) {
        return new TypeEntry(TypeId.of(id), type);
    }
    
    @Override
    public String toString() {
        return "TypeEntry{id=" + id + ", type=" + type + "}";
    }
}