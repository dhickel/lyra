package lang.types;

import lang.LangType;

import java.util.*;

public class TypeTable {
    private final List<TypeEntry> typeTable;
    private final Map<LangType, TypeId> typeToIdMap;
    private final Map<String, TypeId> nameToIdMap;
    private final Set<LangType> resolvedTypes;
    
    // Pre-registered primitive types
    public static final TypeEntry NIL = TypeEntry.of(0, LangType.NIL);
    public static final TypeEntry I8 = TypeEntry.of(1, LangType.I8);
    public static final TypeEntry I16 = TypeEntry.of(2, LangType.I16);
    public static final TypeEntry I32 = TypeEntry.of(3, LangType.I32);
    public static final TypeEntry I64 = TypeEntry.of(4, LangType.I64);
    public static final TypeEntry F32 = TypeEntry.of(5, LangType.F32);
    public static final TypeEntry F64 = TypeEntry.of(6, LangType.F64);
    public static final TypeEntry BOOL = TypeEntry.of(7, LangType.BOOL);
    
    public TypeTable() {
        this.typeTable = new ArrayList<>();
        this.typeToIdMap = new HashMap<>();
        this.nameToIdMap = new HashMap<>();
        this.resolvedTypes = new HashSet<>();
        initializePrimitiveTypes();
    }
    
    private void initializePrimitiveTypes() {
        List<TypeEntry> primitives = List.of(NIL, I8, I16, I32, I64, F32, F64, BOOL);
        
        for (TypeEntry entry : primitives) {
            typeTable.add(entry);
            typeToIdMap.put(entry.type(), entry.id());
            resolvedTypes.add(entry.type());
        }
        
        // Add string type names
        nameToIdMap.put("Nil", NIL.id());
        nameToIdMap.put("I8", I8.id());
        nameToIdMap.put("I16", I16.id());
        nameToIdMap.put("I32", I32.id());
        nameToIdMap.put("I64", I64.id());
        nameToIdMap.put("F32", F32.id());
        nameToIdMap.put("F64", F64.id());
        nameToIdMap.put("Bool", BOOL.id());
    }
    
    public Optional<TypeEntry> getEntry(TypeId typeId) {
        int index = typeId.value();
        if (index < 0 || index >= typeTable.size()) {
            return Optional.empty();
        }
        return Optional.of(typeTable.get(index));
    }
    
    public Optional<TypeEntry> lookupByType(LangType type) {
        TypeId typeId = typeToIdMap.get(type);
        return typeId != null ? getEntry(typeId) : Optional.empty();
    }
    
    public Optional<TypeId> lookupByName(String name) {
        return Optional.ofNullable(nameToIdMap.get(name));
    }
    
    public boolean isResolved(LangType type) {
        return resolvedTypes.contains(type);
    }
    
    public Optional<TypeEntry> resolveType(LangType type) {
        // Check if already resolved
        if (typeToIdMap.containsKey(type)) {
            return Optional.of(typeTable.get(typeToIdMap.get(type).value()));
        }
        
        return switch (type) {
            case LangType.Primitive prim -> 
                Optional.of(resolvePrimitiveType(prim));
                
            case LangType.Composite.Function funcType -> 
                resolveFunctionType(funcType);
                
            case LangType.Composite.Array arrayType -> 
                resolveArrayType(arrayType);
                
            case LangType.UserType userType -> 
                resolveUserType(userType);
                
            case LangType.Undefined undefined -> Optional.empty();
                
            case LangType.Composite.String str -> 
                Optional.of(resolveStringType());
                
            case LangType.Composite.Tuple tupleType -> 
                resolveTupleType(tupleType);
                
            case LangType.Composite.Quote quote -> 
                Optional.of(resolveQuoteType());
        };
    }
    
    private TypeEntry resolvePrimitiveType(LangType.Primitive prim) {
        return switch (prim) {
            case LangType.Primitive.I8 i8 -> I8;
            case LangType.Primitive.I16 i16 -> I16;
            case LangType.Primitive.I32 i32 -> I32;
            case LangType.Primitive.I64 i64 -> I64;
            case LangType.Primitive.F32 f32 -> F32;
            case LangType.Primitive.F64 f64 -> F64;
            case LangType.Primitive.Bool bool -> BOOL;
            case LangType.Primitive.Nil nil -> NIL;
        };
    }
    
    private Optional<TypeEntry> resolveFunctionType(LangType.Composite.Function funcType) {
        // Ensure all parameter types are resolved
        List<LangType> resolvedParams = new ArrayList<>();
        for (LangType paramType : funcType.parameters()) {
            Optional<TypeEntry> resolved = resolveType(paramType);
            if (resolved.isEmpty()) return Optional.empty();
            resolvedParams.add(resolved.get().type());
        }
        
        // Ensure return type is resolved
        Optional<TypeEntry> returnTypeEntry = resolveType(funcType.rtnType());
        if (returnTypeEntry.isEmpty()) return Optional.empty();
        
        // Create resolved function type
        LangType resolvedFuncType = LangType.ofFunction(resolvedParams, returnTypeEntry.get().type());
        
        // Check if this function type already exists
        if (typeToIdMap.containsKey(resolvedFuncType)) {
            return Optional.of(typeTable.get(typeToIdMap.get(resolvedFuncType).value()));
        }
        
        return Optional.of(registerNewType(resolvedFuncType));
    }
    
    private Optional<TypeEntry> resolveArrayType(LangType.Composite.Array arrayType) {
        // Ensure element type is resolved
        Optional<TypeEntry> elementTypeEntry = resolveType(arrayType.elementType());
        if (elementTypeEntry.isEmpty()) return Optional.empty();
        
        LangType resolvedArrayType = LangType.ofArray(elementTypeEntry.get().type());
        
        // Check if this array type already exists
        if (typeToIdMap.containsKey(resolvedArrayType)) {
            return Optional.of(typeTable.get(typeToIdMap.get(resolvedArrayType).value()));
        }
        
        return Optional.of(registerNewType(resolvedArrayType));
    }
    
    private Optional<TypeEntry> resolveTupleType(LangType.Composite.Tuple tupleType) {
        // Ensure all member types are resolved
        List<LangType> resolvedMembers = new ArrayList<>();
        for (LangType memberType : tupleType.memberTypes()) {
            Optional<TypeEntry> resolved = resolveType(memberType);
            if (resolved.isEmpty()) return Optional.empty();
            resolvedMembers.add(resolved.get().type());
        }
        
        LangType resolvedTupleType = LangType.ofTuple(resolvedMembers);
        
        // Check if this tuple type already exists
        if (typeToIdMap.containsKey(resolvedTupleType)) {
            return Optional.of(typeTable.get(typeToIdMap.get(resolvedTupleType).value()));
        }
        
        return Optional.of(registerNewType(resolvedTupleType));
    }
    
    private TypeEntry resolveStringType() {
        LangType stringType = new LangType.Composite.String();
        if (typeToIdMap.containsKey(stringType)) {
            return typeTable.get(typeToIdMap.get(stringType).value());
        }
        return registerNewType(stringType);
    }
    
    private TypeEntry resolveQuoteType() {
        LangType quoteType = new LangType.Composite.Quote();
        if (typeToIdMap.containsKey(quoteType)) {
            return typeTable.get(typeToIdMap.get(quoteType).value());
        }
        return registerNewType(quoteType);
    }
    
    private Optional<TypeEntry> resolveUserType(LangType.UserType userType) {
        // For now, user types are not resolved until they are defined
        return Optional.empty();
    }
    
    private TypeEntry registerNewType(LangType type) {
        TypeId newId = TypeId.of(typeTable.size());
        TypeEntry newEntry = TypeEntry.of(newId, type);
        
        typeTable.add(newEntry);
        typeToIdMap.put(type, newId);
        resolvedTypes.add(type);
        
        return newEntry;
    }
    
    public TypeCompatibility.Result checkCompatibility(TypeId sourceId, TypeId targetId) {
        if (sourceId.equals(targetId)) {
            return TypeCompatibility.Result.compatible(TypeConversion.none());
        }
        
        Optional<TypeEntry> sourceEntry = getEntry(sourceId);
        Optional<TypeEntry> targetEntry = getEntry(targetId);
        
        if (sourceEntry.isEmpty() || targetEntry.isEmpty()) {
            return TypeCompatibility.Result.incompatible();
        }
        
        return TypeCompatibility.check(sourceEntry.get().type(), targetEntry.get().type());
    }
    
    /**
     * Get the widest primitive type from a list of types for operation resolution
     */
    public Optional<LangType.Primitive> getWidestPrimitiveType(List<LangType> types) {
        return types.stream()
            .filter(t -> t instanceof LangType.Primitive)
            .map(t -> (LangType.Primitive) t)
            .filter(p -> TypeCompatibility.getPrecedence(p) > 1) // Exclude Nil and Bool
            .max(Comparator.comparing(TypeCompatibility::getPrecedence));
    }
}