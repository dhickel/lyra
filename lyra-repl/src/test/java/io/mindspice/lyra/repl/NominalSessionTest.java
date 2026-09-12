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
