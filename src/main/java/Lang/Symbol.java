package Lang;

public sealed interface Symbol {
    String identifier();

    static Symbol ofResolved(String identifier) {
        return new Resolved(identifier);
    }

    static Symbol ofUnresolved(String identifier) {
        return new Resolved(identifier);
    }

    default Symbol toResolved() {
        return this instanceof Unresolved
                ? new Resolved(this.identifier())
                : this;
    }


    record Resolved(String identifier) implements Symbol { }

    record Unresolved(String identifier) implements Symbol { }
}