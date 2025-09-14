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

public class ModuleEnv {
    private int scopeCounter = 0;
    private IntList scopeStack = new IntArrayList(20);
    private Namespace namespace;
    private Function<List<String>, Optional<Namespace>> lookupNS;
    private BiFunction<Integer, String, Result<Optional<SymbolRef>, ResolutionError>> lookupGlobal;

    public ModuleEnv(
            Namespace namespace,
            Function<List<String>, Optional<Namespace>> nsLookupFunc,
            BiFunction<Integer, String, Result<Optional<SymbolRef>, ResolutionError>>  globalLookupFunc
    ) {
        this.namespace = namespace;
        this.lookupNS = nsLookupFunc;
        this.lookupGlobal = globalLookupFunc;
    }

    public Result<List<ASTNode>, CError> getExpressions(){
        return namespace.getCompModule().getRootExpressions();
    }
}
