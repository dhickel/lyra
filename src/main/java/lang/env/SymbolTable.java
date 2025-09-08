package lang.env;

import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import util.Result;
import util.exceptions.ResolutionError;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public interface  SymbolTable {


     Result<Optional<Symbol>, ResolutionError> lookup(int scopeId, String identifier);

     Result<Optional<Symbol>, ResolutionError> lookup(IntList scopeIds, String identifier);

     Result<Void, ResolutionError> insert(int scopeId, Symbol symbol);



    public static class MapTable implements SymbolTable {
        private final IntObjectHashMap<Map<String, Symbol>> table;

        public MapTable( int initSize) {

            table = new IntObjectHashMap<>(initSize);
        }

        @Override
        public Result<Optional<Symbol>, ResolutionError> lookup(int scopeId, String identifier) {
            var innerMap = table.get(scopeId);

            return Result.ok(innerMap == null
                    ? (Optional.empty())
                    : (innerMap.get(identifier) instanceof Symbol s) ? Optional.of(s) : Optional.empty());

        }


        @Override
        public Result<Void, ResolutionError> insert(int scopeId, Symbol symbol) {
            var innerMap = table.getIfAbsentPut(scopeId, new HashMap<>());
            return switch (innerMap.putIfAbsent(symbol.identifier(), symbol)) {
                case null -> Result.okVoid();
                case Symbol s -> Result.err(ResolutionError.duplicateSymbol(s));
            };
        }

        @Override
        public Result<Optional<Symbol>, ResolutionError> lookup(IntList scopeIds, String identifier) {
            return null;
        }

    }


}
