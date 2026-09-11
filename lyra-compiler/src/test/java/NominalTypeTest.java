import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.identity.NominalTypeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NominalTypeTest {
    private static NominalType type(String name) {
        return new NominalType(new NominalTypeId(ModuleIdentity.of(ModuleId.of("types.lyra")), "a".repeat(64), name, 0));
    }

    @Test
    void nominalContractsAreInvariantReferenceTypes() {
        var first = type("First");
        assertEquals(first, type("First"));
        assertEquals(first.hashCode(), type("First").hashCode());
        assertEquals("Nominal<" + first.id().stableHash() + ">", first.canonicalSpelling());
        assertFalse(first.isPrimitive());
        assertTrue(first.isComposite());
        assertFalse(first.isNumeric());
        assertFalse(first.isMutable());
        assertTrue(first.nilable().isNilable());
        assertEquals(first, first.mutable().withoutQualifiers());
        assertTrue(TypeRules.canImplicitlyConvert(first, first.nilable()));
        assertFalse(TypeRules.canImplicitlyConvert(first.nilable(), first));
        assertFalse(TypeRules.canImplicitlyConvert(first, type("Second")));
        assertFalse(TypeRules.canImplicitlyConvert(ArrayType.of(first), ArrayType.of(type("Second"))));
    }

    @Test
    void closedSchemasRejectUnknownOriginsDuplicateFieldsAndFunctionData() {
        var first = type("First");
        var other = type("Other");
        var field = new NominalSchema.Member("next", other.nilable(), true, BindingMutability.MUTABLE, false);
        var schema = new NominalSchema(first, NominalSchema.Kind.STRUCT, List.of(field), List.of(other.nilable()));
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeEnvironment(List.of(schema)));
        var empty = new NominalSchema(other, NominalSchema.Kind.STRUCT, List.of(), List.of());
        var env = new NominalTypeEnvironment(List.of(schema, empty));
        assertEquals(schema, env.require(first));
        assertThrows(UnsupportedOperationException.class, () -> env.schemas().clear());
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeEnvironment(List.of(empty, empty)));
        assertThrows(IllegalArgumentException.class, () -> new NominalSchema(first, NominalSchema.Kind.STRUCT,
                List.of(field, field), List.of(other.nilable(), other.nilable())));
        assertThrows(IllegalArgumentException.class, () -> new NominalSchema(first, NominalSchema.Kind.STRUCT,
                List.of(field), List.of()));
        var callable = new NominalSchema.Member("callback", ArrayType.of(FunctionType.of(List.of(), LyraType.UNIT)),
                true, BindingMutability.IMMUTABLE, false);
        var bad = new NominalSchema(other, NominalSchema.Kind.STRUCT, List.of(callable), List.of(callable.type()));
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeEnvironment(List.of(schema, bad)));
    }
}
