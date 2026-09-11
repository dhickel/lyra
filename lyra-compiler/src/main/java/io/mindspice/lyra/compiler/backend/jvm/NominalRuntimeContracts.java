package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.types.*;
import java.util.HashMap;

/** Exact compiler-to-runtime schema projection; no canonical-name fallback or code execution. */
final class NominalRuntimeContracts {
    private final java.util.Map<io.mindspice.lyra.compiler.identity.NominalTypeId,
            io.mindspice.lyra.runtime.NominalType> identities = new HashMap<>();

    static io.mindspice.lyra.runtime.NominalTypeEnvironment from(TypedIr ir) {
        ir.requireValidated();
        var schemas = ir.semanticGraph().resolvedGraph().nominalTypes().schemas();
        if (schemas.isEmpty()) return io.mindspice.lyra.runtime.NominalTypeEnvironment.empty();
        var converter = new NominalRuntimeContracts();
        return new io.mindspice.lyra.runtime.NominalTypeEnvironment(schemas.stream().map(schema ->
                new io.mindspice.lyra.runtime.NominalSchema(converter.nominal(schema.type()),
                        schema.kind() == NominalSchema.Kind.STRUCT
                                ? io.mindspice.lyra.runtime.NominalSchema.Kind.STRUCT
                                : io.mindspice.lyra.runtime.NominalSchema.Kind.CLASS,
                        schema.members().stream().map(member -> new io.mindspice.lyra.runtime.NominalSchema.Member(
                                member.name(), converter.type(member.type()), member.publicAccess(),
                                member.mutability() == BindingMutability.MUTABLE, member.hasInitializer())).toList(),
                        schema.constructorParameters().stream().map(converter::type).toList())).toList());
    }

    private io.mindspice.lyra.runtime.NominalType nominal(NominalType type) {
        return identities.computeIfAbsent(type.id(), id -> new io.mindspice.lyra.runtime.NominalType(
                new io.mindspice.lyra.runtime.NominalTypeId(
                        io.mindspice.lyra.runtime.ModuleId.of(id.module().canonicalKey()),
                        id.revision(), id.name(), id.occurrence())));
    }

    private io.mindspice.lyra.runtime.LyraType type(LyraType type) {
        io.mindspice.lyra.runtime.LyraType projected = switch (type.withoutQualifiers()) {
            case PrimitiveType primitive -> io.mindspice.lyra.runtime.PrimitiveType.fromSpelling(primitive.canonicalSpelling()).orElseThrow();
            case NominalType nominal -> nominal(nominal);
            case ArrayType array -> io.mindspice.lyra.runtime.ArrayType.of(type(array.elementType()));
            case RangeType range -> io.mindspice.lyra.runtime.RangeType.of(type(range.elementType()));
            case TupleType tuple -> io.mindspice.lyra.runtime.TupleType.of(tuple.memberTypes().stream().map(this::type).toList());
            case FunctionType function -> io.mindspice.lyra.runtime.FunctionType.of(function.parameterTypes().stream()
                    .map(this::type).toList(), type(function.returnType()));
            case QualifiedType ignored -> throw new IllegalStateException("qualifiers not removed");
        };
        if (type.isMutable()) projected = projected.mutable();
        if (type.isNilable()) projected = projected.nilable();
        if (!projected.canonicalSpelling().equals(type.canonicalSpelling())) {
            throw new IllegalArgumentException("nominal runtime projection changed an exact type contract");
        }
        return projected;
    }
}
