package lang.env;

import lang.LangType;


import java.util.HashMap;
import java.util.Map;

public class TypeTable {
    private final Map<LangType, TypeRef> typeTable = new HashMap<>(30);


    public TypeTable() {
        LangType.allPrimitives.forEach(p -> typeTable.put(p, TypeRef.ofResolved(p)));
    }

    public TypeRef getStub(LangType langType) {
        typeTable.
    }
}
