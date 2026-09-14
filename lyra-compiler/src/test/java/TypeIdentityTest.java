import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.ExportIdentity;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.JavaNameMangler;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.ExactNumericLiteral;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.LiteralTyping;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.QualifiedType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypePosition;
import io.mindspice.lyra.compiler.types.TypeQualifier;
import io.mindspice.lyra.compiler.types.TypeRules;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Assertion-grade dependency-free tests for domain 7 types and identities. */
public final class TypeIdentityTest {
    @Test
    public void testPrimitiveUniverseAndCanonicalSpelling() {
        primitiveUniverseAndCanonicalSpelling();
    }

    @Test
    public void testCompositeTypesQualifiersAndImmutability() {
        compositeTypesQualifiersAndImmutability();
    }

    @Test
    public void testNumericLatticeAndCommonTypes() {
        numericLatticeAndCommonTypes();
    }

    @Test
    public void testConversionsAndInvariantComposites() {
        conversionsAndInvariantComposites();
    }

    @Test
    public void testExactLiteralTyping() {
        exactLiteralTyping();
    }

    @Test
    public void testCompilationIdsAreDeterministicAndImmutable() {
        compilationIdsAreDeterministicAndImmutable();
    }

    @Test
    public void testStableModuleAndExportIdentity() {
        stableModuleAndExportIdentity();
    }

    @Test
    public void testJavaNamePlanningIsDeterministic() {
        javaNamePlanningIsDeterministic();
    }

    private static void primitiveUniverseAndCanonicalSpelling() {
        List<PrimitiveType> primitives = List.of(PrimitiveType.values());
        check(primitives.size() == 14, "the current primitive universe has exactly 14 types");
        check(primitives.equals(List.of(
                        PrimitiveType.I8, PrimitiveType.I16, PrimitiveType.I32, PrimitiveType.I64,
                        PrimitiveType.U8, PrimitiveType.U16, PrimitiveType.U32, PrimitiveType.U64,
                        PrimitiveType.F32, PrimitiveType.F64, PrimitiveType.BOOL, PrimitiveType.CHAR,
                        PrimitiveType.STRING, PrimitiveType.UNIT)),
                "all signed, unsigned, floating, and scalar primitives are present");
        check(Arrays.stream(LyraType.class.getDeclaredFields()).findAny().isEmpty(),
                "LyraType declares no constant fields; the initialization-cycle alias fields are gone");
        check(Arrays.stream(PrimitiveType.class.getDeclaredFields())
                        .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                        .filter(field -> !field.isEnumConstant())
                        .allMatch(field -> field.isSynthetic() && field.getName().equals("$VALUES")),
                "PrimitiveType declares only its enum constants, with no readable alias constants");
        for (String alias : List.of("Bool", "Char", "String", "Unit")) {
            check(Arrays.stream(LyraType.class.getDeclaredFields())
                            .noneMatch(field -> field.getName().equals(alias))
                            && Arrays.stream(PrimitiveType.class.getDeclaredFields())
                            .noneMatch(field -> field.getName().equals(alias)),
                    "removed primitive alias " + alias + " is absent from LyraType and PrimitiveType");
        }
        check(PrimitiveType.fromSpelling("Bool").orElseThrow() == PrimitiveType.BOOL,
                "primitive lookup uses source spelling");
        check(PrimitiveType.I32.canonicalSpelling().equals("I32")
                        && PrimitiveType.STRING.toString().equals("String"),
                "primitive spellings are whitespace-free");
        check(PrimitiveType.U64.numericDomain().orElseThrow().maximum()
                        .orElseThrow().equals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)),
                "unsigned domains expose mathematical bounds");
        check(PrimitiveType.I64.numericDomain().orElseThrow().minimum()
                        .orElseThrow().equals(BigInteger.ONE.shiftLeft(63).negate()),
                "signed domains expose mathematical bounds");
    }

    private static void compositeTypesQualifiersAndImmutability() {
        LyraType nilString = QualifiedType.of(PrimitiveType.STRING, TypeQualifier.NIL);
        LyraType both = QualifiedType.of(
                PrimitiveType.I32, TypeQualifier.NIL, TypeQualifier.MUT);
        check(both.canonicalSpelling().equals("@mut@nilI32"),
                "qualifiers have canonical @mut then @nil no-whitespace order");
        check(both.equals(QualifiedType.of(
                        PrimitiveType.I32, Set.of(TypeQualifier.MUT, TypeQualifier.NIL))),
                "qualified types compare structurally independent of set iteration order");
        expectThrows(IllegalArgumentException.class,
                () -> QualifiedType.of(PrimitiveType.I32, TypeQualifier.NIL, TypeQualifier.NIL));

        ArrayType array = ArrayType.of(PrimitiveType.I32);
        TupleType tuple = TupleType.of(List.of(PrimitiveType.I8, nilString, array));
        FunctionType function = FunctionType.of(
                List.of(QualifiedType.of(array, TypeQualifier.MUT), nilString),
                QualifiedType.of(PrimitiveType.I32, TypeQualifier.NIL));
        check(array.canonicalSpelling().equals("Array<I32>"), "array spelling");
        check(tuple.canonicalSpelling().equals("Tuple<I8,@nilString,Array<I32>>"),
                "tuple spelling preserves ordered member contracts");
        check(function.canonicalSpelling().equals("Fn<@mutArray<I32>,@nilString;@nilI32>"),
                "function spelling preserves qualified parameters and return");
        for (PrimitiveType primitive : PrimitiveType.values()) {
            check(QualifiedType.nilable(primitive).isNilable()
                            && QualifiedType.nilable(primitive).baseType().equals(primitive),
                    "@nil is legal on every primitive value contract: " + primitive);
        }
        check(QualifiedType.nilable(array).canonicalSpelling().equals("@nilArray<I32>"),
                "@nil is legal on an array contract");
        check(QualifiedType.nilable(tuple).canonicalSpelling().startsWith("@nilTuple<"),
                "@nil is legal on a tuple contract");
        check(QualifiedType.nilable(function).canonicalSpelling().startsWith("@nilFn<"),
                "@nil is legal on a function contract");
        check(TypePosition.BINDING.permitsMutableQualifier()
                        && TypePosition.PARAMETER.permitsMutableQualifier()
                        && !TypePosition.RETURN.permitsMutableQualifier()
                        && !TypePosition.NESTED_VALUE.permitsMutableQualifier(),
                "type positions encode qualifier legality");
        check(LyraSignature.from(function).canonicalSpelling().equals(function.canonicalSpelling()),
                "semantic signature encoding matches its function type without a JVM descriptor");
        check(FunctionType.of(List.of(), PrimitiveType.UNIT).canonicalSpelling().equals("Fn<;Unit>"),
                "zero-parameter signatures retain the semicolon");

        ArrayList<LyraType> members = new ArrayList<>(List.of(PrimitiveType.I8));
        TupleType copied = TupleType.of(members);
        members.clear();
        check(copied.memberTypes().size() == 1, "tuple construction defensively copies members");
        expectThrows(UnsupportedOperationException.class, () -> copied.memberTypes().clear());
        expectThrows(IllegalArgumentException.class,
                () -> ArrayType.of(QualifiedType.mutable(PrimitiveType.I32)));
        expectThrows(IllegalArgumentException.class,
                () -> TupleType.of(List.of(QualifiedType.mutable(PrimitiveType.I32))));
        expectThrows(IllegalArgumentException.class, () -> TupleType.of(List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> FunctionType.of(List.of(), QualifiedType.mutable(PrimitiveType.UNIT)));

        BindingContract binding = BindingContract.mutable(nilString);
        check(binding.mutability() == BindingMutability.MUTABLE
                        && !binding.valueType().isMutable()
                        && binding.canonicalSpelling().equals("@mut@nilString"),
                "binding mutability is retained separately from the qualified value type");
        QualifiedType mutableArray = QualifiedType.of(array, TypeQualifier.MUT);
        mutableArray.validateFor(TypePosition.BINDING);
        check(mutableArray.isMutable(), "mutable contracts are legal at binding positions");
    }

    private static void numericLatticeAndCommonTypes() {
        assertWiden(PrimitiveType.I8, PrimitiveType.I16, true);
        assertWiden(PrimitiveType.I16, PrimitiveType.I32, true);
        assertWiden(PrimitiveType.I32, PrimitiveType.I64, true);
        assertWiden(PrimitiveType.U8, PrimitiveType.U16, true);
        assertWiden(PrimitiveType.U16, PrimitiveType.U32, true);
        assertWiden(PrimitiveType.U32, PrimitiveType.U64, true);
        assertWiden(PrimitiveType.U8, PrimitiveType.I16, true);
        assertWiden(PrimitiveType.U8, PrimitiveType.I32, true);
        assertWiden(PrimitiveType.U8, PrimitiveType.I64, true);
        assertWiden(PrimitiveType.U16, PrimitiveType.I32, true);
        assertWiden(PrimitiveType.U16, PrimitiveType.I64, true);
        assertWiden(PrimitiveType.U32, PrimitiveType.I64, true);
        assertWiden(PrimitiveType.U64, PrimitiveType.I64, false);
        assertWiden(PrimitiveType.I8, PrimitiveType.U8, false);
        assertWiden(PrimitiveType.U8, PrimitiveType.I8, false);
        assertWiden(PrimitiveType.I16, PrimitiveType.F32, true);
        assertWiden(PrimitiveType.U16, PrimitiveType.F32, true);
        assertWiden(PrimitiveType.I32, PrimitiveType.F32, false);
        assertWiden(PrimitiveType.U32, PrimitiveType.F32, false);
        assertWiden(PrimitiveType.I32, PrimitiveType.F64, true);
        assertWiden(PrimitiveType.U32, PrimitiveType.F64, true);
        assertWiden(PrimitiveType.I64, PrimitiveType.F64, false);
        assertWiden(PrimitiveType.U64, PrimitiveType.F64, false);
        assertWiden(PrimitiveType.F32, PrimitiveType.F64, true);
        assertWiden(PrimitiveType.F64, PrimitiveType.F32, false);

        check(TypeRules.commonNumericType(List.of(PrimitiveType.I8, PrimitiveType.U8))
                        .orElseThrow().equals(PrimitiveType.I16),
                "I8 and U8 choose the least signed containing domain");
        check(TypeRules.commonNumericType(List.of(PrimitiveType.I16, PrimitiveType.U16))
                        .orElseThrow().equals(PrimitiveType.I32),
                "I16 and U16 choose I32");
        check(TypeRules.commonNumericType(List.of(PrimitiveType.I32, PrimitiveType.U32))
                        .orElseThrow().equals(PrimitiveType.I64),
                "I32 and U32 choose I64");
        check(TypeRules.commonNumericType(List.of(PrimitiveType.I64, PrimitiveType.U64)).isEmpty(),
                "I64 and U64 have no implicit common type");
        check(TypeRules.commonNumericType(List.of(PrimitiveType.I16, PrimitiveType.F32))
                        .orElseThrow().equals(PrimitiveType.F32),
                "small integers and F32 choose F32");
        check(TypeRules.commonNumericType(List.of(PrimitiveType.I32, PrimitiveType.F32))
                        .orElseThrow().equals(PrimitiveType.F64),
                "I32 and F32 choose F64 through only lossless edges");
        check(TypeRules.commonNumericType(List.of(PrimitiveType.I64, PrimitiveType.F32)).isEmpty(),
                "I64 and F32 do not invent an implicit float conversion");
        check(TypeRules.commonType(List.of(
                        PrimitiveType.STRING, QualifiedType.nilable(PrimitiveType.STRING)))
                        .orElseThrow().equals(QualifiedType.nilable(PrimitiveType.STRING)),
                "common nonnumeric types join only nilability");
    }

    private static void conversionsAndInvariantComposites() {
        check(TypeRules.canImplicitlyConvert(PrimitiveType.I8, PrimitiveType.I64),
                "lossless integer widening is implicit");
        check(TypeRules.canImplicitlyConvert(PrimitiveType.I8,
                        QualifiedType.nilable(PrimitiveType.I64)),
                "a non-nil numeric may lift and widen to a nilable target");
        check(!TypeRules.canImplicitlyConvert(
                        QualifiedType.nilable(PrimitiveType.I8), PrimitiveType.I64),
                "nilability cannot be implicitly removed");
        check(!TypeRules.canImplicitlyConvert(
                        PrimitiveType.I8, QualifiedType.mutable(PrimitiveType.I64)),
                "mutation permission cannot be invented by conversion");
        check(TypeRules.implicitConversion(PrimitiveType.I8, PrimitiveType.I64).steps()
                        .equals(List.of(ConversionStep.NUMERIC_WIDENING)),
                "implicit conversion records its semantic operation");
        check(TypeRules.implicitConversion(PrimitiveType.I8,
                        QualifiedType.nilable(PrimitiveType.I64)).steps().equals(List.of(
                                ConversionStep.NIL_LIFT, ConversionStep.NUMERIC_WIDENING)),
                "combined nil lift and numeric widening are explicit in the decision");

        check(!TypeRules.canImplicitlyConvert(
                        ArrayType.of(PrimitiveType.I8), ArrayType.of(PrimitiveType.I16)),
                "arrays are invariant");
        check(!TypeRules.canImplicitlyConvert(
                        TupleType.of(List.of(PrimitiveType.I8)),
                        TupleType.of(List.of(PrimitiveType.I16))),
                "tuples are invariant");
        check(!TypeRules.canImplicitlyConvert(
                        FunctionType.of(List.of(PrimitiveType.I8), PrimitiveType.I16),
                        FunctionType.of(List.of(PrimitiveType.I16), PrimitiveType.I16)),
                "function contracts are invariant");

        var narrowing = TypeRules.explicitConversion(PrimitiveType.I64, PrimitiveType.I8);
        check(narrowing.kind() == ConversionKind.EXPLICIT
                        && narrowing.isExplicit()
                        && narrowing.steps().equals(List.of(ConversionStep.NUMERIC_EXPLICIT)),
                "narrowing is explicit and not silently widened");
        check(TypeRules.canExplicitlyConvert(PrimitiveType.U64, PrimitiveType.I64),
                "signed/unsigned conversion is explicit even without implicit containment");
        check(TypeRules.canExplicitlyConvert(PrimitiveType.I32, PrimitiveType.STRING),
                "primitive-to-string conversion is explicit");
        check(TypeRules.canExplicitlyConvert(PrimitiveType.UNIT, PrimitiveType.STRING),
                "Unit-to-string conversion is explicit");
        check(!TypeRules.canExplicitlyConvert(
                        QualifiedType.nilable(PrimitiveType.I32), PrimitiveType.STRING),
                "nilable values must be narrowed before scalar text conversion");
        check(TypeRules.classify(PrimitiveType.I32, PrimitiveType.I32).isIdentity(),
                "exact contracts classify as identity");
    }

    private static void exactLiteralTyping() {
        ExactNumericLiteral unsuffixedInteger = ExactNumericLiteral.integer(BigInteger.valueOf(42));
        check(LiteralTyping.infer(unsuffixedInteger).orElseThrow() == PrimitiveType.I64,
                "unsuffixed integers default to I64");
        check(LiteralTyping.infer(unsuffixedInteger, PrimitiveType.I16).orElseThrow()
                        == PrimitiveType.I16,
                "unsuffixed integers adopt a representable expected type");
        check(LiteralTyping.infer(unsuffixedInteger, QualifiedType.nilable(PrimitiveType.I16))
                        .orElseThrow() == PrimitiveType.I16,
                "literal contextual typing unwraps a nilable expected contract");
        check(LiteralTyping.infer(
                        ExactNumericLiteral.integer(BigInteger.valueOf(42), PrimitiveType.I8),
                        PrimitiveType.I16).orElseThrow() == PrimitiveType.I8,
                "numeric suffixes force the literal primitive type");
        check(LiteralTyping.infer(
                        ExactNumericLiteral.integer(BigInteger.valueOf(42), PrimitiveType.I8),
                        PrimitiveType.U8).isEmpty(),
                "a forced signed literal is not silently changed to unsigned");
        check(LiteralTyping.infer(ExactNumericLiteral.integer(BigInteger.ONE.shiftLeft(63)))
                        .isEmpty(),
                "an unsuffixed integer outside I64 is rejected rather than defaulted dynamically");
        check(LiteralTyping.representableAs(
                        ExactNumericLiteral.integer(BigInteger.valueOf(16_777_216)), PrimitiveType.F32),
                "the largest exact consecutive F32 integer boundary is representable");
        check(!LiteralTyping.representableAs(
                        ExactNumericLiteral.integer(BigInteger.valueOf(16_777_217)), PrimitiveType.F32),
                "an integer that would round in F32 is not contextually exact");
        check(LiteralTyping.infer(
                        ExactNumericLiteral.decimal(new BigDecimal("0.1"), PrimitiveType.F32))
                        .orElseThrow() == PrimitiveType.F32,
                "finite decimal literals retain exact decimal input until float lowering");
        check(ExactNumericLiteral.integer(BigInteger.valueOf(7)).negated().integerValue()
                        .equals(BigInteger.valueOf(-7)),
                "literal negation preserves exact integer data");
        ExactNumericLiteral negativeZero = ExactNumericLiteral
                .decimal(new BigDecimal("0.0")).negated();
        check(negativeZero.isNegativeZero()
                        && negativeZero.canonicalSpelling().equals("-0.0"),
                "decimal negation preserves negative zero for deterministic text conversion");
        check(!negativeZero.negated().isNegativeZero(),
                "double negation restores positive zero");
    }

    private static void compilationIdsAreDeterministicAndImmutable() {
        IdentityAllocator initial = IdentityAllocator.initial();
        IdentityAllocator.Allocation<ScopeId> firstScope = initial.allocateScope();
        IdentityAllocator.Allocation<ScopeId> secondScope = firstScope.next().allocateScope();
        check(firstScope.id().equals(new ScopeId(0))
                        && secondScope.id().equals(new ScopeId(1)),
                "scope allocation is ordered and zero-based");
        check(initial.allocateScope().id().equals(firstScope.id()),
                "persistent allocation leaves the original snapshot unchanged");
        check(firstScope.next().allocateDeclaration().id().equals(new DeclarationId(0)),
                "all identity categories have independent deterministic sequences");
        check(firstScope.next().allocateReference().id().equals(new ReferenceId(0))
                        && firstScope.next().allocateLambda().id().equals(new LambdaId(0))
                        && firstScope.next().allocateCapture().id().equals(new CaptureId(0)),
                "reference, lambda, and capture IDs are allocated from the same graph snapshot");

        IdentityAllocator replay = IdentityAllocator.initial();
        check(replay.allocateScope().equals(initial.allocateScope()),
                "replaying the same allocation produces the same immutable result");
        check(new ScopeId(2).compareTo(new ScopeId(3)) < 0
                        && new ScopeId(4).toString().equals("scope#4"),
                "identity values have deterministic ordering and spelling");
        expectThrows(IllegalArgumentException.class, () -> new CaptureId(-1));
    }

    private static void stableModuleAndExportIdentity() {
        ModuleId moduleId = ModuleId.path("game/math.lyra");
        ModuleIdentity first = ModuleIdentity.from(new SourceSpan(moduleId.sourceId(), 0, 1));
        ModuleIdentity second = ModuleIdentity.from(new SourceSpan(moduleId.sourceId(), 100, 200));
        check(first.equals(second) && first.canonicalKey().equals("path:game/math.lyra"),
                "module identity excludes source line and offset data");
        check(!first.equals(ModuleIdentity.of(ModuleId.uri(URI.create("memory://game/math.lyra")))),
                "path and URI endpoint kinds remain distinct identities");
        check(ModuleIdentity.logicalKey(LogicalModuleId.parse("game->math"))
                        .equals("logical:game->math"),
                "logical module names remain separate import keys");

        LyraSignature signature = LyraSignature.of(
                List.of(QualifiedType.mutable(ArrayType.of(PrimitiveType.I32)),
                        QualifiedType.nilable(PrimitiveType.STRING)),
                QualifiedType.nilable(PrimitiveType.I32));
        ExportId export = ExportId.from(
                new SourceSpan(moduleId.sourceId(), 1, 2), "transform", signature);
        ExportId sameExport = ExportId.from(
                new SourceSpan(moduleId.sourceId(), 999, 1000), "transform", signature);
        ExportId differentSignature = ExportId.of(
                moduleId, "transform", LyraSignature.of(List.of(PrimitiveType.I32), PrimitiveType.I32));
        ExportId differentModule = ExportId.of(
                ModuleId.path("other.lyra"), "transform", signature);
        check(export.equals(sameExport) && export.hash().equals(sameExport.hash()),
                "export identity is stable across declaration span changes");
        check(export.hash().length() == 64
                        && export.canonicalInput().contains(signature.canonicalSpelling()),
                "export identity contains a deterministic SHA-256 and complete signature input");
        check(!export.equals(differentSignature) && !export.equals(differentModule),
                "signature and module changes distinguish export identities");
        ExportIdentity immutable = ExportIdentity.immutable(export);
        ExportIdentity mutable = ExportIdentity.mutable(export);
        check(immutable.id().equals(mutable.id())
                        && immutable.bindingMutability() != mutable.bindingMutability(),
                "export binding mutability is separate from the export ID hash");
    }

    private static void javaNamePlanningIsDeterministic() {
        check(JavaNameMangler.mangle("value").equals("value"),
                "legal ASCII names are preserved");
        check(JavaNameMangler.mangle("class").equals("lyra$class"),
                "Java keywords receive the lyra prefix");
        check(JavaNameMangler.mangle("a-b").equals("a$u002Db"),
                "illegal name characters use deterministic UTF-16 escapes");
        check(JavaNameMangler.mangle("1value").equals("$u0031value"),
                "a leading digit is escaped into an identifier-safe name");
        for (String reserved : List.of(
                "close", "equals", "hashCode", "toString", "getClass", "clone", "finalize")) {
            check(JavaNameMangler.mangleInvocation(reserved)
                            .equals("invoke$" + JavaNameMangler.mangle(reserved)),
                    "facade invocation reserves inherited/infrastructure name " + reserved);
        }
        check(!JavaNameMangler.mangleInvocation("value").startsWith("invoke$"),
                "ordinary facade invocation names remain direct");

        LyraSignature signature = LyraSignature.of(List.of(PrimitiveType.I32), PrimitiveType.I32);
        ExportId first = ExportId.of(ModuleId.path("a.lyra"), "value", signature);
        ExportId second = ExportId.of(ModuleId.path("b.lyra"), "value", signature);
        JavaNameMangler.Plan plan = JavaNameMangler.plan(List.of(second, first));
        JavaNameMangler.Plan replay = JavaNameMangler.plan(List.of(first, second));
        check(plan.mappings().equals(replay.mappings()),
                "name planning is independent of input collection order");
        check(plan.mappings().size() == 2
                        && plan.mappings().values().stream().distinct().count() == 2,
                "same source names from distinct stable exports receive collision-free names");
        check(plan.names().values().stream().anyMatch("value"::equals)
                        && plan.names().values().stream().anyMatch(name -> name.startsWith("value$")),
                "the remaining collision receives a stable hash suffix");
        check(plan.invocationNames().values().stream().distinct().count() == 2,
                "collision-resolved exports retain distinct facade invocation names");
        expectThrows(UnsupportedOperationException.class, () -> plan.mappings().clear());
    }

    private static void assertWiden(PrimitiveType source, PrimitiveType target, boolean expected) {
        check(TypeRules.canImplicitlyWiden(source, target) == expected,
                source + " -> " + target + " widening rule");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> void expectThrows(Class<T> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError("expected " + expected.getName() + " but got "
                    + thrown.getClass().getName(), thrown);
        }
        throw new AssertionError("expected " + expected.getName());
    }
}
