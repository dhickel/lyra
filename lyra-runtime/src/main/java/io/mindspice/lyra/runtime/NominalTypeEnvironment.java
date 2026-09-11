package io.mindspice.lyra.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Closed immutable schema graph. Unknown identities never become implicit types. */
public final class NominalTypeEnvironment {
    private static final NominalTypeEnvironment EMPTY = new NominalTypeEnvironment(List.of());
    private final Map<String, NominalSchema> schemas;

    public NominalTypeEnvironment(List<NominalSchema> definitions) {
        var collected = new TreeMap<String, NominalSchema>();
        for (NominalSchema schema : List.copyOf(definitions)) {
            if (collected.putIfAbsent(schema.type().canonicalSpelling(), schema) != null) {
                throw new IllegalArgumentException("duplicate nominal schema identity");
            }
        }
        schemas = java.util.Collections.unmodifiableMap(collected);
        for (NominalSchema schema : schemas.values()) {
            schema.members().forEach(member -> validateReferences(member.type()));
            schema.constructorParameters().forEach(this::validateReferences);
            if (schema.kind() == NominalSchema.Kind.STRUCT) validateData(schema.type(), new HashSet<>());
        }
    }

    public static NominalTypeEnvironment empty() { return EMPTY; }
    public List<NominalSchema> schemas() { return List.copyOf(schemas.values()); }

    public NominalSchema require(NominalType type) {
        NominalSchema schema = schemas.get(Objects.requireNonNull(type, "type").canonicalSpelling());
        if (schema == null || !schema.type().equals(type)) throw new IllegalArgumentException("unknown nominal identity: " + type);
        return schema;
    }

    NominalType resolve(String canonical) {
        NominalSchema schema = schemas.get(canonical);
        if (schema == null) throw new IllegalArgumentException("unknown nominal contract: " + canonical);
        return schema.type();
    }

    public void validateReferences(LyraType type) {
        switch (type.withoutQualifiers()) {
            case NominalType nominal -> require(nominal);
            case ArrayType array -> validateReferences(array.elementType());
            case TupleType tuple -> tuple.memberTypes().forEach(this::validateReferences);
            case FunctionType function -> {
                function.parameterTypes().forEach(this::validateReferences);
                validateReferences(function.returnType());
            }
            case PrimitiveType ignored -> { }
            case RangeType ignored -> { }
            case QualifiedType ignored -> throw new IllegalStateException("qualifiers not removed");
        }
    }

    private void validateData(LyraType type, Set<NominalTypeId> visited) {
        switch (type.withoutQualifiers()) {
            case FunctionType ignored -> throw new IllegalArgumentException("struct data cannot contain functions");
            case ArrayType array -> validateData(array.elementType(), visited);
            case TupleType tuple -> tuple.memberTypes().forEach(member -> validateData(member, visited));
            case NominalType nominal -> {
                NominalSchema schema = require(nominal);
                if (schema.kind() == NominalSchema.Kind.STRUCT && visited.add(nominal.id())) {
                    schema.members().forEach(member -> validateData(member.type(), visited));
                }
            }
            case PrimitiveType ignored -> { }
            case RangeType ignored -> { }
            case QualifiedType ignored -> throw new IllegalStateException("qualifiers not removed");
        }
    }
}
