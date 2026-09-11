import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.identity.NominalTypeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NominalTypeTest {
    @Test
    void constructorFormulaRequiresExactNominalReturnAndRootTarget() {
        var nominal = type("Factory");
        var declaration = new io.mindspice.lyra.compiler.identity.DeclarationId(1);
        var root = io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.root();
        var signature = FunctionType.of(List.of(LyraType.I32), nominal);
        var formula = new io.mindspice.lyra.compiler.semantic.flow.ValueFormula.Constructor(
                declaration, nominal, signature, root);
        assertEquals(signature, formula.type());
        assertEquals(nominal, formula.nominalType());
        assertThrows(IllegalArgumentException.class, () ->
                new io.mindspice.lyra.compiler.semantic.flow.ValueFormula.Constructor(declaration,
                        nominal, FunctionType.of(List.of(), type("Other")), root));
        assertThrows(IllegalArgumentException.class, () ->
                new io.mindspice.lyra.compiler.semantic.flow.ValueFormula.Constructor(declaration,
                        nominal, signature, io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.tupleMember(0)));
    }

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
        assertSame(first.canonicalSpelling(), first.canonicalSpelling());
    }

    @Test
    void heapSnapshotsAndNominalRoutesKeepExactContracts() {
        var type = type("Cell");
        var field = new NominalSchema.Member("value", LyraType.I32, true, BindingMutability.MUTABLE, true);
        var schema = new NominalSchema(type, NominalSchema.Kind.CLASS, List.of(field), List.of());
        var value = io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives.singleton(
                io.mindspice.lyra.compiler.semantic.flow.ValueAlternative.scalar(LyraType.I32));
        var heap = new io.mindspice.lyra.compiler.semantic.flow.NominalObjectState(schema, java.util.Map.of(0, value), true);
        assertThrows(UnsupportedOperationException.class, () -> heap.fields().clear());
        assertThrows(IllegalArgumentException.class, () -> heap.write(1, value, true));
        assertThrows(IllegalArgumentException.class, () -> heap.write(0,
                io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives.empty(), true));
        var narrower = io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives.singleton(
                io.mindspice.lyra.compiler.semantic.flow.ValueAlternative.scalar(LyraType.I8));
        assertThrows(IllegalArgumentException.class, () -> heap.write(0, narrower, true));
        assertFalse(heap.repeatedAllocation().singleton());
        assertEquals(heap.fields(), heap.repeatedAllocation().fields());
        var route = io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.of(
                new io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.NominalMember(type, 0, LyraType.I32));
        assertEquals(LyraType.I32, io.mindspice.lyra.compiler.semantic.flow.ValueAlternative.typeAt(type, route));
        assertThrows(IllegalArgumentException.class, () ->
                io.mindspice.lyra.compiler.semantic.flow.ValueAlternative.typeAt(type("Other"), route));
        assertFalse(route.overlaps(io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.tupleMember(0)));
        assertFalse(route.overlaps(io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.unknownArrayElement()));
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
