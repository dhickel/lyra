package lang.env;

import java.util.Optional;

public class Identifier {
    private String name;
    private SymbolRef symbolRef = null;

    public Identifier(String name) {
        this.name = name;
    }

    public void linkSymbolRef(SymbolRef ref) { this.symbolRef = ref; }

    public String name() { return name; }

    public static Identifier of(String name) { return new Identifier(name); }

    public Optional<SymbolRef> getSymbolRef() {
        return symbolRef == null ? Optional.empty() : Optional.of(symbolRef);
    }

    public State getState() {
        return symbolRef == null
                ? State.Unresolved
                : symbolRef.resolved() ? State.FullyResolved : State.PartiallyResolved;
    }

    public enum State {
        Unresolved,
        PartiallyResolved,
        FullyResolved,
    }


}
