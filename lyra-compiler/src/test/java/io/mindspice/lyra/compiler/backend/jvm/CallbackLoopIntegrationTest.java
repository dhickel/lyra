package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.conformance.LanguageTestSupport;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CallbackLoopIntegrationTest {
    @Test
    void selectedMatchCallbacksAndExactHostRangeWidths() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<Range<I8>;I32> = (=> |range| {
                    let @mut n :I32 = 0
                    iter[range (match #T #T -> || { n := (+ n 1) } _ -> || {})]
                    n
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(3, fixture.call("run", "Fn<Range<I8>;I32>",
                    new io.mindspice.lyra.runtime.LyraRange(0, 3, 1, false, 8)));
            assertThrows(io.mindspice.lyra.runtime.LyraLinkException.class,
                    () -> fixture.call("run", "Fn<Range<I8>;I32>",
                            new io.mindspice.lyra.runtime.LyraRange(0, 3, 1, false, 64)));
        }
    }

    @Test
    void importedCallbacksExecuteWithTheirOwningModule() throws Throwable {
        var request = io.mindspice.lyra.compiler.api.CompileRequest.builder().rootModule("main")
                .resolver(io.mindspice.lyra.compiler.api.SourceResolver.memory(
                        ResolvedSource.memory(LogicalModuleId.parse("dep"), "memory:loops/dep", """
                                let @pub @mut count :I32 = 0
                                let @pub tick :Fn<;Unit> = (=> || { count := (+ count 1) })
                                """),
                        ResolvedSource.memory(LogicalModuleId.parse("main"), "memory:loops/main", """
                                import dep
                                let @pub run :Fn<;I32> = (=> || {
                                    iter[(0..3:1) dep->:.tick]
                                    dep->:.count
                                })
                                """))).build();
        try (var fixture = new LanguageTestSupport.Fixture(LanguageTestSupport.compile(request))) {
            assertEquals(3, fixture.call("run", "Fn<;I32>"));
        }
    }

    @Test
    void repeatedCallbacksCannotAcquireImportedMutationAuthority() {
        for (boolean nested : new boolean[]{false, true}) {
            String body = "let @mut target :Array<I32> = Array[0] "
                    + "iter[(0..3:1) || { target[0] := 1 target := dep->:.values }]";
            String source = "import dep " + (nested
                    ? "let run :Fn<;Unit> = (=> || { " + body + " }) (run)" : body);
            var request = io.mindspice.lyra.compiler.api.CompileRequest.builder().rootModule("main")
                    .resolver(io.mindspice.lyra.compiler.api.SourceResolver.memory(
                            ResolvedSource.memory(LogicalModuleId.parse("main"), "memory:loop/main", source),
                            ResolvedSource.memory(LogicalModuleId.parse("dep"), "memory:loop/dep",
                                    "let @pub values :Array<I32> = Array[0]"))).build();
            var result = io.mindspice.lyra.compiler.api.LyraCompiler.compile(request);
            var failure = assertInstanceOf(io.mindspice.lyra.compiler.api.CompileResult.Failure.class, result);
            assertTrue(failure.diagnostics().stream().anyMatch(diagnostic -> diagnostic.summary().contains("import")),
                    failure.toString());
        }
    }

    @Test
    void changesInsideLoopAreReturnedThroughAnEnclosingFunction() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let choose :Fn<;Fn<;I32>> = (=> || {
                    let @mut selected :Fn<;I32> = (=> || 1)
                    let replacement :Fn<;I32> = (=> || 9)
                    iter[(0..2:1) || { selected := replacement }]
                    selected
                })
                let @pub run :Fn<;I32> = (=> || { let selected = (choose) (selected) })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(9, fixture.call("run", "Fn<;I32>"));
        }
    }
    @Test
    void loopParametersAreFreshBindingsAndStoredCallbacksKeepTheirIdentity() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I64> = (=> || {
                    let zero :Fn<;I64> = (=> || 0)
                    let @mut saved :Array<Fn<;I64>> = Array[zero zero zero]
                    iter[(0..3:1) |x| { saved[x] := (=> :I64 || x) }]
                    (+ (saved[0]) (* 10 (saved[1])) (* 100 (saved[2])))
                })
                let @pub retained :Fn<;I32> = (=> || {
                    let @mut n :I32 = 0
                    let replacement :Fn<;Unit> = (=> || { n := (+ n 10) })
                    let @mut action :Fn<;Unit> = (=> || { n := (+ n 1) })
                    while[|| { action := replacement (< n 3) } action]
                    n
                })
                let @pub selfReplace :Fn<;I32> = (=> || {
                    let @mut n :I32 = 0
                    let replacement :Fn<;Unit> = (=> || { n := (+ n 10) })
                    let @mut action :Fn<;Unit> = (=> || { n := (+ n 1) action := replacement })
                    while[|| (< n 3) action]
                    n
                })
                let @pub invokeSelfReplace :Fn<;I32> = (=> || (selfReplace))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(210L, fixture.call("run", "Fn<;I64>"));
            assertEquals(3, fixture.call("retained", "Fn<;I32>"));
            assertEquals(3, fixture.call("selfReplace", "Fn<;I32>"));
            assertEquals(3, fixture.call("invokeSelfReplace", "Fn<;I32>"));
        }
    }

    @Test
    void computedCallbacksAndNarrowComputedBoundsWork() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<I32;I32> = (=> |bound| {
                    let @mut total :I32 = 0
                    let range = ((+ bound 0)..(+ bound 3):1)
                    iter[range (#T -> |x| { total := (+ total x) } : |y| {})]
                    total
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(6, fixture.call("run", "Fn<I32;I32>", 1));
        }
    }

    @Test
    void terminalLongEndpointsNeverIncrementPastTheSignedDomain() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I32> = (=> || {
                    let @mut n :I32 = 0
                    iter[(9223372036854775806...9223372036854775807:1) || { n := (+ n 1) }]
                    iter[((- 9223372036854775807)...(- 9223372036854775808):(- 1)) || { n := (+ n 1) }]
                    n
                })
                let @pub longLoop :Fn<;I32> = (=> || {
                    let @mut n :I32 = 0
                    while[|| (< n 1000000) || { n := (+ n 1) }]
                    n
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(4, fixture.call("run", "Fn<;I32>"));
            assertEquals(1000000, fixture.call("longLoop", "Fn<;I32>"));
        }
    }

    @Test
    void rangeLoopsMatchIndependentModelAtEveryWidth() throws Throwable {
        for (int bits : new int[]{8, 16, 32, 64}) {
            String type = "I" + bits;
            var artifact = LanguageTestSupport.compile("""
                    let @pub run :Fn<%1$s,%1$s,%1$s;I64> = (=> |a b s| {
                        let @mut sum :I64 = 0
                        iter[(a..b:s) |x| { sum := (+ sum I64[x]) }]
                        sum
                    })
                    let @pub inclusive :Fn<%1$s,%1$s,%1$s;I64> = (=> |a b s| {
                        let @mut sum :I64 = 0
                        (iter (a...b:s) |x| { sum := (+ sum I64[x]) })
                        sum
                    })
                    """.formatted(type));
            var random = new java.util.Random(20260911 + bits);
            int samples = Integer.getInteger("lyra.fuzz.cases", 40);
            assertTrue(samples > 0);
            try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
                for (int i = 0; i < samples; i++) {
                    int a = random.nextInt(101) - 50;
                    int b = random.nextInt(101) - 50;
                    int s = random.nextInt(10) - 5;
                    if (s == 0) s = 1;
                    boolean inclusive = random.nextBoolean();
                    long expected = 0;
                    for (long x = a; s > 0 ? inclusive ? x <= b : x < b : inclusive ? x >= b : x > b; x += s) expected += x;
                    assertEquals(expected, fixture.call(inclusive ? "inclusive" : "run",
                            "Fn<" + type + "," + type + "," + type + ";I64>", box(a, bits), box(b, bits), box(s, bits)),
                            "seed=" + (20260911 + bits) + " sample=" + i);
                }
            }
        }
    }

    private static Object box(long n, int bits) {
        return switch (bits) {
            case 8 -> Byte.valueOf((byte) n);
            case 16 -> Short.valueOf((short) n);
            case 32 -> Integer.valueOf((int) n);
            default -> Long.valueOf(n);
        };
    }

    @Test
    void nestedAndZeroArgumentCallbacksReuseRanges() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I64> = (=> || {
                    let @mut n :I64 = 0
                    let range = (3...1:(- 1))
                    let tick :Fn<;Unit> = (=> || { n := (+ n 1) })
                    iter[range || iter[range tick]]
                    (iter range (=> || { n := (+ n 1) }))
                    n
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(12L, fixture.call("run", "Fn<;I64>"));
        }
    }

    @Test
    void whileSelectsCallbacksOnceAndIncludesTerminalPredicateEffects() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<I32;I32> = (=> |limit| {
                    let @mut n :I32 = 0
                    let @mut tests :I32 = 0
                    let @mut construction :I32 = 0
                    let predicate :Fn<;Bool> = (=> || { tests := (+ tests 1) (< n limit) })
                    let @mut action :Fn<;Unit> = (=> || { n := (+ n 1) })
                    (while { construction := (+ (* construction 10) 1) predicate }
                           { construction := (+ (* construction 10) 2) action })
                    (+ (* construction 10000) (* tests 100) n)
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(120100, fixture.call("run", "Fn<I32;I32>", 0));
            assertEquals(120403, fixture.call("run", "Fn<I32;I32>", 3));
            var random = new java.util.Random(20260912);
            int samples = Integer.getInteger("lyra.fuzz.cases", 40);
            for (int i = 0; i < samples; i++) {
                int limit = random.nextInt(101);
                assertEquals(120000 + (limit + 1) * 100 + limit,
                        fixture.call("run", "Fn<I32;I32>", limit), "seed=20260912 sample=" + i);
            }
        }
    }

    @Test
    void rejectsInvalidCallbackContracts() {
        for (String source : java.util.List.of(
                "iter[(0..10:1) || 2]", "iter[(0..10:1) |x y| {}]",
                "iter[1 || {}]", "iter[(0..10:1)]", "while[|| 1 || {}]",
                "while[|| #F || 1]", "while[|x| #F || {}]", "while[]")) {
            var result = io.mindspice.lyra.compiler.api.LyraCompiler.compile(
                    io.mindspice.lyra.compiler.api.CompileRequest.source("invalid.lyra", source));
            var failure = assertInstanceOf(io.mindspice.lyra.compiler.api.CompileResult.Failure.class, result, source);
            LanguageTestSupport.diagnostics(failure.diagnostics(), source.length());
        }
    }

    @Test
    void iterAndWhileExecuteWithSharedMutableCaptures() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub run :Fn<;I64> = (=> || {
                    let @mut total :I64 = 0
                    iter[(0..10:1) |x| { total := (+ total x) }]
                    while[|| (< total 50) || { total := (+ total 1) }]
                    total
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(50L, fixture.call("run", "Fn<;I64>"));
        }
    }
}
