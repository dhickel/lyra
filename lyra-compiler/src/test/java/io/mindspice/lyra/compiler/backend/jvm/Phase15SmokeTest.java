package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LineNumberTableAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase15SmokeTest {
    @Test
    void acceptsAggregateInputAfterPhase16() {
        assertEmissionSucceeds("let @pub values :Array<I32> = Array[1 2 3]");
        assertEmissionSucceeds("let @pub values :Tuple<I32,I32> = Tuple[1 2]");
        assertEmissionSucceeds("let @pub length :Fn<Array<I32>;I32> = "
                + "(=> |values| values:.length)");
    }

    private static void assertEmissionSucceeds(String source) {
        TypedIr ir = lower(source);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        PhaseResult<JvmBytecodeArtifact> result = JvmBytecodeEmitter.emitPhase(ir, plan);
        assertTrue(result.isSuccess());
        assertTrue(result.optionalValue().isPresent());
    }

    private static void assertUnsupportedEmission(String source) {
        TypedIr ir = lower(source);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        PhaseResult<JvmBytecodeArtifact> result = JvmBytecodeEmitter.emitPhase(ir, plan);
        assertTrue(result.isFailure());
        assertEquals("LYC-EMIT-001", result.diagnostics().getFirst().code().value());
        assertTrue(result.optionalValue().isEmpty());
    }

    @Test
    void acceptsSupportedRecursionShapesAfterPhase16() {
        assertEmissionSucceeds("let @pub recursive :Fn<I32;I32> = "
                + "(=> |n| { let value :I32 = ::recursive[(- n 1)] (+ value 1) })");
        assertEmissionSucceeds("let @pub recursive :Fn<I32;I32> = "
                + "(=> |n| (recursive (- n 1)))");
    }

    @Test
    void rejectsOversizedModifiedUtf8StringConstantsAsStructuredSourceFailures() {
        String literal = "\"" + "😀".repeat(10_923) + "\"";
        TypedIr ir = lower(literal);
        PhaseResult<JvmBytecodeArtifact> result = JvmBytecodeEmitter.emitPhase(
                ir, GeneratedTypePlanner.plan(ir));
        assertTrue(result.isFailure());
        assertEquals("LYC-EMIT-001", result.diagnostics().getFirst().code().value());
        assertTrue(result.diagnostics().getFirst().summary().contains("CONSTANT_Utf8"));
        assertEquals(ir.rootModule().body().forms().getFirst().span(),
                result.diagnostics().getFirst().primarySpan());
    }

    @Test
    void rejectsAPlanForDifferentValidatedIrAsStructuredInvalidPlan() {
        TypedIr ir = lower("let @pub answer :I32 = 42");
        GeneratedTypePlan wrong = GeneratedTypePlanner.plan(
                lower("let @pub answer :String = \"wrong\""));
        PhaseResult<JvmBytecodeArtifact> result = JvmBytecodeEmitter.emitPhase(ir, wrong);
        assertTrue(result.isFailure());
        assertEquals("LYC-EMIT-002", result.diagnostics().getFirst().code().value());
        assertTrue(result.optionalValue().isEmpty());
    }

    @Test
    void emitsCheckedArithmeticAcrossSignedUnsignedAndFloatingValues() throws Exception {
        TypedIr ir = lower("let @pub add :Fn<I32,I32;I32> = (=> |a b| (+ a b)) "
                + "let @pub subtract :Fn<I32,I32;I32> = (=> |a b| (- a b)) "
                + "let @pub remainder :Fn<I32,I32;I32> = (=> |a b| (% a b)) "
                + "let @pub divide :Fn<I32,I32;F64> = (=> |a b| (/ a b)) "
                + "let @pub unsignedAdd :Fn<U32,U32;U32> = (=> |a b| (+ a b)) "
                + "let @pub unsignedSubtract :Fn<U32,U32;U32> = (=> |a b| (- a b)) "
                + "let @pub float32Add :Fn<F32,F32;F32> = (=> |a b| (+ a b)) "
                + "let @pub floatAdd :Fn<F64,F64;F64> = (=> |a b| (+ a b))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(3, facade.getMethod("add", int.class, int.class).invoke(instance, 1, 2));
        assertEquals(-1, facade.getMethod("subtract", int.class, int.class).invoke(instance, 1, 2));
        assertEquals(1, facade.getMethod("remainder", int.class, int.class).invoke(instance, 7, 3));
        assertEquals(2.5d, facade.getMethod("divide", int.class, int.class).invoke(instance, 5, 2));
        assertEquals(3, facade.getMethod("unsignedAdd", int.class, int.class)
                .invoke(instance, 1, 2));
        assertEquals(0, facade.getMethod("unsignedSubtract", int.class, int.class)
                .invoke(instance, 1, 1));
        assertEquals(16_777_216.0f, facade.getMethod("float32Add", float.class, float.class)
                .invoke(instance, 16_777_216.0f, 1.0f));
        assertEquals(3.0d, facade.getMethod("floatAdd", double.class, double.class)
                .invoke(instance, 1.0d, 2.0d));
        assertRuntimeCode(() -> facade.getMethod("add", int.class, int.class)
                .invoke(instance, Integer.MAX_VALUE, 1), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("remainder", int.class, int.class)
                .invoke(instance, 1, 0), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("unsignedSubtract", int.class, int.class)
                .invoke(instance, 0, 1), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("float32Add", float.class, float.class)
                .invoke(instance, Float.MAX_VALUE, Float.MAX_VALUE), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("floatAdd", double.class, double.class)
                .invoke(instance, Double.MAX_VALUE, Double.MAX_VALUE), "LYR-ARITH");
        facade.getMethod("close").invoke(instance);
    }

    private static io.mindspice.lyra.runtime.LyraRuntimeException assertRuntimeCode(
            org.junit.jupiter.api.function.Executable executable, String code) {
        java.lang.reflect.InvocationTargetException failure = assertThrows(
                java.lang.reflect.InvocationTargetException.class, executable);
        io.mindspice.lyra.runtime.LyraRuntimeException runtimeFailure =
                (io.mindspice.lyra.runtime.LyraRuntimeException) failure.getCause();
        assertEquals(code, runtimeFailure.code());
        return runtimeFailure;
    }

    @Test
    void lowersDirectSelfTailCallsToAStackSafeLoop() throws Exception {
        TypedIr ir = lower("let @pub countdown :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : ::countdown[(- n 1)]))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(0, facade.getMethod("countdown", int.class).invoke(instance, 100_000));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void preservesSignedMinimumMagnitudeLiterals() throws Exception {
        TypedIr ir = lower("let @pub min32 :Fn<;I32> = (=> | | (- 2147483648I32)) "
                + "let @pub min64 :Fn<;I64> = (=> | | (- 9223372036854775808I64))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(Integer.MIN_VALUE, invokeFunction(facade, instance, "min32"));
        assertEquals(Long.MIN_VALUE, invokeFunction(facade, instance, "min64"));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsUnaryPowerAndRemainderOperations() throws Exception {
        TypedIr ir = lower("let @pub negate :Fn<I8;I8> = (=> |value| (- value)) "
                + "let @pub increment :Fn<U8;U8> = (=> |value| (++ value)) "
                + "let @pub decrement :Fn<I8;I8> = (=> |value| (-- value)) "
                + "let @pub power :Fn<I32,I32;I32> = (=> |base exponent| (^ base exponent)) "
                + "let @pub floatPower :Fn<F32,F32;F32> = (=> |base exponent| (^ base exponent)) "
                + "let @pub reciprocal :Fn<F64;F64> = (=> |value| (/ value))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals((byte) 1, facade.getMethod("negate", byte.class).invoke(instance, (byte) -1));
        assertEquals((byte) 1, facade.getMethod("increment", byte.class).invoke(instance, (byte) 0));
        assertEquals((byte) 0, facade.getMethod("decrement", byte.class).invoke(instance, (byte) 1));
        assertEquals(8, facade.getMethod("power", int.class, int.class).invoke(instance, 2, 3));
        assertEquals(1, facade.getMethod("power", int.class, int.class)
                .invoke(instance, 1, Integer.MAX_VALUE));
        assertEquals(8.0f, facade.getMethod("floatPower", float.class, float.class).invoke(instance, 2.0f, 3.0f));
        assertEquals(0.5d, facade.getMethod("reciprocal", double.class).invoke(instance, 2.0d));
        assertRuntimeCode(() -> facade.getMethod("negate", byte.class).invoke(instance, (byte) -128), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("increment", byte.class).invoke(instance, (byte) -1), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("power", int.class, int.class).invoke(instance, 2, -1), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("reciprocal", double.class).invoke(instance, 0.0d), "LYR-ARITH");
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void normalizesNarrowUnsignedOperandsBetweenVariadicSteps() throws Exception {
        TypedIr ir = lower("let @pub add :Fn<U8,U8,U8;U8> = (=> |a b c| (+ a b c))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertRuntimeCode(() -> facade.getMethod("add", byte.class, byte.class, byte.class)
                .invoke(instance, (byte) -1, (byte) 0, (byte) 1), "LYR-ARITH");
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsBranchesShortCircuitAndNilCoalescing() throws Exception {
        TypedIr ir = lower("let @pub choose :Fn<Bool;I32> = (=> |flag| (flag -> 1 : 2)) "
                + "let @pub coalesce :Fn<@nil I32;I32> = (=> |maybe| (maybe : 7)) "
                + "let @pub explode :Fn<I32;F64> = (=> |x| (/ 1 (- x x))) "
                + "let @pub shortAnd :Fn<;Bool> = (=> | | (and #F (explode 1))) "
                + "let @pub shortOr :Fn<;Bool> = (=> | | (or #T (explode 1)))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(1, facade.getMethod("choose", boolean.class).invoke(instance, true));
        assertEquals(2, facade.getMethod("choose", boolean.class).invoke(instance, false));
        assertEquals(7, facade.getMethod("coalesce", Integer.class)
                .invoke(instance, new Object[] {null}));
        assertEquals(0, facade.getMethod("coalesce", Integer.class).invoke(instance, 0));
        assertEquals(false, invokeFunction(facade, instance, "shortAnd"));
        assertEquals(true, invokeFunction(facade, instance, "shortOr"));
        assertRuntimeCode(() -> facade.getMethod("explode", int.class).invoke(instance, 1), "LYR-ARITH");
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void comparesNilablePrimitivePresenceBeforePayload() throws Exception {
        TypedIr ir = lower("let @pub equal :Fn<@nil I32;Bool> = (=> |maybe| (== maybe #NIL)) "
                + "let @pub unequal :Fn<@nil I32;Bool> = (=> |maybe| (!= maybe #NIL))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, facade.getMethod("equal", Integer.class)
                .invoke(instance, new Object[] {null}));
        assertEquals(false, facade.getMethod("unequal", Integer.class)
                .invoke(instance, new Object[] {null}));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void handlesNullablePrimitiveJavaParametersWithWrapperBoundaries() throws Exception {
        TypedIr ir = lower("let @pub orZero :Fn<@nil I32;I32> = (=> |value| (value : 5))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(5, facade.getMethod("orZero", Integer.class).invoke(instance, new Object[] {null}));
        assertEquals(0, facade.getMethod("orZero", Integer.class).invoke(instance, Integer.valueOf(0)));
        assertEquals(2, facade.getMethod("orZero", Integer.class).invoke(instance, Integer.valueOf(2)));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsNullableStatePresenceSeparatelyFromItsPayload() throws Exception {
        TypedIr ir = lower("let @pub absent :@nil I32 = #NIL "
                + "let @pub zero :@nil I32 = 0");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(null, facade.getMethod("get$absent").invoke(instance));
        assertEquals(Integer.valueOf(0), facade.getMethod("get$zero").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsUnitAndNarrowUnsignedValueEquality() throws Exception {
        TypedIr ir = lower("let @pub unitEqual :Fn<;Bool> = (=> | | (== () ())) "
                + "let @pub unitUnequal :Fn<;Bool> = (=> | | (!= () ())) "
                + "let @pub u8Equal :Fn<U8;Bool> = (=> |value| (== value 255U8))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, invokeFunction(facade, instance, "unitEqual"));
        assertEquals(false, invokeFunction(facade, instance, "unitUnequal"));
        assertEquals(true, facade.getMethod("u8Equal", byte.class).invoke(instance, (byte) -1));
        assertEquals(false, facade.getMethod("u8Equal", byte.class).invoke(instance, (byte) 254));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsPredicateNarrowingAndThenOnlyUnitBranches() throws Exception {
        TypedIr ir = lower("let @pub select :Fn<@nil I32;I32> = "
                + "(=> |maybe| (maybe value -> (+ value 1) : 3)) "
                + "let @pub thenOnly :Fn<I32;Unit> = (=> |value| (value -> ()))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(3, facade.getMethod("select", Integer.class)
                .invoke(instance, new Object[] {null}));
        assertEquals(3, facade.getMethod("select", Integer.class).invoke(instance, 0));
        assertEquals(2, facade.getMethod("select", Integer.class).invoke(instance, 1));
        facade.getMethod("thenOnly", int.class).invoke(instance, 0);
        facade.getMethod("thenOnly", int.class).invoke(instance, 1);
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void preservesStrictLeftToRightFailureOrder() throws Exception {
        TypedIr ir = lower("let @pub ordered :Fn<I64,I64;I8> = "
                + "(=> |left right| (+ I8[left] I8[right]))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        io.mindspice.lyra.runtime.LyraRuntimeException failure =
                assertRuntimeCode(() -> facade.getMethod("ordered", long.class, long.class)
                        .invoke(instance, 128L, 129L), "LYR-CONVERT");
        assertSameSpan(ir.failureSites().stream()
                        .filter(site -> site.checkKind()
                                == io.mindspice.lyra.compiler.ir.IrCheckKind.EXPLICIT_CONVERSION)
                        .min(Comparator.comparingInt(site -> site.span().startOffset()))
                        .orElseThrow().span(),
                failure.frames().getFirst().span());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsReferenceValuesInSharedMutableCells() {
        assertEmissionSucceeds("let @mut text :String = \"a\" "
                + "let @pub append :Fn<;String> = (=> | | { text := (+ text \"b\") text })");
    }

    @Test
    void emitsLocalRebindingWithoutLeakingSharedCells() throws Exception {
        TypedIr ir = lower("let @pub bump :Fn<I32;I32> = (=> |value| "
                + "{ let @mut local :I32 = value local := (+ local 1) local })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(42, facade.getMethod("bump", int.class).invoke(instance, 41));
        facade.getMethod("close").invoke(instance);
    }

    private static Object invokeFunction(Class<?> facade, Object instance, String name,
                                         Object... arguments) throws Exception {
        Object closure = facade.getMethod("value$" + name).invoke(instance);
        java.lang.reflect.Method invoke = closure.getClass().getMethod("invoke");
        invoke.setAccessible(true);
        return invoke.invoke(closure, arguments);
    }

    @Test
    void emitsCheckedExplicitNumericConversions() throws Exception {
        TypedIr ir = lower("let @pub widen :Fn<I32;I64> = (=> |value| I64[value]) "
                + "let @pub narrow :Fn<I64;I8> = (=> |value| I8[value]) "
                + "let @pub floatToInt :Fn<F64;I32> = (=> |value| I32[value]) "
                + "let @pub doubleToFloat :Fn<F64;F32> = (=> |value| F32[value]) "
                + "let @pub floatToI64 :Fn<F64;I64> = (=> |value| I64[value]) "
                + "let @pub floatToU64 :Fn<F64;U64> = (=> |value| U64[value]) "
                + "let @pub unsignedToText :Fn<U32;String> = (=> |value| String[value])");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(42L, facade.getMethod("widen", int.class).invoke(instance, 42));
        assertEquals((byte) 127, facade.getMethod("narrow", long.class).invoke(instance, 127L));
        assertEquals(3, facade.getMethod("floatToInt", double.class).invoke(instance, 3.0d));
        assertEquals(1.25f, facade.getMethod("doubleToFloat", double.class).invoke(instance, 1.25d));
        assertEquals(9223372036854774784L,
                facade.getMethod("floatToI64", double.class).invoke(instance, 9223372036854774784.0d));
        assertEquals(Long.MIN_VALUE,
                facade.getMethod("floatToU64", double.class).invoke(instance, 0x1.0p63));
        assertEquals("4294967295", facade.getMethod("unsignedToText", int.class).invoke(instance, -1));
        assertRuntimeCode(() -> facade.getMethod("narrow", long.class).invoke(instance, 128L), "LYR-CONVERT");
        assertRuntimeCode(() -> facade.getMethod("floatToInt", double.class).invoke(instance, 3.5d), "LYR-CONVERT");
        assertRuntimeCode(() -> facade.getMethod("floatToInt", double.class)
                .invoke(instance, Double.POSITIVE_INFINITY), "LYR-CONVERT");
        assertRuntimeCode(() -> facade.getMethod("doubleToFloat", double.class)
                .invoke(instance, Double.MAX_VALUE), "LYR-CONVERT");
        assertRuntimeCode(() -> facade.getMethod("floatToI64", double.class)
                .invoke(instance, 0x1.0p63), "LYR-CONVERT");
        assertRuntimeCode(() -> facade.getMethod("floatToU64", double.class)
                .invoke(instance, 0x1.0p64), "LYR-CONVERT");
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsLexicalLocalDeclarationsAndStrictBlockSequencing() throws Exception {
        TypedIr ir = lower("let @pub calculate :Fn<I32;I32> = "
                + "(=> |value| { let first :I32 = (+ value 1) let second :I32 = (+ first 1) second })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(43, facade.getMethod("calculate", int.class).invoke(instance, 41));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsExplicitScalarTextConversions() throws Exception {
        TypedIr ir = lower("let @pub i8Text :Fn<I8;String> = (=> |value| String[value]) "
                + "let @pub u32Text :Fn<U32;String> = (=> |value| String[value]) "
                + "let @pub f64Text :Fn<F64;String> = (=> |value| String[value]) "
                + "let @pub boolText :Fn<Bool;String> = (=> |value| String[value]) "
                + "let @pub charText :Fn<Char;String> = (=> |value| String[value]) "
                + "let @pub unitText :Fn<;String> = (=> | | String[()]) "
                + "let @pub length :Fn<String;I32> = (=> |text| text:.length) "
                + "let @pub join :Fn<String,String;String> = (=> |left right| (+ left right))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals("-1", facade.getMethod("i8Text", byte.class).invoke(instance, (byte) -1));
        assertEquals("4294967295", facade.getMethod("u32Text", int.class).invoke(instance, -1));
        assertEquals("1.25", facade.getMethod("f64Text", double.class).invoke(instance, 1.25d));
        assertEquals("1.0e21", facade.getMethod("f64Text", double.class).invoke(instance, 1.0e21));
        assertEquals("-0.0", facade.getMethod("f64Text", double.class).invoke(instance, -0.0d));
        assertEquals("#T", facade.getMethod("boolText", boolean.class).invoke(instance, true));
        assertEquals("x", facade.getMethod("charText", char.class).invoke(instance, 'x'));
        assertEquals("()", invokeFunction(facade, instance, "unitText"));
        assertEquals(0, facade.getMethod("length", String.class).invoke(instance, ""));
        assertEquals(4, facade.getMethod("length", String.class).invoke(instance, "lyra"));
        assertEquals("ab", facade.getMethod("join", String.class, String.class)
                .invoke(instance, "a", "b"));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsScalarAbiConstantsAndNil() throws Exception {
        TypedIr ir = lower("let @pub i8 :I8 = 1I8 "
                + "let @pub i16 :I16 = 2I16 "
                + "let @pub i32 :I32 = 3I32 "
                + "let @pub i64 :I64 = 4I64 "
                + "let @pub u32 :U32 = 4294967295U32 "
                + "let @pub f32 :F32 = 1.5F32 "
                + "let @pub f64 :F64 = 2.5F64 "
                + "let @pub flag :Bool = #T "
                + "let @pub ch :Char = 'x' "
                + "let @pub text :String = \"ok\" "
                + "let @pub unit :Unit = () "
                + "let @pub maybe :@nil I32 = #NIL "
                + "let @pub nilText :@nil String = #NIL "
                + "let @pub nilUnit :@nil Unit = #NIL");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        assertEquals("()L" + plan.moduleFacades().get(ir.rootModule().moduleId()).replace('.', '/') + ";",
                plan.classPlan(plan.moduleFacades().get(ir.rootModule().moduleId())).orElseThrow()
                        .members().stream().filter(m -> m.kind() == GeneratedMemberKind.FACTORY)
                        .findFirst().orElseThrow().descriptor());
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals((byte) 1, facade.getMethod("get$i8").invoke(instance));
        assertEquals((short) 2, facade.getMethod("get$i16").invoke(instance));
        assertEquals(3, facade.getMethod("get$i32").invoke(instance));
        assertEquals(4L, facade.getMethod("get$i64").invoke(instance));
        assertEquals(-1, facade.getMethod("get$u32").invoke(instance));
        assertEquals(1.5f, facade.getMethod("get$f32").invoke(instance));
        assertEquals(2.5d, facade.getMethod("get$f64").invoke(instance));
        assertEquals(true, facade.getMethod("get$flag").invoke(instance));
        assertEquals('x', facade.getMethod("get$ch").invoke(instance));
        assertEquals("ok", facade.getMethod("get$text").invoke(instance));
        assertEquals(io.mindspice.lyra.runtime.LyraUnit.INSTANCE, facade.getMethod("get$unit").invoke(instance));
        assertEquals(null, facade.getMethod("get$maybe").invoke(instance));
        assertEquals(null, facade.getMethod("get$nilText").invoke(instance));
        assertEquals(null, facade.getMethod("get$nilUnit").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsMutualRecursionAfterPhase16() {
        assertEmissionSucceeds("let @pub first :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : ::second[(- n 1)])) "
                + "let @pub second :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : ::first[(- n 1)]))");
    }

    @Test
    void emitsCapturingClosuresAfterPhase16() {
        assertEmissionSucceeds(
                "let @pub maker :Fn<I32;Fn<;I32>> = (=> |value| (=> | | value))");
    }

    @Test
    void emitsRelationalValueEqualityAndTruthiness() throws Exception {
        TypedIr ir = lower("let @pub less :Fn<I64,I64;Bool> = (=> |left right| (< left right)) "
                + "let @pub unsignedLess :Fn<U32,U32;Bool> = (=> |left right| (< left right)) "
                + "let @pub floatLess :Fn<F64,F64;Bool> = (=> |left right| (< left right)) "
                + "let @pub textEqual :Fn<String,String;Bool> = (=> |left right| (== left right)) "
                + "let @pub textUnequal :Fn<String,String;Bool> = (=> |left right| (!= left right)) "
                + "let @pub nullableTextTruth :Fn<@nil String;Bool> = "
                + "(=> |value| (value -> #T : #F)) "
                + "let @pub truth :Fn<I32;Bool> = (=> |value| (value -> #T : #F)) "
                + "let @pub negate :Fn<Bool;Bool> = (=> |value| (not value)) "
                + "let @pub exclusive :Fn<Bool,Bool;Bool> = (=> |left right| (xor left right))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, facade.getMethod("less", long.class, long.class).invoke(instance, -2L, 1L));
        assertEquals(false, facade.getMethod("unsignedLess", int.class, int.class).invoke(instance, -1, 1));
        assertEquals(true, facade.getMethod("floatLess", double.class, double.class).invoke(instance, 1.0d, 2.0d));
        assertEquals(true, facade.getMethod("textEqual", String.class, String.class).invoke(instance, "a", "a"));
        assertEquals(false, facade.getMethod("textEqual", String.class, String.class).invoke(instance, "a", "b"));
        assertEquals(false, facade.getMethod("textUnequal", String.class, String.class)
                .invoke(instance, "a", "a"));
        assertEquals(true, facade.getMethod("textUnequal", String.class, String.class)
                .invoke(instance, "a", "b"));
        assertEquals(false, facade.getMethod("nullableTextTruth", String.class)
                .invoke(instance, new Object[] {null}));
        assertEquals(false, facade.getMethod("nullableTextTruth", String.class).invoke(instance, ""));
        assertEquals(true, facade.getMethod("nullableTextTruth", String.class).invoke(instance, "x"));
        assertEquals(true, facade.getMethod("truth", int.class).invoke(instance, 1));
        assertEquals(false, facade.getMethod("truth", int.class).invoke(instance, 0));
        assertEquals(false, facade.getMethod("negate", boolean.class).invoke(instance, true));
        assertEquals(true, facade.getMethod("exclusive", boolean.class, boolean.class).invoke(instance, true, false));
        assertEquals(false, facade.getMethod("exclusive", boolean.class, boolean.class).invoke(instance, true, true));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void enforcesWideAndNarrowIntegerBoundaries() throws Exception {
        TypedIr ir = lower("let @pub i8Add :Fn<I8,I8;I8> = (=> |a b| (+ a b)) "
                + "let @pub u8Add :Fn<U8,U8;U8> = (=> |a b| (+ a b)) "
                + "let @pub i64Add :Fn<I64,I64;I64> = (=> |a b| (+ a b)) "
                + "let @pub i64Multiply :Fn<I64,I64;I64> = (=> |a b| (* a b)) "
                + "let @pub u64Add :Fn<U64,U64;U64> = (=> |a b| (+ a b)) "
                + "let @pub i64Divide :Fn<I64,I64;F64> = (=> |a b| (/ a b)) "
                + "let @pub u64Divide :Fn<U64,U64;F64> = (=> |a b| (/ a b)) "
                + "let @pub u32Divide :Fn<U32,U32;F64> = (=> |a b| (/ a b)) "
                + "let @pub u32Remainder :Fn<U32,U32;U32> = (=> |a b| (% a b)) "
                + "let @pub u64Remainder :Fn<U64,U64;U64> = (=> |a b| (% a b))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals((byte) 3, facade.getMethod("i8Add", byte.class, byte.class)
                .invoke(instance, (byte) 1, (byte) 2));
        assertEquals((byte) -2, facade.getMethod("u8Add", byte.class, byte.class)
                .invoke(instance, (byte) -2, (byte) 0));
        assertEquals(3L, facade.getMethod("i64Add", long.class, long.class).invoke(instance, 1L, 2L));
        assertEquals(6L, facade.getMethod("i64Multiply", long.class, long.class).invoke(instance, 2L, 3L));
        assertEquals(3L, facade.getMethod("u64Add", long.class, long.class).invoke(instance, 1L, 2L));
        assertEquals(2.5d, facade.getMethod("i64Divide", long.class, long.class).invoke(instance, 5L, 2L));
        assertEquals(2.5d, facade.getMethod("u64Divide", long.class, long.class).invoke(instance, 5L, 2L));
        assertEquals(9223372036854775808.0d,
                facade.getMethod("u64Divide", long.class, long.class).invoke(instance, -1L, 2L));
        assertEquals(2147483647.5d,
                facade.getMethod("u32Divide", int.class, int.class).invoke(instance, -1, 2));
        assertEquals(1, facade.getMethod("u32Remainder", int.class, int.class).invoke(instance, -1, 2));
        assertEquals(1L, facade.getMethod("u64Remainder", long.class, long.class).invoke(instance, -1L, 2L));
        assertRuntimeCode(() -> facade.getMethod("i8Add", byte.class, byte.class)
                .invoke(instance, (byte) 127, (byte) 1), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("u8Add", byte.class, byte.class)
                .invoke(instance, (byte) -1, (byte) 1), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("i64Add", long.class, long.class)
                .invoke(instance, Long.MAX_VALUE, 1L), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("u64Add", long.class, long.class)
                .invoke(instance, -1L, 1L), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("i64Multiply", long.class, long.class)
                .invoke(instance, Long.MIN_VALUE, -1L), "LYR-ARITH");
        assertRuntimeCode(() -> facade.getMethod("u64Divide", long.class, long.class)
                .invoke(instance, 1L, 0L), "LYR-ARITH");
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsFloatingEqualityAndCheckedFailure() throws Exception {
        TypedIr ir = lower("let @pub same :Fn<F64;Bool> = (=> |x| (== x x)) "
                + "let @pub increment :Fn<I32;I32> = (=> |x| (+ x 1))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, facade.getMethod("same", double.class).invoke(instance, 1.25d));
        java.lang.reflect.InvocationTargetException failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("increment", int.class).invoke(instance, Integer.MAX_VALUE));
        assertEquals("LYR-ARITH", ((io.mindspice.lyra.runtime.LyraRuntimeException) failure.getCause()).code());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void materializesNullableFunctionReturnsAtTheJavaBoundary() throws Exception {
        TypedIr ir = lower("let @pub absent :Fn<;@nil I32> = (=> | | #NIL) "
                + "let @pub present :Fn<;@nil I32> = (=> | | 0)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(null, invokeFunction(facade, instance, "absent"));
        assertEquals(0, invokeFunction(facade, instance, "present"));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsLoadableScalarFacade() throws Exception {
        TypedIr ir = lower("let @pub answer :I32 = (+ 20 22)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        String facadeName = plan.moduleFacades().get(ir.rootModule().moduleId());
        assertNotNull(artifact.classBytes(facadeName).orElseThrow());
        ClassModel model = ClassFile.of().parse(artifact.bytes(facadeName));
        SourceFileAttribute sourceFile = model.findAttribute(Attributes.sourceFile()).orElseThrow();
        assertEquals("phase15.lyra", sourceFile.sourceFile().stringValue());
        MethodModel getter = model.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("get$answer"))
                .findFirst().orElseThrow();
        CodeModel codeModel = getter.code().orElseThrow();
        assertTrue(codeModel instanceof CodeAttribute);
        CodeAttribute code = (CodeAttribute) codeModel;
        LineNumberTableAttribute lineTable = code.findAttribute(Attributes.lineNumberTable()).orElseThrow();
        assertTrue(!lineTable.lineNumbers().isEmpty());
        for (GeneratedClassPlan generated : plan.classes()) {
            for (GeneratedMemberPlan member : generated.members()) {
                assertEquals(member.descriptor(), artifact.descriptors().get(
                        generated.binaryName() + "#" + member.declarationKey()));
            }
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        };
        java.lang.reflect.Method define = loader.getClass().getDeclaredMethod("define", String.class, byte[].class);
        define.setAccessible(true);
        for (String name : artifact.classNames()) {
            define.invoke(loader, name, artifact.bytes(name));
        }
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object answer = facade.getMethod("get$answer").invoke(instance);
        assertEquals(42, answer);
        io.mindspice.lyra.runtime.ArtifactMetadata metadata =
                (io.mindspice.lyra.runtime.ArtifactMetadata) facade.getMethod("$lyra$metadata")
                        .invoke(instance);
        assertEquals(1, metadata.modules().size());
        assertEquals("answer", metadata.exports().getFirst().name());
        assertEquals("get$answer", metadata.exports().getFirst().getterName());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void rejectsUnvalidatedIrAtTheConsumerGate() {
        TypedIr validated = lower("let @pub answer :I32 = 42");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(validated);
        TypedIr candidate = new TypedIr(validated.typedSemanticGraph(),
                validated.modules(), validated.metadata());
        assertThrows(IllegalStateException.class,
                () -> JvmBytecodeEmitter.emit(candidate, plan));
        assertThrows(IllegalStateException.class,
                () -> JvmBytecodeEmitter.emitPhase(candidate, plan));
    }

    @Test
    void emittedArtifactDefensivelyCopiesEveryByteArray() {
        TypedIr ir = lower("let @pub answer :I32 = 42");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        String name = artifact.classNames().getFirst();
        byte[] expected = artifact.bytes(name);
        byte[] direct = artifact.bytes(name);
        direct[0] ^= 0x7f;
        assertArrayEquals(expected, artifact.bytes(name));
        byte[] mapped = artifact.classes().get(name);
        mapped[0] ^= 0x7f;
        assertArrayEquals(expected, artifact.classes().get(name));
    }

    @Test
    void runtimeFailuresUseIrSitesAndAppendDirectCallFrames() throws Exception {
        TypedIr ir = lower("let @pub explode :Fn<I32;F64> = (=> |x| (/ 1 (- x x))) "
                + "let @pub outer :Fn<I32;F64> = (=> |x| ::explode[x])");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        io.mindspice.lyra.runtime.LyraRuntimeException failure = assertRuntimeCode(
                () -> facade.getMethod("outer", int.class).invoke(instance, 1), "LYR-ARITH");
        io.mindspice.lyra.compiler.ir.IrFailureSite division = ir.failureSites().stream()
                .filter(site -> site.checkKind() == io.mindspice.lyra.compiler.ir.IrCheckKind.DIVISION)
                .findFirst().orElseThrow();
        IrNode.DirectCall call = io.mindspice.lyra.compiler.ir.IrTraversal.preOrder(ir).stream()
                .filter(IrNode.DirectCall.class::isInstance)
                .map(IrNode.DirectCall.class::cast)
                .findFirst().orElseThrow();
        assertEquals(2, failure.frames().size());
        assertSameSpan(division.span(), failure.frames().get(0).span());
        assertSameSpan(call.span(), failure.frames().get(1).span());
        assertEquals("explode", failure.frames().get(0).function());
        assertEquals("outer", failure.frames().get(1).function());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsExactJava25VersionAndOneBasedSourceLines() {
        TypedIr ir = lower("let hidden :I32 = 1\n"
                + "let @pub identity :Fn<I32;I32> = (=> |value| value)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        String closureName = plan.closureClasses().values().iterator().next();
        ClassModel model = ClassFile.of().parse(artifact.bytes(closureName));
        assertEquals(ClassFile.JAVA_25_VERSION, model.majorVersion());
        assertEquals(0, model.minorVersion());
        MethodModel invoke = model.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("invoke"))
                .findFirst().orElseThrow();
        LineNumberTableAttribute lines = ((CodeAttribute) invoke.code().orElseThrow())
                .findAttribute(Attributes.lineNumberTable()).orElseThrow();
        assertTrue(lines.lineNumbers().stream().allMatch(line -> line.lineNumber() >= 1));
        assertTrue(lines.lineNumbers().stream().anyMatch(line -> line.lineNumber() == 2));
    }

    @Test
    void verifiesAndInvokesTheCompleteArtifactInAnExternalJvm() throws Exception {
        TypedIr ir = lower("let @pub answer :I32 = (+ 20 22)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        JvmBytecodeArtifact repeated = JvmBytecodeEmitter.emit(ir, plan);
        assertEquals(artifact.classNames(), repeated.classNames());
        for (String name : artifact.classNames()) {
            assertArrayEquals(artifact.bytes(name), repeated.bytes(name));
        }
        verifyInExternalJvm(artifact, plan.moduleFacades().get(ir.rootModule().moduleId()));
    }

    @Test
    void emitsRuntimeVisibleFunctionalInterfaceAnnotations() throws Exception {
        TypedIr ir = lower("let @pub identity :Fn<I32;I32> = (=> |value| value)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        for (String functionInterface : plan.functionInterfaces().values()) {
            assertTrue(Class.forName(functionInterface, false, loader)
                    .isAnnotationPresent(FunctionalInterface.class));
        }
    }

    @Test
    void emitsDirectAndCallableFunctionCalls() throws Exception {
        TypedIr ir = lower("let @pub fn :Fn<I32;I32> = (=> |x| (+ x 1)) "
                + "let @pub apply :Fn<I32;I32> = (=> |x| (fn x)) "
                + "let @pub sink :Fn<I32;Unit> = (=> |x| ()) "
                + "let @pub invokeSink :Fn<I32;Unit> = (=> |x| (sink x))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(42, facade.getMethod("fn", int.class).invoke(instance, 41));
        assertEquals(42, facade.getMethod("apply", int.class).invoke(instance, 41));
        facade.getMethod("invokeSink", int.class).invoke(instance, 41);
        Object value = facade.getMethod("value$fn").invoke(instance);
        java.lang.reflect.Method invoke = value.getClass().getMethod("invoke", int.class);
        invoke.setAccessible(true);
        assertEquals(42, invoke.invoke(value, 41));
        facade.getMethod("close").invoke(instance);
    }

    private static void assertSameSpan(
            io.mindspice.lyra.compiler.source.SourceSpan expected,
            io.mindspice.lyra.runtime.SourceSpan actual) {
        assertEquals(expected.sourceId().value(), actual.sourceId().value());
        assertEquals(expected.startOffset(), actual.startOffset());
        assertEquals(expected.endOffset(), actual.endOffset());
    }

    private static void verifyInExternalJvm(JvmBytecodeArtifact artifact, String facadeName)
            throws Exception {
        Path directory = Files.createTempDirectory("lyra-phase15-emitter-");
        try {
            for (String name : artifact.classNames()) {
                Path classFile = directory.resolve(name.replace('.', '/') + ".class");
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, artifact.bytes(name));
            }
            Path javaLauncher = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "java.exe" : "java");
            Process process = new ProcessBuilder(javaLauncher.toString(), "-Xverify:all",
                    "-cp", System.getProperty("java.class.path"),
                    Phase15VerificationProbe.class.getName(), directory.toString(), facadeName)
                    .redirectErrorStream(false)
                    .start();
            boolean completed = process.waitFor(Duration.ofSeconds(10));
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(Duration.ofSeconds(2));
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(completed,
                    "external Phase-15 verifier timed out; stdout=" + stdout + "; stderr=" + stderr);
            assertEquals(0, process.exitValue(),
                    "external Phase-15 verifier failed; stdout=" + stdout + "; stderr=" + stderr);
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            }
        }
    }

    private ClassLoader defineAll(JvmBytecodeArtifact artifact) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        };
        java.lang.reflect.Method define = loader.getClass().getDeclaredMethod("define", String.class, byte[].class);
        define.setAccessible(true);
        for (String name : artifact.classNames()) {
            define.invoke(loader, name, artifact.bytes(name));
        }
        return loader;
    }

    private static TypedIr lower(String source) {
        ModuleId id = ModuleId.path("phase15.lyra");
        SourceSnapshot snapshot = success(SourceSnapshot.capture(id.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + id.value())),
                source.getBytes(StandardCharsets.UTF_8)));
        LexedSource lexed = success(Lexer.lex(snapshot));
        GrammarProgram grammar = success(GrammarMatcher.match(lexed));
        SyntaxProgram syntax = success(Parser.parse(lexed, grammar));
        ModuleGraph.Node node = new ModuleGraph.Node(id,
                Optional.of(io.mindspice.lyra.compiler.source.LogicalModuleId.fromSourceId(id.sourceId())),
                snapshot, syntax, ModuleRevision.compute(snapshot));
        ModuleGraph graph = new ModuleGraph(id, List.of(node), List.of(), Map.of());
        ResolvedSemanticGraph resolved = success(SemanticResolver.resolve(graph));
        TypedSemanticGraph typed = success(TypeChecker.check(resolved));
        return success(TypedIrBuilder.lower(typed));
    }

    /** Entry point used by the external -Xverify:all artifact check. */
    public static final class Phase15VerificationProbe {
        public static void main(String[] args) throws Exception {
            if (args.length != 2) {
                throw new IllegalArgumentException("expected generated class directory and facade name");
            }
            Path directory = Path.of(args[0]);
            Map<String, byte[]> classes = new HashMap<>();
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class"))
                        .forEach(path -> {
                            try {
                                String name = directory.relativize(path).toString()
                                        .replace('/', '.').replace('\\', '.')
                                        .replaceAll("\\.class$", "");
                                classes.put(name, Files.readAllBytes(path));
                            } catch (IOException exception) {
                                throw new java.io.UncheckedIOException(exception);
                            }
                        });
            }
            ClassLoader loader = new ClassLoader(Phase15SmokeTest.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name);
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            for (String name : classes.keySet()) {
                Class.forName(name, false, loader);
            }
            Class<?> facade = Class.forName(args[1], true, loader);
            Object instance = facade.getMethod("$lyra$create").invoke(null);
            Object answer = facade.getMethod("get$answer").invoke(instance);
            if (!Integer.valueOf(42).equals(answer)) {
                throw new AssertionError("generated artifact returned " + answer);
            }
            facade.getMethod("close").invoke(instance);
        }
    }

    private static <T extends ImmutablePhaseArtifact> T success(PhaseResult<T> result) {
        if (result instanceof PhaseResult.Success<T> success) return success.value();
        throw new AssertionError(result.diagnostics());
    }
}
