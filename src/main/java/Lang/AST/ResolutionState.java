package Lang.AST;

public sealed interface ResolutionState {
    ResolutionState UNRESOLVED = new Unresolved();

    record Resolved() implements ResolutionState { }

    record Unresolved() implements ResolutionState { }

}
