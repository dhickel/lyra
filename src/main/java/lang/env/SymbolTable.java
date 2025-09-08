package lang.env;

import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import util.Result;
import util.exceptions.ResolutionError;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public abstract class SymbolTable {
    private final SymbolTable parent;

    protected SymbolTable(SymbolTable parent) { this.parent = parent; }

    abstract Result<Optional<Symbol>, ResolutionError> lookup(int scopeId, String identifier);

    abstract Result<Optional<Symbol>, ResolutionError> lookup(IntList scopeIds, String identifier);

    abstract Result<Void, ResolutionError> insert(int scopeId, Symbol symbol);

    public boolean hasParent() {
        return parent != null;
    }


    public static class MapTable extends SymbolTable {
        private final IntObjectHashMap<Map<String, Symbol>> table;

        public MapTable(SymbolTable parent, int initSize) {
            super(parent);
            table = new IntObjectHashMap<>(initSize);
        }

        @Override
        Result<Optional<Symbol>, ResolutionError> lookup(int scopeId, String identifier) {
            var innerMap = table.get(scopeId);

            return Result.ok(innerMap == null
                    ? (Optional.empty())
                    : (innerMap.get(identifier) instanceof Symbol s) ? Optional.of(s) : Optional.empty());

        }


        @Override
        Result<Void, ResolutionError> insert(int scopeId, Symbol symbol) {
            var innerMap = table.getIfAbsentPut(scopeId, new HashMap<>());
            return switch (innerMap.putIfAbsent(symbol.identifier(), symbol)) {
                case null -> Result.okVoid();
                case Symbol s -> Result.err(ResolutionError.duplicateSymbol(s));
            };
        }

        @Override
        Result<Optional<Symbol>, ResolutionError> lookup(IntList scopeIds, String identifier) {
            return null;
        }

    }


}
