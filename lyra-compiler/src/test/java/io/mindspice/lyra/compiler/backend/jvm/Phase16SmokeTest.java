package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.lyra.runtime.LyraRuntimeException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase16SmokeTest {
    @Test
    void identicalSnapshotsEmitByteIdenticalPlansAndClasses() {
        String source = "let @pub answer :I32 = 42 "
                + "let @pub values :Array<I32> = Array[1 2] "
                + "let @pub maker :Fn<I32;Fn<;I32>> = (=> |value| (=> | | value))";
        TypedIr firstIr = lower(source);
        TypedIr secondIr = lower(source);
        GeneratedTypePlan firstPlan = GeneratedTypePlanner.plan(firstIr);
        GeneratedTypePlan secondPlan = GeneratedTypePlanner.plan(secondIr);
        JvmBytecodeArtifact first = JvmBytecodeEmitter.emit(firstIr, firstPlan);
        JvmBytecodeArtifact second = JvmBytecodeEmitter.emit(secondIr, secondPlan);
        assertEquals(firstPlan, secondPlan);
        assertEquals(first.classNames(), second.classNames());
        for (String name : first.classNames()) {
            assertArrayEquals(first.bytes(name), second.bytes(name), name);
        }
    }

    @Test
    void floatingRelationalComparisonsTreatNaNAsUnordered() throws Exception {
        TypedIr ir = lower("let @pub less :Fn<F64,F64;Bool> = (=> |a b| (< a b)) "
                + "let @pub lessEqual :Fn<F64,F64;Bool> = (=> |a b| (<= a b)) "
                + "let @pub greater :Fn<F64,F64;Bool> = (=> |a b| (> a b)) "
                + "let @pub greaterEqual :Fn<F64,F64;Bool> = (=> |a b| (>= a b))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(false, facade.getMethod("less", double.class, double.class)
                .invoke(instance, Double.NaN, 1.0));
        assertEquals(false, facade.getMethod("lessEqual", double.class, double.class)
                .invoke(instance, Double.NaN, 1.0));
        assertEquals(false, facade.getMethod("greater", double.class, double.class)
                .invoke(instance, Double.NaN, 1.0));
        assertEquals(false, facade.getMethod("greaterEqual", double.class, double.class)
                .invoke(instance, Double.NaN, 1.0));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void scalarTextConversionCoversUnsignedFloatAndUtf16Edges() throws Exception {
        TypedIr ir = lower("let @pub u8Text :Fn<U8;String> = (=> |value| String[value]) "
                + "let @pub u16Text :Fn<U16;String> = (=> |value| String[value]) "
                + "let @pub u64Text :Fn<U64;String> = (=> |value| String[value]) "
                + "let @pub f32Text :Fn<F32;String> = (=> |value| String[value]) "
                + "let @pub charText :Fn<Char;String> = (=> |value| String[value])");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals("255", facade.getMethod("u8Text", byte.class)
                .invoke(instance, (byte) -1));
        assertEquals("65535", facade.getMethod("u16Text", short.class)
                .invoke(instance, (short) -1));
        assertEquals("18446744073709551615", facade.getMethod("u64Text", long.class)
                .invoke(instance, -1L));
        assertEquals("1.0e7", facade.getMethod("f32Text", float.class)
                .invoke(instance, 1.0e7f));
        String surrogate = (String) facade.getMethod("charText", char.class)
                .invoke(instance, '\uD800');
        assertEquals(1, surrogate.length());
        assertEquals('\uD800', surrogate.charAt(0));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void unsignedNarrowValuesNormalizeAcrossWideningAndTupleFields() throws Exception {
        TypedIr ir = lower("let @pub widen8 :Fn<U8;I32> = (=> |value| I32[value]) "
                + "let @pub widen16 :Fn<U16;I32> = (=> |value| I32[value]) "
                + "let @pub tuple :Tuple<U8,U16> = Tuple[255U8 65535U16] "
                + "let @pub tuple8 :Fn<;I32> = (=> | | I32[tuple:.0]) "
                + "let @pub tuple16 :Fn<;I32> = (=> | | I32[tuple:.1])");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(255, facade.getMethod("widen8", byte.class).invoke(instance, (byte) -1));
        assertEquals(65535, facade.getMethod("widen16", short.class).invoke(instance, (short) -1));
        assertEquals(255, facade.getMethod("tuple8").invoke(instance));
        assertEquals(65535, facade.getMethod("tuple16").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void emitsAndInvokesAggregates() throws Exception {
        TypedIr ir = lower("let @pub values :Array<I32> = Array[1 2 3] "
                + "let @pub tuple :Tuple<I32,String> = Tuple[42 \"x\"] "
                + "let @pub length :Fn<Array<I32>;I32> = (=> |values| values:.length) "
                + "let @pub at :Fn<Array<I32>,I32;I32> = (=> |values index| values[index]) "
                + "let @pub chars :Fn<String,I32;Char> = (=> |text index| text[index]) "
                + "let @pub same :Fn<Array<I32>,Array<I32>;Bool> = (=> |left right| (== left right))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object values = facade.getMethod("get$values").invoke(instance);
        assertEquals(3, java.lang.reflect.Array.getLength(values));
        assertEquals(2, java.lang.reflect.Array.get(values, 1));
        Object tuple = facade.getMethod("get$tuple").invoke(instance);
        Method first = tuple.getClass().getDeclaredMethod("$lyra$get$0");
        Method second = tuple.getClass().getDeclaredMethod("$lyra$get$1");
        first.setAccessible(true);
        second.setAccessible(true);
        assertEquals(42, first.invoke(tuple));
        assertEquals("x", second.invoke(tuple));
        Object fn = facade.getMethod("value$length").invoke(instance);
        Method invoke = fn.getClass().getMethod("invoke", values.getClass());
        invoke.setAccessible(true);
        assertEquals(3, invoke.invoke(fn, values));
        Object at = facade.getMethod("value$at").invoke(instance);
        Method atInvoke = at.getClass().getMethod("invoke", values.getClass(), int.class);
        atInvoke.setAccessible(true);
        assertEquals(2, atInvoke.invoke(at, values, 1));
        Object chars = facade.getMethod("value$chars").invoke(instance);
        Method charsInvoke = chars.getClass().getMethod("invoke", String.class, int.class);
        charsInvoke.setAccessible(true);
        assertEquals('e', charsInvoke.invoke(chars, "hello", 1));
        Object same = facade.getMethod("value$same").invoke(instance);
        Method sameInvoke = same.getClass().getMethod("invoke", values.getClass(), values.getClass());
        sameInvoke.setAccessible(true);
        assertEquals(true, sameInvoke.invoke(same, values, new int[]{1, 2, 3}));
        assertEquals(false, sameInvoke.invoke(same, values, new int[]{1, 2, 4}));
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> atInvoke.invoke(at, values, 3)).getCause();
        assertEquals("LYR-BOUNDS", ((LyraRuntimeException) failure).code());
    }

    @Test
    void emitsExactArrayAbiAndPreservesLiveIdentityAliasesAndMutation() throws Exception {
        TypedIr ir = lower("let @pub bytes :Array<I8> = Array[1I8 2I8] "
                + "let @pub shorts :Array<I16> = Array[1I16 2I16] "
                + "let @pub @mut ints :Array<I32> = Array[1 2] "
                + "let @pub longs :Array<I64> = Array[1I64 2I64] "
                + "let @pub floats :Array<F32> = Array[1.0F32 2.0F32] "
                + "let @pub doubles :Array<F64> = Array[1.0F64 2.0F64] "
                + "let @pub flags :Array<Bool> = Array[#T #F] "
                + "let @pub chars :Array<Char> = Array['a' 'b'] "
                + "let @pub texts :Array<String> = Array[\"a\" \"b\"] "
                + "let @pub units :Array<Unit> = Array[() ()] "
                + "let @pub maybes :Array<@nil I32> = Array[#NIL 7] "
                + "let @pub alias :Array<I32> = ints "
                + "let @pub mutate :Fn<I32;Unit> = (=> |value| (ints[0] := value)) "
                + "let @pub read :Fn<;I32> = (=> | | alias[0]) "
                + "let @pub identical :Fn<;Bool> = (=> | | (eq? ints alias))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        Object instance = planAndCreate(ir, plan);
        Class<?> facade = instance.getClass();
        assertEquals(byte[].class, facade.getMethod("get$bytes").getReturnType());
        assertEquals(short[].class, facade.getMethod("get$shorts").getReturnType());
        assertEquals(int[].class, facade.getMethod("get$ints").getReturnType());
        assertEquals(long[].class, facade.getMethod("get$longs").getReturnType());
        assertEquals(float[].class, facade.getMethod("get$floats").getReturnType());
        assertEquals(double[].class, facade.getMethod("get$doubles").getReturnType());
        assertEquals(boolean[].class, facade.getMethod("get$flags").getReturnType());
        assertEquals(char[].class, facade.getMethod("get$chars").getReturnType());
        assertEquals(String[].class, facade.getMethod("get$texts").getReturnType());
        assertEquals(io.mindspice.lyra.runtime.LyraUnit[].class,
                facade.getMethod("get$units").getReturnType());
        assertEquals(Integer[].class, facade.getMethod("get$maybes").getReturnType());
        assertArrayEquals(new Integer[]{null, 7}, (Integer[]) facade.getMethod("get$maybes").invoke(instance));
        int[] ints = (int[]) facade.getMethod("get$ints").invoke(instance);
        assertSame(ints, facade.getMethod("get$alias").invoke(instance));
        assertEquals(true, facade.getMethod("identical").invoke(instance));
        facade.getMethod("mutate", int.class).invoke(instance, 9);
        assertEquals(9, facade.getMethod("read").invoke(instance));
        ints[0] = 11;
        assertEquals(11, facade.getMethod("read").invoke(instance));
    }

    @Test
    void functionArraysAndTupleFieldsRetainTypedClosureValues() throws Exception {
        TypedIr ir = lower("let one :Fn<I32;I32> = (=> |value| (+ value 1)) "
                + "let two :Fn<I32;I32> = (=> |value| (+ value 2)) "
                + "let @pub values :Array<Fn<I32;I32>> = Array[one two] "
                + "let @pub pair :Tuple<Fn<I32;I32>,Fn<I32;I32>> = Tuple[one two] "
                + "let @pub callArray :Fn<I32,I32;I32> = (=> |index value| (values[index] value)) "
                + "let @pub callTuple :Fn<I32;I32> = (=> |value| (pair:.0 value))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(6, facade.getMethod("callArray", int.class, int.class).invoke(instance, 0, 5));
        assertEquals(7, facade.getMethod("callArray", int.class, int.class).invoke(instance, 1, 5));
        assertEquals(6, facade.getMethod("callTuple", int.class).invoke(instance, 5));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void arraysAndTuplesUseStructuralEqualityWhileClosuresKeepIdentity() throws Exception {
        TypedIr ir = lower("let @pub arrays :Fn<Array<I32>,Array<I32>;Bool> = "
                + "(=> |left right| (== left right)) "
                + "let @pub tuples :Fn<Tuple<I32,String>,Tuple<I32,String>;Bool> = "
                + "(=> |left right| (== left right)) "
                + "let @pub sameFn :Fn<Fn<;I32>,Fn<;I32>;Bool> = "
                + "(=> |left right| (eq? left right)) "
                + "let @pub maker :Fn<;Fn<;I32>> = (=> | | (=> | | 1))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, facade.getMethod("arrays", int[].class, int[].class)
                .invoke(instance, new int[]{1, 2}, new int[]{1, 2}));
        Object tupleA = newTuple(plan, loader, 1, "x");
        Object tupleB = newTuple(plan, loader, 1, "x");
        Object tupleC = newTuple(plan, loader, 2, "x");
        Class<?> tupleClass = tupleA.getClass();
        assertTrue((tupleClass.getModifiers() & java.lang.reflect.Modifier.FINAL) != 0);
        assertTrue(java.util.Arrays.stream(tupleClass.getDeclaredFields())
                .allMatch(field -> java.lang.reflect.Modifier.isPrivate(field.getModifiers())
                        && java.lang.reflect.Modifier.isFinal(field.getModifiers())));
        assertEquals(true, facade.getMethod("tuples", tupleClass, tupleClass)
                .invoke(instance, tupleA, tupleB));
        assertEquals(false, facade.getMethod("tuples", tupleClass, tupleClass)
                .invoke(instance, tupleA, tupleC));
        Object maker = facade.getMethod("value$maker").invoke(instance);
        Method make = maker.getClass().getMethod("invoke");
        make.setAccessible(true);
        Object first = make.invoke(maker);
        Object second = make.invoke(maker);
        assertNotSame(first, second);
        Class<?> fnClass = Class.forName(plan.functionInterfaces().get("Fn<;I32>"), true, loader);
        Method sameFn = facade.getMethod("sameFn", fnClass, fnClass);
        assertEquals(true, sameFn.invoke(instance, first, first));
        assertEquals(false, sameFn.invoke(instance, first, second));
        Object javaCallback = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{fnClass}, (ignored, method, arguments) -> 1);
        Throwable callbackFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> sameFn.invoke(instance, javaCallback, first)).getCause();
        assertEquals("LYR-LINK", ((LyraRuntimeException) callbackFailure).code());
        Object otherInstance = facade.getMethod("$lyra$create").invoke(null);
        Object otherMaker = facade.getMethod("value$maker").invoke(otherInstance);
        Method otherMake = otherMaker.getClass().getMethod("invoke");
        otherMake.setAccessible(true);
        Object foreign = otherMake.invoke(otherMaker);
        Throwable foreignFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> sameFn.invoke(instance, foreign, first)).getCause();
        assertEquals("LYR-LINK", ((LyraRuntimeException) foreignFailure).code());
    }

    @Test
    void nestedAggregatesAndBoxedNullableScalarsUseRecursiveValueEquality() throws Exception {
        TypedIr ir = lower("let @pub nested :Fn<Array<Array<I32>>,Array<Array<I32>>;Bool> = "
                + "(=> |left right| (== left right)) "
                + "let @pub nullableFloats :Fn<Array<@nil F64>,Array<@nil F64>;Bool> = "
                + "(=> |left right| (== left right)) "
                + "let @pub tupleArrays :Fn<Tuple<Array<I32>,I32>,Tuple<Array<I32>,I32>;Bool> = "
                + "(=> |left right| (== left right))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);

        assertEquals(true, facade.getMethod("nested", int[][].class, int[][].class)
                .invoke(instance, new int[][]{{1, 2}, {3}}, new int[][]{{1, 2}, {3}}));
        assertEquals(false, facade.getMethod("nested", int[][].class, int[][].class)
                .invoke(instance, new int[][]{{1, 2}}, new int[][]{{1, 3}}));
        Method nullableFloats = facade.getMethod(
                "nullableFloats", Double[].class, Double[].class);
        assertEquals(true, nullableFloats.invoke(
                instance, new Double[]{null, -0.0d}, new Double[]{null, 0.0d}));
        assertEquals(false, nullableFloats.invoke(
                instance, new Double[]{Double.NaN}, new Double[]{Double.NaN}));

        Class<?> tuple = Class.forName(
                plan.tupleClasses().get("Tuple<Array<I32>,I32>"), true, loader);
        var constructor = tuple.getDeclaredConstructor(int[].class, int.class);
        constructor.setAccessible(true);
        Object left = constructor.newInstance(new int[]{4, 5}, 6);
        Object equal = constructor.newInstance(new int[]{4, 5}, 6);
        Object unequal = constructor.newInstance(new int[]{4, 7}, 6);
        assertEquals(true, facade.getMethod("tupleArrays", tuple, tuple)
                .invoke(instance, left, equal));
        assertEquals(false, facade.getMethod("tupleArrays", tuple, tuple)
                .invoke(instance, left, unequal));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void tupleElementsEvaluateArbitraryExpressionsExactlyOnceFromLeftToRight() throws Exception {
        TypedIr ir = lower("let @pub @mut counter :I32 = 0 "
                + "let @pub make :Fn<;Tuple<I32,I32>> = (=> | | Tuple["
                + "{ counter := (+ counter 1) counter } "
                + "{ counter := (+ counter 1) counter }])");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object tuple = facade.getMethod("make").invoke(instance);
        Method first = tuple.getClass().getDeclaredMethod("$lyra$get$0");
        Method second = tuple.getClass().getDeclaredMethod("$lyra$get$1");
        first.setAccessible(true);
        second.setAccessible(true);
        assertEquals(1, first.invoke(tuple));
        assertEquals(2, second.invoke(tuple));
        assertEquals(2, facade.getMethod("get$counter").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    private static Object newTuple(GeneratedTypePlan plan, ClassLoader loader,
                                   int first, String second) throws Exception {
        String name = plan.tupleClasses().get("Tuple<I32,String>");
        Class<?> tuple = Class.forName(name, true, loader);
        var constructor = tuple.getDeclaredConstructor(int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(first, second);
    }

    @Test
    void callableValuesReadFromJavaVisibleArraysAreAuthenticatedBeforeDispatch() throws Exception {
        TypedIr ir = lower("let @pub invokeFirst :Fn<Array<Fn<;I32>>;I32> = "
                + "(=> |values| (values[0])) "
                + "let @pub sameFunctions :Fn<Array<Fn<;I32>>,Array<Fn<;I32>>;Bool> = "
                + "(=> |left right| (== left right)) "
                + "let @pub value :Fn<;I32> = (=> | | 7)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(
                plan.functionInterfaces().get("Fn<;I32>"), true, loader);
        Class<?> functionArray = java.lang.reflect.Array.newInstance(function, 0).getClass();
        Method invokeFirst = facade.getMethod("invokeFirst", functionArray);

        Object accepted = java.lang.reflect.Array.newInstance(function, 1);
        java.lang.reflect.Array.set(
                accepted, 0, facade.getMethod("value$value").invoke(instance));
        assertEquals(7, invokeFirst.invoke(instance, accepted));

        java.util.concurrent.atomic.AtomicInteger callbackCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        java.lang.reflect.InvocationHandler handler = (proxy, method, arguments) -> {
            callbackCalls.incrementAndGet();
            return switch (method.getName()) {
                case "equals" -> true;
                case "hashCode" -> 1;
                case "toString" -> "callback";
                default -> 99;
            };
        };
        Object callback = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[]{function}, handler);
        Object otherCallback = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[]{function}, handler);
        Object rejected = java.lang.reflect.Array.newInstance(function, 1);
        Object sameIdentity = java.lang.reflect.Array.newInstance(function, 1);
        Object otherIdentity = java.lang.reflect.Array.newInstance(function, 1);
        java.lang.reflect.Array.set(rejected, 0, callback);
        java.lang.reflect.Array.set(sameIdentity, 0, callback);
        java.lang.reflect.Array.set(otherIdentity, 0, otherCallback);
        Method sameFunctions = facade.getMethod(
                "sameFunctions", functionArray, functionArray);
        assertEquals(true, sameFunctions.invoke(instance, rejected, sameIdentity));
        assertEquals(false, sameFunctions.invoke(instance, rejected, otherIdentity));
        assertEquals(0, callbackCalls.get());

        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invokeFirst.invoke(instance, rejected)).getCause();
        assertEquals("LYR-LINK", ((LyraRuntimeException) failure).code());
        assertEquals(0, callbackCalls.get());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void stringIndexAndLengthUseUtf16CodeUnits() throws Exception {
        TypedIr ir = lower("let @pub length :Fn<String;I32> = (=> |text| text:.length) "
                + "let @pub at :Fn<String,I32;Char> = (=> |text index| text[index])");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        Object instance = planAndCreate(ir, plan);
        String text = "A\uD83D\uDE00B";
        assertEquals(4, instance.getClass().getMethod("length", String.class).invoke(instance, text));
        assertEquals('\uD83D', instance.getClass().getMethod("at", String.class, int.class)
                .invoke(instance, text, 1));
        assertEquals('\uDE00', instance.getClass().getMethod("at", String.class, int.class)
                .invoke(instance, text, 2));
        Throwable negative = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> instance.getClass().getMethod("at", String.class, int.class)
                        .invoke(instance, text, -1)).getCause();
        assertEquals("LYR-BOUNDS", ((LyraRuntimeException) negative).code());
    }

    @Test
    void supportsMutualAndNonTailRecursion() throws Exception {
        TypedIr ir = lower("let @pub first :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : { let value :I32 = ::second[(- n 1)] (+ value 1) })) "
                + "let @pub second :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : { let value :I32 = ::first[(- n 1)] (+ value 1) })) "
                + "let @pub nonTail :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : { let value :I32 = ::nonTail[(- n 1)] (+ value 1) }))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        if (Boolean.getBoolean("phase16.dump")) {
            for (String name : artifact.classNames()) {
                java.nio.file.Path path = java.nio.file.Path.of("/tmp/phase16classes",
                        name.replace('.', '/') + ".class");
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.write(path, artifact.bytes(name));
            }
        }
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object firstResult = facade.getMethod("first", int.class).invoke(instance, 5);
        Object secondResult = facade.getMethod("second", int.class).invoke(instance, 5);
        Object nonTailResult = facade.getMethod("nonTail", int.class).invoke(instance, 5);
        assertEquals(5, firstResult);
        assertEquals(5, secondResult);
        assertEquals(5, nonTailResult);
    }

    @Test
    void separateClosuresShareOneCapturedMutableCell() throws Exception {
        TypedIr ir = lower("let @pub maker :Fn<I32;Tuple<Fn<;I32>,Fn<;I32>>> = "
                + "(=> |value| { let @mut current :I32 = value "
                + "let first :Fn<;I32> = (=> | | { current := (+ current 1) current }) "
                + "let second :Fn<;I32> = (=> | | { current := (+ current 10) current }) "
                + "Tuple[first second] })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object maker = facade.getMethod("value$maker").invoke(instance);
        Method make = maker.getClass().getMethod("invoke", int.class);
        make.setAccessible(true);
        Object pair = make.invoke(maker, 1);
        Method getFirst = pair.getClass().getDeclaredMethod("$lyra$get$0");
        Method getSecond = pair.getClass().getDeclaredMethod("$lyra$get$1");
        getFirst.setAccessible(true);
        getSecond.setAccessible(true);
        Object first = getFirst.invoke(pair);
        Object second = getSecond.invoke(pair);
        Method invokeFirst = first.getClass().getMethod("invoke");
        Method invokeSecond = second.getClass().getMethod("invoke");
        invokeFirst.setAccessible(true);
        invokeSecond.setAccessible(true);
        assertEquals(2, invokeFirst.invoke(first));
        assertEquals(12, invokeSecond.invoke(second));
        assertEquals(13, invokeFirst.invoke(first));
    }

    @Test
    void importsCrossModuleValuesAndFunctionsAndSharesArtifactAuthority() throws Exception {
        ModuleId main = ModuleId.path("phase16_modules_main.lyra");
        ModuleId dependency = ModuleId.path("phase16_modules_dep.lyra");
        String dependencySource = "let @pub @mut value :I32 = 7 "
                + "let @pub add :Fn<I32;I32> = (=> |n| (+ n value))";
        String mainSource = "import phase16_modules_dep "
                + "let @pub read :Fn<;I32> = (=> | | phase16_modules_dep->:.value) "
                + "let @pub call :Fn<I32;I32> = (=> |n| phase16_modules_dep->::add[n]) "
                + "let @pub depAdd :Fn<I32;I32> = phase16_modules_dep->:.add "
                + "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |fn n| (fn n))";
        ModuleGraph graph = moduleGraph(main, mainSource, dependency, dependencySource);
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("read").invoke(instance));
        assertEquals(12, facade.getMethod("call", int.class).invoke(instance, 5));
        Object depAdd = facade.getMethod("value$depAdd").invoke(instance);
        Class<?> functionClass = Class.forName(plan.functionInterfaces().get("Fn<I32;I32>"), true, loader);
        assertEquals(12, facade.getMethod("apply", functionClass, int.class)
                .invoke(instance, depAdd, 5));
        instance.getClass().getMethod("close").invoke(instance);
        Throwable closed = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("read").invoke(instance)).getCause();
        assertEquals("LYR-CLOSED", ((LyraRuntimeException) closed).code());
    }

    @Test
    void functionOnlyImportCyclesLinkBeforeEitherModuleInitializes() throws Exception {
        ModuleId main = ModuleId.path("phase16_function_cycle_main.lyra");
        ModuleId dependency = ModuleId.path("phase16_function_cycle_dep.lyra");
        ModuleGraph.Node mainNode = module(main,
                "import phase16_function_cycle_dep->{g} "
                        + "let @pub f :Fn<;I32> = (=> | | ::g[])");
        ModuleGraph.Node dependencyNode = module(dependency,
                "import phase16_function_cycle_main->{f} "
                        + "let @pub g :Fn<;I32> = (=> | | ::f[])");
        LogicalModuleId mainLogical = LogicalModuleId.fromSourceId(main.sourceId());
        LogicalModuleId dependencyLogical = LogicalModuleId.fromSourceId(dependency.sourceId());
        ModuleGraph graph = new ModuleGraph(main, List.of(mainNode, dependencyNode),
                List.of(new ModuleGraph.Edge(main, dependencyLogical, dependency,
                                mainNode.program().imports().getFirst().path().span()),
                        new ModuleGraph.Edge(dependency, mainLogical, main,
                                dependencyNode.program().imports().getFirst().path().span())),
                Map.of(mainLogical, main, dependencyLogical, dependency));
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true,
                defineAll(JvmBytecodeEmitter.emit(ir, plan)));
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("f").invoke(instance)).getCause();
        assertEquals("LYR-STACK", ((LyraRuntimeException) failure).code());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void reexportedValuesAndFunctionsDelegateToTheirOriginModuleState() throws Exception {
        ModuleId main = ModuleId.path("phase16_reexport_main.lyra");
        ModuleId dependency = ModuleId.path("phase16_reexport_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import phase16_reexport_dep "
                        + "import @pub phase16_reexport_dep->{value add} "
                        + "let @pub qualified :I32 = phase16_reexport_dep->:.value "
                        + "let @pub qualifiedCall :Fn<I32;I32> = "
                        + "(=> |n| phase16_reexport_dep->::add[n])",
                dependency,
                "let @pub value :I32 = 7 "
                        + "let @pub add :Fn<I32;I32> = (=> |n| (+ n value))");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("get$value").invoke(instance));
        assertEquals(12, facade.getMethod("add", int.class).invoke(instance, 5));
        assertEquals(7, facade.getMethod("get$qualified").invoke(instance));
        assertEquals(12, facade.getMethod("qualifiedCall", int.class).invoke(instance, 5));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void importOnlyModuleStatesStillEmitValidExceptionRanges() throws Exception {
        ModuleId main = ModuleId.path("phase16_import_only_main.lyra");
        ModuleId dependency = ModuleId.path("phase16_import_only_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import @pub phase16_import_only_dep->{value}",
                dependency, "let @pub value :I32 = 7");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("get$value").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void chainedReexportsFollowEveryModuleStateLink() throws Exception {
        ModuleId main = ModuleId.path("phase16_chain_main.lyra");
        ModuleId middle = ModuleId.path("phase16_chain_middle.lyra");
        ModuleId origin = ModuleId.path("phase16_chain_origin.lyra");
        ModuleGraph.Node mainNode = module(main,
                "import phase16_chain_middle "
                        + "import @pub phase16_chain_middle->{value add} "
                        + "let @pub qualified :I32 = phase16_chain_middle->:.value "
                        + "let @pub qualifiedCall :Fn<I32;I32> = "
                        + "(=> |n| phase16_chain_middle->::add[n])");
        ModuleGraph.Node middleNode = module(middle,
                "import @pub phase16_chain_origin->{value add}");
        ModuleGraph.Node originNode = module(origin,
                "let @pub value :I32 = 7 "
                        + "let @pub add :Fn<I32;I32> = (=> |n| (+ n value))");
        LogicalModuleId middleLogical = LogicalModuleId.fromSourceId(middle.sourceId());
        LogicalModuleId originLogical = LogicalModuleId.fromSourceId(origin.sourceId());
        ModuleGraph graph = new ModuleGraph(main,
                List.of(mainNode, middleNode, originNode),
                java.util.stream.Stream.concat(
                                mainNode.program().imports().stream()
                                        .map(importNode -> new ModuleGraph.Edge(main, middleLogical, middle,
                                                importNode.path().span())),
                                java.util.stream.Stream.of(new ModuleGraph.Edge(middle, originLogical, origin,
                                        middleNode.program().imports().getFirst().path().span())))
                        .toList(),
                Map.of(middleLogical, middle, originLogical, origin));
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("get$value").invoke(instance));
        assertEquals(12, facade.getMethod("add", int.class).invoke(instance, 5));
        assertEquals(7, facade.getMethod("get$qualified").invoke(instance));
        assertEquals(12, facade.getMethod("qualifiedCall", int.class).invoke(instance, 5));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void chainedReexportsRemainReachableFromLocalSelectiveCalls() throws Exception {
        ModuleId main = ModuleId.path("phase16_local_chain_main.lyra");
        ModuleId middle = ModuleId.path("phase16_local_chain_middle.lyra");
        ModuleId origin = ModuleId.path("phase16_local_chain_origin.lyra");
        ModuleGraph.Node mainNode = module(main,
                "import phase16_local_chain_middle->{add} "
                        + "let @pub answer :I32 = ::add[5]");
        ModuleGraph.Node middleNode = module(middle,
                "import @pub phase16_local_chain_origin->{add}");
        ModuleGraph.Node originNode = module(origin,
                "let @pub add :Fn<I32;I32> = (=> |n| (+ n 1))");
        LogicalModuleId middleLogical = LogicalModuleId.fromSourceId(middle.sourceId());
        LogicalModuleId originLogical = LogicalModuleId.fromSourceId(origin.sourceId());
        ModuleGraph graph = new ModuleGraph(main,
                List.of(mainNode, middleNode, originNode),
                List.of(new ModuleGraph.Edge(main, middleLogical, middle,
                                mainNode.program().imports().getFirst().path().span()),
                        new ModuleGraph.Edge(middle, originLogical, origin,
                                middleNode.program().imports().getFirst().path().span())),
                Map.of(middleLogical, middle, originLogical, origin));
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(6, facade.getMethod("get$answer").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void recursiveFunctionValueCapturesLinkBeforeInvocation() throws Exception {
        TypedIr ir = lower("let @pub first :Fn<;Bool> = (=> | | (eq? second second)) "
                + "let second :Fn<;Bool> = (=> | | (eq? first first))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, facade.getMethod("first").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void rootSelfFunctionValueUsesItsLinkedIdentity() throws Exception {
        TypedIr ir = lower("let @pub self :Fn<;Bool> = (=> | | (eq? self self))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(true, facade.getMethod("self").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void localForwardFunctionCallUsesPredeclaredSlot() throws Exception {
        TypedIr ir = lower("let @pub run :Fn<;I32> = (=> | | { "
                + "let first :Fn<;I32> = (=> | | ::second[]) "
                + "let answer :I32 = ::first[] "
                + "let second :Fn<;I32> = (=> | | 7) answer })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("run").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void localForwardMutableFunctionCallUsesPreallocatedCell() throws Exception {
        TypedIr ir = lower("let @pub run :Fn<;I32> = (=> | | { "
                + "let @mut first :Fn<;I32> = (=> | | ::second[]) "
                + "let answer :I32 = ::first[] "
                + "let @mut second :Fn<;I32> = (=> | | 7) answer })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("run").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void localForwardCaptureIsLinkedBeforeAnEagerCall() throws Exception {
        TypedIr ir = lower("let @pub run :Fn<;I32> = (=> | | { "
                + "let make :Fn<;I32> = (=> | | ::later[]) "
                + "let answer :I32 = ::make[] "
                + "let later :Fn<;I32> = (=> | | 7) answer })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(7, facade.getMethod("run").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void localMutuallyRecursiveFunctionsUseLinkedClosureSlots() throws Exception {
        TypedIr ir = lower("let @pub run :Fn<I32;I32> = (=> |n| { "
                + "let first :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::second[(- value 1)])) "
                + "let second :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::first[(- value 1)])) "
                + "(first n) })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        long linkedSlots = plan.classes().stream()
                .filter(value -> value.kind() == GeneratedClassKind.CLOSURE)
                .flatMap(value -> value.members().stream())
                .filter(value -> value.kind() == GeneratedMemberKind.CLOSURE_CAPTURE_FIELD)
                .filter(value -> value.descriptor().startsWith("[L"))
                .count();
        assertEquals(2, linkedSlots);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(0, facade.getMethod("run", int.class).invoke(instance, 6));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void escapedLocalRecursiveClosuresRetainTheirLinkedSlots() throws Exception {
        TypedIr ir = lower("let @pub maker :Fn<;Fn<I32;I32>> = (=> | | { "
                + "let first :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::second[(- value 1)])) "
                + "let second :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::first[(- value 1)])) first })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object first = facade.getMethod("maker").invoke(instance);
        Method invoke = first.getClass().getMethod("invoke", int.class);
        invoke.setAccessible(true);
        assertEquals(0, invoke.invoke(first, 20));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void localNonTailSelfRecursionUsesTheCurrentClosureIdentity() throws Exception {
        TypedIr ir = lower("let @pub run :Fn<I32;I32> = (=> |n| { "
                + "let walk :Fn<I32;I32> = (=> |value| ((<= value 0) -> 0 : { "
                + "let tail :I32 = ::walk[(- value 1)] (+ tail 1) })) "
                + "(walk n) })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(20, facade.getMethod("run", int.class).invoke(instance, 20));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void mutableLocalMutualRecursionPreallocatesSharedFunctionCells() throws Exception {
        TypedIr ir = lower("let @pub run :Fn<I32;I32> = (=> |n| { "
                + "let @mut first :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::second[(- value 1)])) "
                + "let @mut second :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::first[(- value 1)])) "
                + "(first n) })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(0, facade.getMethod("run", int.class).invoke(instance, 20));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void translatesOrdinaryRecursionStackOverflowAtGeneratedCallBoundary() throws Exception {
        TypedIr ir = lower("let @pub recurse :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : { let value :I32 = ::recurse[(- n 1)] (+ value 1) }))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        Object instance = planAndCreate(ir, plan);
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> instance.getClass().getMethod("recurse", int.class).invoke(instance, 100_000))
                .getCause();
        assertTrue(failure instanceof LyraRuntimeException,
                () -> "expected translated runtime failure, got " + failure);
        assertEquals("LYR-STACK", ((LyraRuntimeException) failure).code());
    }

    @Test
    void eagerInitializationCanInvokeLinkedFunctionsAcrossModules() throws Exception {
        ModuleId main = ModuleId.path("phase16_eager_main.lyra");
        ModuleId dependency = ModuleId.path("phase16_eager_dep.lyra");
        ModuleGraph graph = moduleGraph(main,
                "import phase16_eager_dep let @pub answer :I32 = phase16_eager_dep->::add[5]",
                dependency, "let @pub add :Fn<I32;I32> = (=> |n| (+ n 1))");
        TypedIr ir = lower(graph);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact first = JvmBytecodeEmitter.emit(ir, plan);
        JvmBytecodeArtifact second = JvmBytecodeEmitter.emit(ir, plan);
        assertEquals(first.classNames(), second.classNames());
        for (String name : first.classNames()) assertArrayEquals(first.bytes(name), second.bytes(name));
        ClassLoader loader = defineAll(first);
        Class<?> facade = Class.forName(plan.moduleFacades().get(main), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(6, facade.getMethod("get$answer").invoke(instance));
    }

    @Test
    void recursiveFunctionSlotsLinkBeforeInterleavedEagerValues() throws Exception {
        TypedIr ir = lower("let first :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : ::second[(- n 1)])) "
                + "let @pub answer :I32 = ::first[4] "
                + "let second :Fn<I32;I32> = "
                + "(=> |n| ((<= n 0) -> 0 : ::first[(- n 1)]))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(0, facade.getMethod("get$answer").invoke(instance));
    }

    @Test
    void recursiveFunctionPrelinkingRetainsInitializedCaptures() throws Exception {
        TypedIr ir = lower("let seed :I32 = 1 "
                + "let recurse :Fn<I32;I32> = (=> |n| "
                + "((<= n 0) -> seed : { let tail :I32 = ::recurse[(- n 1)] (+ seed tail) })) "
                + "let @pub answer :I32 = ::recurse[3]");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(4, facade.getMethod("get$answer").invoke(instance));
    }

    @Test
    void eagerInitializationCanInvokeAnEarlierSameModuleFunction() throws Exception {
        TypedIr ir = lower("let add :Fn<I32;I32> = (=> |n| (+ n 1)) "
                + "let @pub answer :I32 = ::add[5]");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(6, facade.getMethod("get$answer").invoke(instance));
    }

    @Test
    void eagerInitializationPrelinksAForwardFunctionBeforeItsValueUse() throws Exception {
        TypedIr ir = lower("let @pub answer :I32 = ::add[5] "
                + "let add :Fn<I32;I32> = (=> |n| (+ n 1))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(6, facade.getMethod("get$answer").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void prelinkedClosuresRetainTheSourceOrderedPrivateCapture() throws Exception {
        TypedIr ir = lower("let value :I32 = 1 "
                + "let read :Fn<;I32> = (=> | | value) "
                + "let value :I32 = 2 "
                + "let @pub answer :I32 = ::read[]");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(1, facade.getMethod("get$answer").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void rootCapturedMutableCellsRemainLiveThroughFacadeSetters() throws Exception {
        TypedIr ir = lower("let @pub @mut current :I32 = 1 "
                + "let @pub read :Fn<;I32> = (=> | | current)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(1, facade.getMethod("read").invoke(instance));
        facade.getMethod("set$current", int.class).invoke(instance, 7);
        assertEquals(7, facade.getMethod("read").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void nullablePrimitiveSharedCellsRoundTripFacadeWrappersAndNil() throws Exception {
        TypedIr ir = lower("let @pub @mut @nil value :I32 = #NIL "
                + "let @pub read :Fn<;I32> = "
                + "(=> | | (value present -> present : (- 1)))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertNull(facade.getMethod("get$value").invoke(instance));
        assertEquals(-1, facade.getMethod("read").invoke(instance));
        facade.getMethod("set$value", Integer.class).invoke(instance, 7);
        assertEquals(7, facade.getMethod("get$value").invoke(instance));
        assertEquals(7, facade.getMethod("read").invoke(instance));
        facade.getMethod("set$value", Integer.class).invoke(instance, new Object[]{null});
        assertNull(facade.getMethod("get$value").invoke(instance));
        assertEquals(-1, facade.getMethod("read").invoke(instance));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void mutableFunctionSettersAuthenticateAndReplaceLiveBindings() throws Exception {
        TypedIr ir = lower("let @pub @mut fn :Fn<I32;I32> = (=> |n| (+ n 1)) "
                + "let @pub replacement :Fn<I32;I32> = (=> |n| (+ n 10)) "
                + "let @pub capture :Fn<;Fn<I32;I32>> = (=> | | fn)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<I32;I32>"), true, loader);
        assertEquals(2, facade.getMethod("fn", int.class).invoke(instance, 1));
        Object replacement = facade.getMethod("value$replacement").invoke(instance);
        facade.getMethod("set$fn", function).invoke(instance, replacement);
        assertEquals(11, facade.getMethod("fn", int.class).invoke(instance, 1));
        Object captured = facade.getMethod("capture").invoke(instance);
        Method invoke = captured.getClass().getMethod("invoke", int.class);
        invoke.setAccessible(true);
        assertEquals(11, invoke.invoke(captured, 1));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void mutableUncapturedFunctionSetterAuthenticatesReplacement() throws Exception {
        TypedIr ir = lower("let @pub @mut fn :Fn<I32;I32> = (=> |n| (+ n 1)) "
                + "let @pub replacement :Fn<I32;I32> = (=> |n| (+ n 10))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<I32;I32>"), true, loader);
        assertEquals(2, facade.getMethod("fn", int.class).invoke(instance, 1));
        Object replacement = facade.getMethod("value$replacement").invoke(instance);
        facade.getMethod("set$fn", function).invoke(instance, replacement);
        assertEquals(11, facade.getMethod("fn", int.class).invoke(instance, 1));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void nullableFunctionSetterAcceptsNilAndAuthenticatedClosure() throws Exception {
        TypedIr ir = lower("let @pub @mut fn :@nil Fn<;I32> = #NIL "
                + "let @pub replacement :Fn<;I32> = (=> | | 9)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<;I32>"), true, loader);
        assertNull(facade.getMethod("value$fn").invoke(instance));
        Throwable absent = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("fn").invoke(instance)).getCause();
        assertEquals("LYR-LINK", ((LyraRuntimeException) absent).code());
        Object replacement = facade.getMethod("value$replacement").invoke(instance);
        facade.getMethod("set$fn", function).invoke(instance, replacement);
        assertEquals(9, facade.getMethod("fn").invoke(instance));
        facade.getMethod("set$fn", function).invoke(instance, new Object[]{null});
        assertNull(facade.getMethod("value$fn").invoke(instance));
        Throwable cleared = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("fn").invoke(instance)).getCause();
        assertEquals("LYR-LINK", ((LyraRuntimeException) cleared).code());
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void nullableNarrowPrimitiveUnboxingPreservesUnsignedPayloads() throws Exception {
        TypedIr ir = lower("let @pub increment :Fn<@nil U8;I32> = "
                + "(=> |value| (value narrowed -> (+ narrowed 1) : 0)) "
                + "let @pub increment16 :Fn<@nil U16;I32> = "
                + "(=> |value| (value narrowed -> (+ narrowed 1) : 0))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        assertEquals(256, facade.getMethod("increment", Byte.class).invoke(instance, (byte) -1));
        assertEquals(65536, facade.getMethod("increment16", Short.class).invoke(instance, (short) -1));
        assertEquals(0, facade.getMethod("increment", Byte.class).invoke(instance, new Object[]{null}));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void nullableFunctionParametersPreserveNullWithoutStackPollution() throws Exception {
        TypedIr ir = lower("let @pub call :Fn<@nil Fn<;I32>;I32> = "
                + "(=> |fn| (fn value -> (value) : 7))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Class<?> function = Class.forName(plan.functionInterfaces().get("Fn<;I32>"), true, loader);
        assertEquals(7, facade.getMethod("call", function).invoke(instance, new Object[]{null}));
        Object identity = facade.getMethod("value$call").invoke(instance);
        assertTrue(identity != null);
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void externalVerifierLoadsAndInvokesCompletePhase16Artifact() throws Exception {
        TypedIr ir = lower("let @pub answer :I32 = 42 "
                + "let @pub values :Array<@nil I32> = Array[#NIL 7] "
                + "let @pub tuple :Tuple<String,Array<I32>> = Tuple[\"x\" Array[1 2]] "
                + "let @pub increment :Fn<@nil U8;I32> = "
                + "(=> |value| (value narrowed -> (+ narrowed 1) : 0)) "
                + "let @pub maker :Fn<I32;Fn<;I32>> = (=> |n| (=> | | n)) "
                + "let @pub local :Fn<I32;I32> = (=> |n| { "
                + "let first :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::second[(- value 1)])) "
                + "let second :Fn<I32;I32> = (=> |value| "
                + "((<= value 0) -> 0 : ::first[(- value 1)])) (first n) })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        Path directory = Files.createTempDirectory("lyra-phase16-verify-");
        try {
            for (String name : artifact.classNames()) {
                Path classFile = directory.resolve(name.replace('.', '/') + ".class");
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, artifact.bytes(name));
            }
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Process process = new ProcessBuilder(java.toString(), "-Xverify:all", "-cp",
                    System.getProperty("java.class.path"),
                    Phase15SmokeTest.Phase15VerificationProbe.class.getName(),
                    directory.toString(), plan.moduleFacades().get(ir.rootModule().moduleId()))
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(Duration.ofSeconds(10)), "external verifier timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void initializationFailureIsTerminalAndMappedToInit() throws Exception {
        TypedIr ir = lower("let zero :I32 = 0 let @pub value :F64 = (/ 1 zero)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("$lyra$create").invoke(null)).getCause();
        assertEquals("LYR-INIT", ((LyraRuntimeException) failure).code());
        assertEquals("LYR-ARITH", ((LyraRuntimeException) ((LyraRuntimeException) failure)
                .getCause()).code());
    }

    @Test
    void capturedRootValuesDoNotReorderEarlierEagerInitializers() throws Exception {
        String source = "let zero :I32 = 0 "
                + "let first :I32 = (% 1 zero) "
                + "let later :I32 = (% 2 zero) "
                + "let captured :Fn<;I32> = (=> | | later) "
                + "let @pub answer :I32 = 0";
        TypedIr ir = lower(source);
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(
                plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("$lyra$create").invoke(null)).getCause();
        LyraRuntimeException initialization = (LyraRuntimeException) failure;
        assertEquals("LYR-INIT", initialization.code());
        LyraRuntimeException arithmetic = (LyraRuntimeException) initialization.getCause();
        assertEquals("LYR-ARITH", arithmetic.code());
        assertEquals(source.indexOf("(% 1 zero)"),
                arithmetic.frames().getFirst().span().startOffset());
    }

    @Test
    void integerFailureDuringInitializationIsTerminallyMappedToInit() throws Exception {
        TypedIr ir = lower("let zero :I32 = 0 let @pub value :I32 = (% 1 zero)");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("$lyra$create").invoke(null)).getCause();
        assertEquals("LYR-INIT", ((LyraRuntimeException) failure).code());
        Throwable cause = ((LyraRuntimeException) failure).getCause();
        assertTrue(cause instanceof LyraRuntimeException);
        assertEquals("LYR-ARITH", ((LyraRuntimeException) cause).code());
    }

    @Test
    void eagerCallFailureIsTerminallyMappedToInit() throws Exception {
        TypedIr ir = lower("let zero :I32 = 0 "
                + "let boom :Fn<;F64> = (=> | | (/ 1 zero)) "
                + "let @pub answer :F64 = ::boom[]");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Throwable failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> facade.getMethod("$lyra$create").invoke(null)).getCause();
        assertEquals("LYR-INIT", ((LyraRuntimeException) failure).code());
        Throwable cause = ((LyraRuntimeException) failure).getCause();
        assertTrue(cause instanceof LyraRuntimeException);
        assertEquals("LYR-ARITH", ((LyraRuntimeException) cause).code());
    }

    @Test
    void generatedFacadeAndClosuresRejectWrongThreadBeforeStateAccess() throws Exception {
        TypedIr ir = lower("let @pub value :I32 = 1");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        Object instance = planAndCreate(ir, plan);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                instance.getClass().getMethod("get$value").invoke(instance);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                failure.set(exception.getCause());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();
        assertEquals("LYR-THREAD", ((LyraRuntimeException) failure.get()).code());
        instance.getClass().getMethod("close").invoke(instance);
    }

    private static Object planAndCreate(TypedIr ir, GeneratedTypePlan plan) throws Exception {
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        return facade.getMethod("$lyra$create").invoke(null);
    }

    @Test
    void capturesImmutableValuesAndCreatesFreshClosureIdentities() throws Exception {
        TypedIr ir = lower("let @pub maker :Fn<I32;Fn<;I32>> = "
                + "(=> |value| (=> | | value))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object maker = facade.getMethod("value$maker").invoke(instance);
        Method make = maker.getClass().getMethod("invoke", int.class);
        make.setAccessible(true);
        Object first = make.invoke(maker, 7);
        Object second = make.invoke(maker, 7);
        assertEquals(false, first == second);
        Method read = first.getClass().getMethod("invoke");
        read.setAccessible(true);
        assertEquals(7, read.invoke(first));
    }

    @Test
    void immutableNullablePrimitiveCapturesPreservePresenceAndPayload() throws Exception {
        TypedIr ir = lower("let @pub maker :Fn<@nil I32;Fn<;@nil I32>> = "
                + "(=> |maybe| (=> | | maybe))");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        ClassLoader loader = defineAll(JvmBytecodeEmitter.emit(ir, plan));
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object maker = facade.getMethod("value$maker").invoke(instance);
        Method make = maker.getClass().getMethod("invoke", Integer.class);
        make.setAccessible(true);
        Object nilClosure = make.invoke(maker, new Object[]{null});
        Method nilRead = nilClosure.getClass().getMethod("invoke");
        nilRead.setAccessible(true);
        assertNull(nilRead.invoke(nilClosure));
        Object valueClosure = make.invoke(maker, 7);
        Method valueRead = valueClosure.getClass().getMethod("invoke");
        valueRead.setAccessible(true);
        assertEquals(7, valueRead.invoke(valueClosure));
        facade.getMethod("close").invoke(instance);
    }

    @Test
    void capturedMutableBindingsUseOneSharedCell() throws Exception {
        TypedIr ir = lower("let @pub maker :Fn<I32;Fn<;I32>> = (=> |value| { "
                + "let @mut current :I32 = value "
                + "(=> | | { current := (+ current 1) current }) })");
        GeneratedTypePlan plan = GeneratedTypePlanner.plan(ir);
        JvmBytecodeArtifact artifact = JvmBytecodeEmitter.emit(ir, plan);
        ClassLoader loader = defineAll(artifact);
        Class<?> facade = Class.forName(plan.moduleFacades().get(ir.rootModule().moduleId()), true, loader);
        Object instance = facade.getMethod("$lyra$create").invoke(null);
        Object maker = facade.getMethod("value$maker").invoke(instance);
        Method make = maker.getClass().getMethod("invoke", int.class);
        make.setAccessible(true);
        Object counter = make.invoke(maker, 4);
        Method increment = counter.getClass().getMethod("invoke");
        increment.setAccessible(true);
        assertEquals(5, increment.invoke(counter));
        assertEquals(6, increment.invoke(counter));
    }

    private static ClassLoader defineAll(JvmBytecodeArtifact artifact) throws Exception {
        ClassLoader loader = new ClassLoader(Phase16SmokeTest.class.getClassLoader()) {
            Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        };
        Method define = loader.getClass().getDeclaredMethod("define", String.class, byte[].class);
        define.setAccessible(true);
        for (String name : artifact.classNames()) define.invoke(loader, name, artifact.bytes(name));
        return loader;
    }

    private static TypedIr lower(String source) {
        ModuleId id = ModuleId.path("phase16.lyra");
        return lower(new ModuleGraph(id, List.of(module(id, source)), List.of(), Map.of()));
    }

    private static TypedIr lower(ModuleGraph graph) {
        ResolvedSemanticGraph resolved = success(SemanticResolver.resolve(graph));
        TypedSemanticGraph typed = success(TypeChecker.check(resolved));
        return success(TypedIrBuilder.lower(typed));
    }

    private static ModuleGraph moduleGraph(ModuleId main, String mainSource,
                                           ModuleId dependency, String dependencySource) {
        LogicalModuleId logical = LogicalModuleId.fromSourceId(dependency.sourceId());
        ModuleGraph.Node mainNode = module(main, mainSource);
        ModuleGraph.Node dependencyNode = module(dependency, dependencySource);
        return new ModuleGraph(main,
                List.of(mainNode, dependencyNode),
                mainNode.program().imports().stream()
                        .map(importNode -> new ModuleGraph.Edge(main, logical, dependency,
                                importNode.path().span()))
                        .toList(),
                Map.of(logical, dependency));
    }

    private static ModuleGraph.Node module(ModuleId id, String source) {
        SourceSnapshot snapshot = success(SourceSnapshot.capture(id.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + id.value())),
                source.getBytes(StandardCharsets.UTF_8)));
        LexedSource lexed = success(Lexer.lex(snapshot));
        GrammarProgram grammar = success(GrammarMatcher.match(lexed));
        SyntaxProgram syntax = success(Parser.parse(lexed, grammar));
        return new ModuleGraph.Node(id,
                Optional.of(LogicalModuleId.fromSourceId(id.sourceId())), snapshot, syntax,
                ModuleRevision.compute(snapshot));
    }

    private static <T extends ImmutablePhaseArtifact> T success(PhaseResult<T> result) {
        if (result instanceof PhaseResult.Success<T> success) return success.value();
        throw new AssertionError(result.diagnostics());
    }
}
