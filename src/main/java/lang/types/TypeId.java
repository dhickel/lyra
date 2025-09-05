package lang.types;

public record TypeId(int value) {
    public static TypeId of(int value) {
        return new TypeId(value);
    }
    
    public int asInt() {
        return value;
    }
    
    @Override
    public String toString() {
        return "TypeId(" + value + ")";
    }
}