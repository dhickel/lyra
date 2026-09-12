package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.NominalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-generation execution and bounded snapshot coverage for nominal values. */
class NominalSessionTest {
    @Test
    void retainedTypeNamesConstructTheExactOriginalNominalType() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "type-1.lyra", "struct Point { let value :I32 }")));
            success(session.submit(EvaluationSource.of(
                    "type-2.lyra", "let point :Point = Point[23]")));
            EvaluationResult.Success result = success(
                    session.submit(EvaluationSource.of("type-3.lyra", "point:.value")));
            assertEquals("23", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesExecuteOriginalInitializersAndConstructor() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("factory-1.lyra", """
                    class Counter {
                        let @pub @mut value :I32 = 0
                        let @pub read :Fn<;I32> = (=> || self:.value)
                        Counter = (=> |start :I32| { self:.value := start })
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "factory-2.lyra", "let fresh :Counter = Counter[41]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "factory-3.lyra", "fresh::read[]")));
            assertEquals("41", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveAggregateDefaultProvenance() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("aggregate-factory-1.lyra", """
                    class Bag { let @pub values :Array<I32> = Array<I32>[1 2] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "aggregate-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "aggregate-factory-3.lyra", "bag:.values[1]")));
            assertEquals("2", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveNestedAggregateDefaultProvenance() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nested-aggregate-factory-1.lyra", """
                    class Bag { let @pub values :Tuple<Array<I32>,I32> = Tuple[Array<I32>[3 4] 5] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-aggregate-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nested-aggregate-factory-3.lyra", "bag:.values:.0[1]")));
            assertEquals("4", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveAggregateDefaultAliases() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("aggregate-alias-factory-1.lyra", """
                    class Bag {
                        let @pub values :Array<I32> = Array<I32>[8 9]
                        let @pub alias :Array<I32> = self:.values
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "aggregate-alias-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "aggregate-alias-factory-3.lyra", "bag:.alias[1]")));
            assertEquals("9", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveAggregateReturningDefaultCalls() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("aggregate-call-factory-1.lyra", """
                    let make :Fn<;Array<I32>> = (=> || Array<I32>[10 11])
                    class Bag { let @pub values :Array<I32> = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "aggregate-call-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "aggregate-call-factory-3.lyra", "bag:.values[1]")));
            assertEquals("11", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveCallableReturningDefaultCalls() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("callable-call-factory-1.lyra", """
                    let make :Fn<;Fn<;I32>> = (=> || (=> || 14))
                    class Box { let @pub read :Fn<;I32> = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "callable-call-factory-2.lyra", "let box :Box = Box[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "callable-call-factory-3.lyra", "box::read[]")));
            assertEquals("14", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedConstructorsPreserveAggregateFieldWrites() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-aggregate-1.lyra", """
                    class Bag {
                        let @pub values :Array<I32>
                        Bag = (=> |values :Array<I32>| { self:.values := values })
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "constructor-aggregate-2.lyra", "let bag :Bag = Bag[Array<I32>[6 7]]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "constructor-aggregate-3.lyra", "bag:.values[1]")));
            assertEquals("7", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedConstructorsTransferCapturedStateEffects() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-effect-1.lyra", """
                    let @mut selected :Fn<;I32> = (=> || 0)
                    class Box { Box = (=> || { selected := (=> || 12) }) }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "constructor-effect-2.lyra", "let box :Box = Box[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "constructor-effect-3.lyra", "::selected[]")));
            assertEquals("12", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void objectStateAndReplacedMethodsPersistAcrossThreeSubmissions() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "nominal-1.lyra", """
                            class Counter {
                                let @pub @mut value :I32 = 1
                                let @pub @mut read :Fn<;I32> = (=> || self:.value)
                            }
                            let @mut counter :Counter = Counter[]
                            let saved :Fn<;I32> = counter:.read
                            """)));

            success(session.submit(EvaluationSource.of(
                    "nominal-2.lyra", """
                            counter:.value := 7
                            counter:.read := (=> || (+ self:.value 10))
                            """)));

            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nominal-3.lyra", "(+ (* (saved) 100) counter::read[])")));
            ValueSnapshot snapshot = result.value().orElseThrow();
            assertEquals("I32", snapshot.canonicalType());
            assertEquals("717", assertInstanceOf(ValueSnapshot.Scalar.class, snapshot.data()).value());
            assertTrue(session.workspaceState().bindings().get("counter").type().baseType()
                    instanceof NominalType);
        }
    }

    @Test
    void nominalSnapshotsExposeStructDataButNotPrivateClassState() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.Success structResult = success(session.submit(EvaluationSource.of(
                    "snapshot-struct.lyra", "struct Pair { let left :I32 let right :I32 } Pair[2 3]")));
            ValueSnapshot.Aggregate struct = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    structResult.value().orElseThrow().data());
            assertEquals(AggregateKind.STRUCT, struct.kind());
            assertEquals("Pair", struct.alias().orElseThrow());
            assertEquals(List.of("2", "3"), struct.elements().stream()
                    .map(ValueSnapshot::data).map(ValueSnapshot.Scalar.class::cast)
                    .map(ValueSnapshot.Scalar::value).toList());

            EvaluationResult.Success classResult = success(session.submit(EvaluationSource.of(
                    "snapshot-class.lyra", "class Secret { let value :I32 = 9 } Secret[]")));
            ValueSnapshot.Aggregate clazz = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    classResult.value().orElseThrow().data());
            assertEquals(AggregateKind.CLASS, clazz.kind());
            assertEquals("Secret", clazz.alias().orElseThrow());
            assertTrue(clazz.elements().isEmpty());
        }
    }

    private static EvaluationResult.Success success(EvaluationResult result) {
        return assertInstanceOf(EvaluationResult.Success.class, result, result::toString);
    }
}
