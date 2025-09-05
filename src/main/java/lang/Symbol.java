package lang;

public record Symbol(String identifier) {


    public static Symbol of(String identifier) {
        return new Symbol(identifier);
    }


}