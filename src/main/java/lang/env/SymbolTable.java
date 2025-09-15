package lang.env;

import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import util.Result;
import util.exceptions.ResolutionError;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public interface SymbolTable {


    Result<Optional<SymbolRef>, ResolutionError> lookup(int scopeId, String identifier);

    Optional<SymbolRef> lookup(IntList scopeIds, String identifier);

    Result<Void, ResolutionError> define(int scopeId, String identifier, SymbolRef.SymbolData symbolData);

    Result<SymbolRef, ResolutionError> define(int scopeId, String identifier);


    public static class MapTable implements SymbolTable {
        private final IntObjectHashMap<Map<String, SymbolRef>> table;

        public MapTable(int initSize) {
            table = new IntObjectHashMap<>(initSize);
        }

        @Override
        public Result<Optional<SymbolRef>, ResolutionError> lookup(int scopeId, String identifier) {
            var innerMap = table.get(scopeId);

            return Result.ok(innerMap == null
                    ? (Optional.empty())
                    : (innerMap.get(identifier) instanceof SymbolRef s) ? Optional.of(s) : Optional.empty());
        }

        @Override
        public Optional<SymbolRef> lookup(IntList scopeIds, String identifier) {
            for (int i = scopeIds.size() - 1; i  >= 0 ; --i) {
                int id = scopeIds.get(i);
                var innerMap = table.get(id);
                if (innerMap == null) { continue; }

                var sRef = innerMap.get(identifier);
                if (sRef == null) { continue; }
                return Optional.of(sRef);
            }
            return Optional.empty();
        }

        @Override
        public Result<Void, ResolutionError> define(int scopeId, String identifier, SymbolRef.SymbolData symbolData) {
            var innerMap = table.getIfAbsentPut(scopeId, HashMap::new);
            SymbolRef existing = innerMap.get(identifier);

            if (existing == null) {
                innerMap.put(identifier, new SymbolRef(symbolData));
                return Result.okVoid();
            }

            return switch (existing.resolved()) {
                case true -> Result.err(ResolutionError.duplicateSymbol(existing));
                case false -> {
                    existing.resolve(symbolData);
                    yield Result.okVoid();
                }
            };
        }

        @Override
        public Result<SymbolRef, ResolutionError> define(int scopeId, String identifier) {
            var innerMap = table.getIfAbsentPut(scopeId, HashMap::new);
            if (innerMap.containsKey(identifier)) {
                return Result.err(ResolutionError.duplicateSymbol(innerMap.get(identifier)));
            }
            var newSym = SymbolRef.ofUnresolved();
            innerMap.put(identifier, newSym);
            return Result.ok(newSym);
        }

    }


}
