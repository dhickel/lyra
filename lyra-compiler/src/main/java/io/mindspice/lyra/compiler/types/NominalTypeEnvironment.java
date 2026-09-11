package io.mindspice.lyra.compiler.types;

import io.mindspice.lyra.compiler.identity.NominalTypeId;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Closed schema graph. References must resolve exactly, including recursive references. */
public final class NominalTypeEnvironment {
    private final Map<NominalTypeId, NominalSchema> schemas;

    public NominalTypeEnvironment(List<NominalSchema> definitions) {
        var collected = new LinkedHashMap<NominalTypeId, NominalSchema>();
        for (NominalSchema definition : List.copyOf(definitions)) {
            if (collected.putIfAbsent(definition.type().id(), definition) != null) {
                throw new IllegalArgumentException("duplicate nominal schema identity: " + definition.type().id());
            }
        }
        schemas = Collections.unmodifiableMap(collected);
        for (NominalSchema schema : schemas.values()) {
            schema.members().forEach(member -> validateReferences(member.type()));
            schema.constructorParameters().forEach(this::validateReferences);
        }
        for (NominalSchema schema : schemas.values()) {
            if (schema.kind() == NominalSchema.Kind.STRUCT) {
                validateData(schema.type(), new HashSet<>());
            }
        }
    }

    public List<NominalSchema> schemas() { return List.copyOf(schemas.values()); }

    public NominalSchema require(NominalType type) {
        NominalSchema schema = schemas.get(Objects.requireNonNull(type, "type").id());
        if (schema == null) throw new IllegalArgumentException("unknown nominal schema: " + type);
        return schema;
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
            case QualifiedType ignored -> throw new IllegalStateException("qualifiers were not removed");
        }
    }

    private void validateData(LyraType type, Set<NominalTypeId> visited) {
        switch (type.withoutQualifiers()) {
            case FunctionType ignored -> throw new IllegalArgumentException("struct data cannot contain a function");
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
            case QualifiedType ignored -> throw new IllegalStateException("qualifiers were not removed");
        }
    }
}
