package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.conformance.LanguageTestSupport;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchBoundaryIntegrationTest {
    @Test
    void armArrowsBeforeDirectCallsAreNotNamespaceQualification() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let answer :Fn<;I32> = (=> || 42)
                let @pub run :Fn<I32;I32> = (=> |value| {
                  let expected :I32 = 10
                  (match value
                    expected -> ::answer[]
                    _ -> 0)
                })
                let @pub conditional :Fn<Bool;I32> = (=> |enabled|
                  (cond enabled -> ::answer[] _ -> 0))
                let @pub bracketed :Fn<Bool;I32> = (=> |enabled|
                  cond[enabled -> ::answer[] _ -> 0])
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(42, fixture.call("run", "Fn<I32;I32>", 10));
            assertEquals(0, fixture.call("run", "Fn<I32;I32>", 11));
            assertEquals(42, fixture.call("conditional", "Fn<Bool;I32>", true));
            assertEquals(0, fixture.call("conditional", "Fn<Bool;I32>", false));
            assertEquals(42, fixture.call("bracketed", "Fn<Bool;I32>", true));
            assertEquals(0, fixture.call("bracketed", "Fn<Bool;I32>", false));
        }
    }

    @Test
    void bracketMatchArgumentsComposeWithoutChangingLazyEvaluationOrFormOrder() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub nested :Fn<U32;U32> = (=> |a|
                  ((=> :U32 |value :U32| value)
                    match[a 1U32 -> match[a _ -> 7U32] _ -> a]))
                let @pub ordered :Fn<;I32> = (=> || {
                  let @mut trace :I32 = 0
                  let mark :Fn<I32;I32> = (=> |value| {
                    trace := (+ (* trace 10) value) value
                  })
                  let add :Fn<I32,I32;I32> = (=> |a b| (+ a b))
                  let result :I32 = ::add[(mark 1)
                    match[(mark 2)
                      (mark 3) when (mark 9) -> (mark 9)
                      (mark 2) when (mark 4) -> (mark 5)
                      _ -> (mark 9)]]
                  (cond #F -> (mark 9) _ -> (mark 6))
                  (+ trace result)
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(7, fixture.call("nested", "Fn<U32;U32>", 1));
            assertEquals(-1, fixture.call("nested", "Fn<U32;U32>", -1));
            assertEquals(1_232_462, fixture.call("ordered", "Fn<;I32>"));
        }
    }

    @Test
    void identifierConditionalPredicateCanSelectADirectMatchExpression() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<Bool;I32> = (=> |enabled|
                  (enabled -> (cond #T -> 42 _ -> 0) : 7))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(42, fixture.call("run", "Fn<Bool;I32>", true));
            assertEquals(7, fixture.call("run", "Fn<Bool;I32>", false));
        }
    }

    @Test
    void namespacePatternsAndGuardsMayHavePostfixAccessors() throws Throwable {
        var request = CompileRequest.builder().rootModule("main").resolver(SourceResolver.memory(
                ResolvedSource.memory(LogicalModuleId.parse("constants"), "memory:match/constants", """
                        let @pub values :Array<I32> = Array[7]
                        let @pub pair :Fn<;Tuple<I32,Bool>> = (=> || Tuple[8 #T])
                        """),
                ResolvedSource.memory(LogicalModuleId.parse("main"), "memory:match/main", """
                        import constants as c
                        let @pub run :Fn<I32;I32> = (=> |value|
                          (match value
                            c->:.values[0] -> 7
                            c->::pair[]:.0 when c->::pair[]:.1 -> 8
                            _ -> 0))
                        """))).build();
        try (var fixture = new LanguageTestSupport.Fixture(LanguageTestSupport.compile(request))) {
            assertEquals(7, fixture.call("run", "Fn<I32;I32>", 7));
            assertEquals(8, fixture.call("run", "Fn<I32;I32>", 8));
            assertEquals(0, fixture.call("run", "Fn<I32;I32>", 9));
        }
    }

    @Test
    void fullModulePathsMayEndDirectlyInAccessorsInsideMatchHeads() throws Throwable {
        var request = CompileRequest.builder().rootModule("main").resolver(SourceResolver.memory(
                ResolvedSource.memory(LogicalModuleId.parse("game->constants"), "memory:match/game/constants", """
                        let @pub values :Array<I32> = Array[7]
                        let @pub pair :Fn<;Tuple<I32,Bool>> = (=> || Tuple[8 #T])
                        """),
                ResolvedSource.memory(LogicalModuleId.parse("main"), "memory:match/main", """
                        import game->constants
                        let @pub run :Fn<I32;I32> = (=> |value|
                          (match value
                            game->constants:.values[0] -> 7
                            game->constants::pair[]:.0 when game->constants::pair[]:.1 -> 8
                            _ -> 0))
                        """))).build();
        try (var fixture = new LanguageTestSupport.Fixture(LanguageTestSupport.compile(request))) {
            assertEquals(7, fixture.call("run", "Fn<I32;I32>", 7));
            assertEquals(8, fixture.call("run", "Fn<I32;I32>", 8));
            assertEquals(0, fixture.call("run", "Fn<I32;I32>", 9));
        }
    }

    @Test
    void numericPatternComparisonDoesNotRetypeTheStoredSubjectPerArm() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I32> = (=> ||
                  (match 1 1U8 -> 42 _ -> 0))
                let @pub wide :Fn<I64;I32> = (=> |value|
                  (match value 1U8 -> 1 2I32 -> 2 _ -> 3))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(42, fixture.call("run", "Fn<;I32>"));
            assertEquals(1, fixture.call("wide", "Fn<I64;I32>", 1L));
            assertEquals(2, fixture.call("wide", "Fn<I64;I32>", 2L));
            assertEquals(3, fixture.call("wide", "Fn<I64;I32>", Long.MAX_VALUE));
        }
    }

    @Test
    void unsignedSubjectsUseLosslessLogicalWideningForEachPattern() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub byteCase :Fn<U8;I32> = (=> |value|
                  (match value 255I16 -> 1 _ -> 0))
                let @pub shortCase :Fn<U16;I32> = (=> |value|
                  (match value 65535I32 -> 2 _ -> 0))
                let @pub intCase :Fn<U32;I32> = (=> |value|
                  (match value 4294967295I64 -> 3 _ -> 0))
                let @pub floatCase :Fn<U32;I32> = (=> |value|
                  (match value 4294967295.0F64 -> 4 _ -> 0))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(1, fixture.call("byteCase", "Fn<U8;I32>", (byte) 255));
            assertEquals(2, fixture.call("shortCase", "Fn<U16;I32>", (short) 65535));
            assertEquals(3, fixture.call("intCase", "Fn<U32;I32>", -1));
            assertEquals(4, fixture.call("floatCase", "Fn<U32;I32>", -1));
        }
    }

    @Test
    void logicalComparisonConversionCoversSignedFloatingAndNilDomains() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub signed :Fn<I8;I32> = (=> |value|
                  (match value 127I16 -> 1 _ -> 0))
                let @pub integerToFloat :Fn<I32;I32> = (=> |value|
                  (match value 16777217.0F64 -> 2 _ -> 0))
                let @pub floatToFloat :Fn<F32;I32> = (=> |value|
                  (match value 1.5F64 -> 3 _ -> 0))
                let @pub nilCase :Fn<;I32> = (=> || {
                  let @nil value :F32 = #NIL
                  (match value #NIL -> 4 _ -> 0)
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(1, fixture.call("signed", "Fn<I8;I32>", (byte) 127));
            assertEquals(0, fixture.call("signed", "Fn<I8;I32>", (byte) -1));
            assertEquals(2, fixture.call("integerToFloat", "Fn<I32;I32>", 16_777_217));
            assertEquals(0, fixture.call("integerToFloat", "Fn<I32;I32>", 16_777_216));
            assertEquals(3, fixture.call("floatToFloat", "Fn<F32;I32>", 1.5f));
            assertEquals(0, fixture.call("floatToFloat", "Fn<F32;I32>", 1.25f));
            assertEquals(4, fixture.call("nilCase", "Fn<;I32>"));
        }
    }

    @Test
    void inferredAggregateMatchUsesContextualNumericBranchType() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;U8> = (=> || {
                  let values = Tuple[(match 0I32 0I32 -> 1 _ -> 2U8)]
                  values:.0
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals((byte) 1, fixture.call("run", "Fn<;U8>"));
        }
    }

    @Test
    void inferredArrayPeersPropagateNestedMatchNumericTypes() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;U8> = (=> || {
                  let values = Array[
                    (cond #T -> 1 _ -> 2U8)
                    (match 0I32 1I32 -> 3U8 _ -> 4)
                  ]
                  values[1]
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals((byte) 4, fixture.call("run", "Fn<;U8>"));
        }
    }

    @Test
    void unsignedSwitchCandidatesCompareMathematicalValues() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub byteCase :Fn<U8;I32> = (=> |value|
                  (match value 200U8 -> 1 255U8 -> 2 _ -> 3))
                let @pub shortCase :Fn<U16;I32> = (=> |value|
                  match[value 40000U16 -> 1 65535U16 -> 2 _ -> 3])
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(1, fixture.call("byteCase", "Fn<U8;I32>", (byte) 200));
            assertEquals(2, fixture.call("byteCase", "Fn<U8;I32>", (byte) 255));
            assertEquals(3, fixture.call("byteCase", "Fn<U8;I32>", (byte) 0));
            assertEquals(1, fixture.call("shortCase", "Fn<U16;I32>", (short) 40000));
            assertEquals(2, fixture.call("shortCase", "Fn<U16;I32>", (short) 65535));
            assertEquals(3, fixture.call("shortCase", "Fn<U16;I32>", (short) 0));
        }
    }

    @Test
    void fallbackOnlyStillEvaluatesSubjectAndWildcardIsNotAny() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I32> = (=> || {
                  let @mut count :I32 = 0
                  let _ :I32 = 99
                  let result :I32 = (match { count := (+ count 1) 10I32 }
                    _ -> _)
                  (+ result count)
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(100, fixture.call("run", "Fn<;I32>"));
        }
    }

    @Test
    void wildcardRecognitionDoesNotConsumeAnUnderscoreRootedValueExpression() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I32> = (=> || {
                  let _ :Array<I32> = Array[7]
                  (match _:.length
                    _[0] -> 0
                    1 -> 42
                    _ -> 1)
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(42, fixture.call("run", "Fn<;I32>"));
        }
    }

    @Test
    void traditionalMatchUsesExistingNumericNilAndStructuralEquality() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub numeric :Fn<I16;I32> = (=> |value|
                  (match value 2I32 -> 20 _ -> 0))
                let @pub nilValue :Fn<;I32> = (=> || {
                  let @nil value :I32 = #NIL
                  (match value #NIL -> 30 _ -> 0)
                })
                let @pub aggregate :Fn<Array<I32>;I32> = (=> |value|
                  (match value Array[1 2] -> 40 _ -> 0))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(20, fixture.call("numeric", "Fn<I16;I32>", (short) 2));
            assertEquals(0, fixture.call("numeric", "Fn<I16;I32>", (short) 3));
            assertEquals(30, fixture.call("nilValue", "Fn<;I32>"));
            assertEquals(40, fixture.call("aggregate", "Fn<Array<I32>;I32>", new int[]{1, 2}));
            assertEquals(0, fixture.call("aggregate", "Fn<Array<I32>;I32>", new int[]{2, 1}));
        }
    }

    @Test
    void computedPatternsAndFailedGuardsKeepSourceOrder() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I32> = (=> || {
                  let @mut trace :I32 = 0
                  let result :I32 = (match { trace := (+ (* trace 10) 1) 7I32 }
                    { trace := (+ (* trace 10) 2) 6I32 }
                      when { trace := 999 #T } -> 90
                    { trace := (+ (* trace 10) 3) 7I32 }
                      when { trace := (+ (* trace 10) 4) #F } -> 91
                    _ when { trace := (+ (* trace 10) 5) #T }
                      -> { trace := (+ (* trace 10) 6) 8 }
                    { trace := 999 7I32 } -> 92
                    _ -> 93)
                  (+ trace result)
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(123464, fixture.call("run", "Fn<;I32>"));
        }
    }
}
