package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.conformance.LanguageTestSupport;
import io.mindspice.lyra.runtime.LyraRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RangeIntegrationTest {
    @Test
    void nestedRangeBoundsEvaluateOnceInSourceOrder() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub range :Fn<;Tuple<Range<I32>,I32>> = (=> || {
                  let @mut trace :I32 = 0
                  let mark :Fn<I32;I32> = (=> |value| {
                    trace := (+ (* trace 10) value)
                    value
                  })
                  let saved :Range<I32> = ({ let start :I32 = (mark 1) start }..(mark 2):(mark 3))
                  Tuple[saved trace]
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            Object tuple = fixture.call("range", "Fn<;Tuple<Range<I32>,I32>>");
            var first = tuple.getClass().getDeclaredMethod("$lyra$get$0");
            var second = tuple.getClass().getDeclaredMethod("$lyra$get$1");
            first.setAccessible(true);
            second.setAccessible(true);
            assertEquals(new LyraRange(1, 2, 3, false, 32),
                    first.invoke(tuple));
            assertEquals(123, second.invoke(tuple));
        }
    }

    @Test
    void signedDomainsAndInvalidRangesHaveSourceDiagnostics() {
        for (String source : java.util.List.of("let r = (0..10:0)", "let r = (0.0..1.0:0.1)",
                "let r :Range<U32> = (0..10:1)", "let r :Range<@nil I32> = (0..10:1)",
                "let r :Range<String> = (0..10:1)", "let r :Range<I8> = (0..128:1)",
                "let r = (0....10:1)")) {
            var result = io.mindspice.lyra.compiler.api.LyraCompiler.compile(
                    io.mindspice.lyra.compiler.api.CompileRequest.source("invalid.lyra", source));
            var failure = assertInstanceOf(io.mindspice.lyra.compiler.api.CompileResult.Failure.class, result);
            LanguageTestSupport.diagnostics(failure.diagnostics(), source.length());
        }
    }

    @Test
    void seededRangeConstructionAndTraversalMatchIndependentIntegerModel() throws Throwable {
        var random = new java.util.Random(8675309);
        int samples = Integer.getInteger("lyra.fuzz.cases", 40);
        if (samples <= 0) throw new IllegalArgumentException("lyra.fuzz.cases must be positive");
        for (int bits : new int[]{8, 16, 32, 64}) {
            String element = "I" + bits;
            var artifact = LanguageTestSupport.compile("let @pub range :Fn<" + element + "," + element
                    + "," + element + ";Range<" + element + ">> = (=> |a b s| (a...b:s)) "
                    + "let @pub exclusive :Fn<" + element + "," + element + "," + element
                    + ";Range<" + element + ">> = (=> |a b s| (a..b:s))");
            try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
                for (int sample = 0; sample < samples; sample++) {
                    int start = random.nextInt(101) - 50;
                    int end = random.nextInt(101) - 50;
                    int step = random.nextInt(11) - 5;
                    if (step == 0) step = 1;
                    boolean inclusive = random.nextBoolean();
                    LyraRange range = (LyraRange) fixture.call(inclusive ? "range" : "exclusive", "Fn<" + element + "," + element
                                    + "," + element + ";Range<" + element + ">>",
                            integerBox(start, bits), integerBox(end, bits), integerBox(step, bits));
                    var expected = new java.util.ArrayList<Long>();
                    for (long value = start; step > 0 ? inclusive ? value <= end : value < end
                            : inclusive ? value >= end : value > end; value += step) {
                        expected.add(value);
                    }
                    var actual = new java.util.ArrayList<Long>();
                    if (!range.isEmpty()) {
                        long value = range.start();
                        do {
                            actual.add(value);
                            if (!range.hasSuccessor(value)) break;
                            value += range.step();
                        } while (actual.size() <= 101);
                    }
                    assertEquals(expected, actual, "seed=8675309 bits=" + bits + " sample=" + sample);
                }
            }
        }
    }

    private static Object integerBox(int value, int bits) {
        return switch (bits) {
            case 8 -> Byte.valueOf((byte) value);
            case 16 -> Short.valueOf((short) value);
            case 32 -> Integer.valueOf(value);
            default -> Long.valueOf(value);
        };
    }

    @Test
    void inferredAndStoredRangesRetainEvaluatedBounds() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub range :Fn<;Range<I64>> = (=> || {
                  let saved = (0...10:2)
                  saved
                })
                let @pub narrow :Fn<;Range<I8>> = (=> || {
                  let saved :Range<I8> = (100...0:(- 1))
                  saved
                })
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(new LyraRange(0, 10, 2, true, 64), fixture.call("range", "Fn<;Range<I64>>"));
            assertEquals(new LyraRange(100, 0, -1, true, 8), fixture.call("narrow", "Fn<;Range<I8>>"));
        }
    }

    @Test
    void runtimeZeroStepFailsAndExtremalSuccessorsTerminateWithoutOverflow() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub range :Fn<I64;Range<I64>> = (=> |step| (0..10:step))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertThrows(io.mindspice.lyra.runtime.LyraArithmeticException.class,
                    () -> fixture.call("range", "Fn<I64;Range<I64>>", 0L));
        }
        assertFalse(new LyraRange(Long.MAX_VALUE, Long.MAX_VALUE, 1, true, 64).hasSuccessor(Long.MAX_VALUE));
        assertFalse(new LyraRange(Long.MIN_VALUE, Long.MIN_VALUE, -1, true, 64).hasSuccessor(Long.MIN_VALUE));
    }

    @Test
    void rangeValuesCrossTypedJavaExports() throws Throwable {
        var artifact = LanguageTestSupport.compile("""
                let @pub range :Fn<I32,I32,I32;Range<I32>> = (=> |start end step| (start...end:step))
                """);
        try (var fixture = new LanguageTestSupport.Fixture(artifact)) {
            assertEquals(new LyraRange(0, 10, 2, true, 32),
                    fixture.call("range", "Fn<I32,I32,I32;Range<I32>>", 0, 10, 2));
        }
    }
}
