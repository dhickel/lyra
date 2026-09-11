package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NominalTypeContractTest {
    @Test void seededRecursiveContractsRoundTripOnlyInTheirExactEnvironment() {
        int cases = Integer.getInteger("lyra.fuzz.cases", 80);
        for (long seed : new long[] { 101, 20260911 }) {
            var random = new java.util.Random(seed);
            for (int index = 0; index < cases; index++) {
                var nominal = type("Node" + index);
                boolean nil = random.nextBoolean();
                boolean mutable = random.nextBoolean();
                LyraType fieldType = nil ? nominal.nilable() : nominal;
                if (random.nextBoolean()) fieldType = ArrayType.of(fieldType);
                var member = new NominalSchema.Member("next", fieldType, true, mutable, false);
                var schema = new NominalSchema(nominal, NominalSchema.Kind.STRUCT, List.of(member), List.of(fieldType));
                var environment = new NominalTypeEnvironment(List.of(schema));
                String replay = "seed=" + seed + ", index=" + index;
                assertEquals(fieldType, LyraType.parse(fieldType.canonicalSpelling(), environment), replay);
                assertEquals(mutable, environment.require(nominal).members().getFirst().mutable(), replay);
                assertFalse(nominal.isMutable(), replay);
                assertThrows(IllegalArgumentException.class, () -> LyraType.parse(
                        type("Other" + nominal.id().name()).canonicalSpelling(), environment), replay);
            }
        }
    }

    private static NominalType type(String name) {
        return new NominalType(new NominalTypeId(ModuleId.path("types.lyra"), "a".repeat(64), name, 0));
    }

    @Test void nominalIdentityAndRecursiveParsingRequireClosedSchemas() {
        var node = type("Node");
        var next = new NominalSchema.Member("next", node.nilable(), true, true, false);
        var schema = new NominalSchema(node, NominalSchema.Kind.STRUCT, List.of(next), List.of(node.nilable()));
        var environment = new NominalTypeEnvironment(List.of(schema));
        assertEquals("LYRA-NOMINAL-TYPE-ID/1;15:path:types.lyra64:" + "a".repeat(64) + "4:Node1:0",
                node.id().canonicalInput());
        assertEquals("1281bb141a43aedd9db4b5653dcae22ec5067cf647c7c45296b5141c74bf1884", node.id().stableHash());
        assertEquals(node, type("Node"));
        assertEquals(node.hashCode(), type("Node").hashCode());
        assertNotEquals(node, type("Other"));
        assertSame(node.canonicalSpelling(), node.canonicalSpelling());
        assertSame(node, LyraType.parse(node.canonicalSpelling(), environment));
        assertEquals(node.nilable(), LyraType.parse("@nil" + node, environment));
        var function = FunctionType.of(List.of(ArrayType.of(node)), node.nilable());
        assertEquals(function, LyraType.parse(function.canonicalSpelling(), environment));
        assertEquals(function.signature(), LyraSignature.parse(function.canonicalSpelling(), environment));
        assertThrows(IllegalArgumentException.class, () -> LyraType.parse(node.canonicalSpelling()));
        assertThrows(IllegalArgumentException.class, () -> LyraType.parse(type("Other").canonicalSpelling(), environment));
        for (String malformed : List.of("Nominal<abc>", "Nominal<" + "A".repeat(64) + ">",
                node + "extra", "@nil@nil" + node, "Nominal<" + node.id().stableHash() + "> ")) {
            assertThrows(IllegalArgumentException.class, () -> LyraType.parse(malformed, environment), malformed);
        }
        assertThrows(UnsupportedOperationException.class, () -> environment.schemas().clear());
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeEnvironment(List.of(schema, schema)));
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeEnvironment(List.of(
                new NominalSchema(type("Other"), NominalSchema.Kind.CLASS, List.of(next), List.of()))));
    }

    @Test void structDataRejectsNestedFunctionsButStopsAtClassReferences() {
        var callback = new NominalSchema.Member("callback", FunctionType.of(List.of(), LyraType.UNIT), false, true, true);
        var object = type("Object");
        var objectSchema = new NominalSchema(object, NominalSchema.Kind.CLASS, List.of(callback), List.of());
        var box = type("Box");
        var field = new NominalSchema.Member("object", object, true, true, false);
        var boxSchema = new NominalSchema(box, NominalSchema.Kind.STRUCT, List.of(field), List.of(object));
        assertEquals(2, new NominalTypeEnvironment(List.of(boxSchema, objectSchema)).schemas().size());
        var nested = new NominalSchema.Member("data", ArrayType.of(TupleType.of(List.of(callback.type()))), true, false, false);
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeEnvironment(List.of(
                new NominalSchema(box, NominalSchema.Kind.STRUCT, List.of(nested), List.of(nested.type())))));
        assertThrows(IllegalArgumentException.class, () -> new NominalSchema(box,
                NominalSchema.Kind.STRUCT, List.of(field), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NominalSchema.Member("bad-name", LyraType.I32, true, false, false));
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeId(ModuleId.path("types.lyra"), "bad", "Box", 0));
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeId(ModuleId.path("types.lyra"), "a".repeat(64), "box", 0));
        assertThrows(IllegalArgumentException.class, () -> new NominalTypeId(ModuleId.path("types.lyra"), "a".repeat(64), "Box", -1));
    }
}
