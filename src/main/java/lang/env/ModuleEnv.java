package lang.env;

import lang.ast.ASTNode;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import util.Result;
import util.exceptions.CError;
import util.exceptions.ResolutionError;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import compile.resolve.NsScope;

public class ModuleEnv {
    private int scopeCounter = 0;
    private final IntList scopeStack = new IntArrayList(20);
    private final Namespace namespace;
    private final Function<List<String>, Optional<Namespace>> lookupNS;
    private final BiFunction<Integer, String, Result<Optional<SymbolRef>, ResolutionError>> lookupGlobal;

    public ModuleEnv(
            Namespace namespace,
            Function<List<String>, Optional<Namespace>> nsLookupFunc,
            BiFunction<Integer, String, Result<Optional<SymbolRef>, ResolutionError>>  globalLookupFunc
    ) {
        this.namespace = namespace;
        this.lookupNS = nsLookupFunc;
        this.lookupGlobal = globalLookupFunc;
        // Start with the global scope of the namespace
        scopeStack.add(0);
    }

    public Result<List<ASTNode>, CError> getExpressions(){
        return namespace.getCompModule().getRootExpressions();
    }

    public void enterScope() {
        scopeCounter++;
        scopeStack.add(scopeCounter);
    }

    public void exitScope() {
        scopeStack.removeAtIndex(scopeStack.size() - 1);
    }

    public int getCurrentScope() {
        return scopeStack.getLast();
    }

    public NsScope getCurrentNsScope() {
        return NsScope.of(namespace.id(), getCurrentScope());
    }

    public Result<Void, CError> define(String identifier, SymbolRef.SymbolData symbolData) {
        return namespace.symbolTable().define(getCurrentScope(), identifier, symbolData).castErr();
    }

    public Optional<SymbolRef> lookup(String identifier) {
        return namespace.symbolTable().lookup(scopeStack, identifier);
    }
}
