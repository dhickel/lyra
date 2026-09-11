package io.mindspice.lyra.compiler.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class FuzzInfrastructureTest {
    @TempDir Path temp;

    @Test void nonCooperativeWorkerIsKilledAndItsCheckpointSurvives() throws Exception {
        var result = FuzzProcess.run(List.of("--probe", "hang", temp.toString()), temp,
                Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertTrue(result.timedOut());
        assertEquals("mutation", FuzzCase.read(temp.resolve("current.properties")).get("mode"));
        assertEquals("let x = 1", FuzzCase.read(temp.resolve("current.properties")).get("source"));
    }

    @Test void replayPreservesUnicodeInputsArgumentsAndExpectedFailures() throws Throwable {
        FuzzCase test = LanguageFuzzWorker.numeric(new SplittableRandom(57), NumericModel.U64);
        test.put("comment", "😀\n\\uD800\u0000");
        test.save(temp);
        FuzzCase loaded = FuzzCase.read(temp.resolve("current.properties"));
        assertEquals(test.data, loaded.data);
        LanguageFuzzWorker.run(loaded);
    }

    @Test void matchReplayRetainsExactSourceInputsValuesAndFailures() throws Exception {
        FuzzCase generated = null;
        for (long seed = 0; seed < 1_000 && generated == null; seed++) {
            FuzzCase candidate = LanguageFuzzWorker.match(new SplittableRandom(seed));
            boolean hasValue = false, hasFailure = false;
            for (int input = 0; input < 6; input++) {
                hasValue |= candidate.get("expected." + input) != null;
                hasFailure |= candidate.get("failure." + input) != null;
            }
            if (hasValue && hasFailure) generated = candidate;
        }
        assertNotNull(generated, "Deterministic match seeds should include modeled values and runtime failures");
        Path directory = temp.resolve("match-replay");
        generated.save(directory);
        FuzzCase loaded = FuzzCase.read(directory.resolve("current.properties"));
        assertEquals(generated.data, loaded.data);
        assertEquals(generated.get("source"), Files.readString(directory.resolve("current.lyra")));
        assertEquals("match", loaded.get("mode"));
        assertNotNull(loaded.get("a.0"));
        assertTrue(loaded.data.stringPropertyNames().stream().anyMatch(name -> name.startsWith("expected.")));
        assertTrue(loaded.data.stringPropertyNames().stream().anyMatch(name -> name.startsWith("failure.")));
    }

    @Test void moduleMatchReplaysCoverFullPathsAndBothTerminalAccessorSpellings() throws Throwable {
        var covered = new java.util.HashSet<String>();
        for (long seed = 0; seed < 12; seed++) {
            FuzzCase generated = LanguageFuzzWorker.modules(new SplittableRandom(seed));
            Path directory = temp.resolve("module-match-" + seed);
            generated.save(directory);
            FuzzCase loaded = FuzzCase.read(directory.resolve("current.properties"));
            assertEquals(generated.data, loaded.data);
            String source = loaded.get("source");
            assertTrue(loaded.get("moduleName.0").startsWith("group->"));
            covered.add(loaded.get("moduleName.0").contains("->nested->") ? "deep" : "shallow");
            covered.add(source.contains("->:.value") ? "terminal-arrow" : "terminal-adjacent");
            covered.add(source.contains("::match[") ? "bracket" : "prefix");
            LanguageFuzzWorker.run(loaded);
        }
        assertEquals(java.util.Set.of("deep", "shallow", "terminal-arrow", "terminal-adjacent", "bracket", "prefix"),
                covered);

        FuzzCase legacy = new FuzzCase("modules", "let @pub run :Fn<;I32> = (=> || 42)");
        legacy.put("moduleCount", 1);
        legacy.put("module.0", legacy.get("source"));
        legacy.put("expected", 42);
        LanguageFuzzWorker.run(legacy); // Existing replays without moduleName fields retain their original identity.
    }

    @Test void generatorSeedsAreStableAndInvalidBudgetsFailInsteadOfSkipping() {
        assertEquals(LanguageFuzzWorker.numeric(new SplittableRandom(123), NumericModel.I32).data,
                LanguageFuzzWorker.numeric(new SplittableRandom(123), NumericModel.I32).data);
        System.setProperty("lyra.fuzz.infrastructureBudget", "0");
        try { assertThrows(IllegalArgumentException.class,
                () -> LanguageFuzzTest.integerProperty("lyra.fuzz.infrastructureBudget", 120, 60, 1000)); }
        finally { System.clearProperty("lyra.fuzz.infrastructureBudget"); }
    }

    @Test void minimumCampaignBudgetBalancesEveryModeAndNumericKind() {
        assertEquals(110, LanguageFuzzWorker.MINIMUM_CASES);
        int[] modeCounts = new int[LanguageFuzzWorker.MODES.size()];
        EnumSet<NumericModel> numericKinds = EnumSet.noneOf(NumericModel.class);
        for (int index = 0; index < LanguageFuzzWorker.MINIMUM_CASES; index++) {
            String mode = LanguageFuzzWorker.modeAt(index);
            modeCounts[LanguageFuzzWorker.MODES.indexOf(mode)]++;
            if (mode.equals("numeric")) numericKinds.add(LanguageFuzzWorker.numericTypeAt(index));
        }
        for (int count : modeCounts) assertEquals(10, count, "Minimum budget must balance every mode");
        assertEquals(EnumSet.allOf(NumericModel.class), numericKinds);
        assertThrows(IllegalArgumentException.class, () -> LanguageFuzzWorker.numericTypeAt(1));

        System.setProperty("lyra.fuzz.minimumBudget", "99");
        try {
            assertThrows(IllegalArgumentException.class, () -> LanguageFuzzTest.integerProperty(
                    "lyra.fuzz.minimumBudget", 180, LanguageFuzzWorker.MINIMUM_CASES, 1_000_000));
        } finally {
            System.clearProperty("lyra.fuzz.minimumBudget");
        }
    }

    @Test void recursiveNumericMatchShapeHasKnownSourceAndLazyIndependentModelForEveryType() {
        assertEquals(EnumSet.range(TypedProgramGenerator.Shape.BINARY, TypedProgramGenerator.Shape.MATCH),
                EnumSet.copyOf(TypedProgramGenerator.recursiveShapes()));
        var a = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.A, "", null, null);
        var b = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.B, "", null, null);
        var match = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.MATCH, "", a, b);
        assertEquals("(match a ?? ((< a b) -> a : b) -> a ?? _ -> b)",
                match.source(NumericModel.I32, false));
        assertEquals("::match[a ?? ((< a b) -> a : b) -> a ?? _ -> b]",
                match.source(NumericModel.I32, true));

        var nested = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.MATCH, "", match, match);
        assertEquals("(match a ?? ((< a b) -> a : b) -> "
                + "(match a ?? ((< a b) -> a : b) -> a ?? _ -> b) ?? _ -> "
                + "(match a ?? ((< a b) -> a : b) -> a ?? _ -> b))",
                nested.source(NumericModel.I32, false));
        assertEquals("::match[a ?? ((< a b) -> a : b) -> "
                + "::match[a ?? ((< a b) -> a : b) -> a ?? _ -> b] ?? _ -> "
                + "::match[a ?? ((< a b) -> a : b) -> a ?? _ -> b]]",
                nested.source(NumericModel.I32, true));

        for (NumericModel type : NumericModel.values()) {
            Object one = type.decode("1");
            Object two = type.decode("2");
            assertEquals(one, match.evaluate(type, one, one), type.toString());
            assertEquals(one, match.evaluate(type, one, two), type.toString());
            assertEquals(one, match.evaluate(type, two, one), type.toString());
            assertEquals(one, nested.evaluate(type, one, two), type.toString());
            assertTrue(TypedProgramGenerator.program(nested, type).contains("Fn<" + type + "," + type + ";" + type + ">"));
            String generated = LanguageFuzzWorker.numeric(new SplittableRandom(1000 + type.ordinal()), type).get("source");
            assertTrue(generated.contains("(match a ?? ((< a b) -> a : b) ->"),
                    "Prefix numeric match missing for " + type);
            assertTrue(generated.contains("::match[a ?? ((< a b) -> a : b) ->"),
                    "Bracket numeric match missing for " + type);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TypedProgramGenerator.generateMatch(new SplittableRandom(1), NumericModel.I32, 0));

        var integerTrap = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.BINARY, "%", a, b);
        var lazyInteger = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.MATCH, "", a, integerTrap);
        assertEquals(0, lazyInteger.evaluate(NumericModel.I32, 0, 0));
        assertEquals("LYR-ARITH", assertThrows(NumericModel.Trap.class,
                () -> lazyInteger.evaluate(NumericModel.I32, 7, 0)).code);

        var floatTrap = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.BINARY, "/", a, b);
        var lazyFloat = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.MATCH, "", a, floatTrap);
        assertEquals(0.0f, lazyFloat.evaluate(NumericModel.F32, 0.0f, 0.0f));
        assertEquals("LYR-ARITH", assertThrows(NumericModel.Trap.class,
                () -> lazyFloat.evaluate(NumericModel.F32, 1.0f, 0.0f)).code);
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(
                (Float) match.evaluate(NumericModel.F32, -0.0f, 0.0f)));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(
                (Double) match.evaluate(NumericModel.F64, -0.0d, 0.0d)));
    }

    @Test void generatedIdentifierConditionalsCanSelectNestedMatchWithoutEvaluatingOtherBranches() throws Throwable {
        var a = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.A, "", null, null);
        var b = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.B, "", null, null);
        var match = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.MATCH, "", a, b);
        var trap = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.BINARY, "%", a, b);
        var conditional = new TypedProgramGenerator.Expr(TypedProgramGenerator.Shape.CONDITIONAL, "", match, trap);
        assertEquals("{ let less :Bool = (< a b) (less -> "
                        + "::match[a ?? ((< a b) -> a : b) -> a ?? _ -> b] : %[a, b]) }",
                conditional.source(NumericModel.I32, true));
        assertEquals(-1, conditional.evaluate(NumericModel.I32, -1, 0));
        assertEquals(1, conditional.evaluate(NumericModel.I32, 3, 2));
        assertEquals("LYR-ARITH", assertThrows(NumericModel.Trap.class,
                () -> conditional.evaluate(NumericModel.I32, 1, 0)).code);
        try (var fixture = new LanguageTestSupport.Fixture(LanguageTestSupport.compile(
                TypedProgramGenerator.program(conditional, NumericModel.I32)))) {
            for (String name : List.of("run", "alternate")) {
                assertEquals(-1, fixture.call(name, "Fn<I32,I32;I32>", -1, 0));
                assertEquals(1, fixture.call(name, "Fn<I32,I32;I32>", 3, 2));
                LanguageTestSupport.runtimeFailure(assertThrows(Throwable.class,
                        () -> fixture.call(name, "Fn<I32,I32;I32>", 1, 0)), "LYR-ARITH");
            }
        }
    }

    @Test void mixedWidthMatchFuzzProbesCoverLogicalNumericConversions() throws Throwable {
        String[][] comparisons = {
                {"255U8", "255I16"},
                {"65535U16", "65535I32"},
                {"4294967295U32", "4294967295I64"},
                {"4294967295U32", "4294967295.0F64"},
                {"16777217I32", "16777217.0F64"},
                {"1.5F32", "1.5F64"}
        };
        for (int index = 0; index < comparisons.length; index++) {
            var program = new TypedProgramGenerator.MatchProgram(false, 0, 0, 0, 0, index);
            String source = program.source();
            String arm = comparisons[index][0] + " ?? " + comparisons[index][1]
                    + " -> 0 ?? _ -> 1000000";
            assertTrue(source.contains("(match " + arm + ")"), "Missing prefix probe " + index);
            assertTrue(source.contains("::match[" + arm + "]"), "Missing bracket probe " + index);
            var expected = program.evaluate(1, 1);
            assertNull(expected.failure());
            try (var fixture = new LanguageTestSupport.Fixture(LanguageTestSupport.compile(source))) {
                assertEquals(expected.value(), fixture.call("run", "Fn<I32,I32;I32>", 1, 1));
                assertEquals(expected.value(), fixture.call("alternate", "Fn<I32,I32;I32>", 1, 1));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new TypedProgramGenerator.MatchProgram(false, 0, 0, 0, 0, 6));
    }

    @Test void matchProgramSpellingsAndLazyEffectModelHaveKnownBoundaries() {
        var conditional = new TypedProgramGenerator.MatchProgram(true, 99, 1, 2, -2);
        String conditionalSource = conditional.source();
        assertTrue(conditionalSource.contains("(match _ ?? (conditionOne) -> (+ a 2) "
                + "?? (conditionTwo) -> (- b 2) ?? _ -> (% 1 b))"));
        assertTrue(conditionalSource.contains("::match[_ ?? (conditionOne) -> (+ a 2) "
                + "?? (conditionTwo) -> (- b 2) ?? _ -> (% 1 b)]"));
        assertFalse(conditionalSource.contains("::match[(conditionOne)"));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(13, null), conditional.evaluate(1, 7));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(30, null), conditional.evaluate(0, 2));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(null, "LYR-ARITH"), conditional.evaluate(0, 0));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(null, "LYR-ARITH"),
                conditional.evaluate(Integer.MAX_VALUE, 1));

        var traditional = new TypedProgramGenerator.MatchProgram(false, 1, 3, 2, 4);
        String traditionalSource = traditional.source();
        assertTrue(traditionalSource.contains("(match (subject) ?? (patternOne) when (guard) -> (+ a 2) "
                + "?? (patternTwo) -> (+ b 4) ?? _ -> (% 1 b))"));
        assertTrue(traditionalSource.contains("::match[(subject) ?? (patternOne) when (guard) -> (+ a 2) "
                + "?? (patternTwo) -> (+ b 4) ?? _ -> (% 1 b)]"));
        assertTrue(traditionalSource.contains("let subject :Fn<;I32>"));
        assertFalse(traditionalSource.contains("@nil subject"));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(37, null), traditional.evaluate(5, 2));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(31, null), traditional.evaluate(5, 1));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(null, "LYR-ARITH"),
                traditional.evaluate(Integer.MAX_VALUE, 1));

        var guarded = new TypedProgramGenerator.MatchProgram(false, 0, 5, 2, 3);
        assertEquals(new TypedProgramGenerator.MatchEvaluation(118, null), guarded.evaluate(5, 2));
        assertEquals(new TypedProgramGenerator.MatchEvaluation(134, null), guarded.evaluate(5, 0));
    }

    @Test void anIncorrectOracleFailsTheWorkerAndPreservesTheReplay() throws Exception {
        FuzzCase test = new FuzzCase("state", "let @pub run :Fn<;I32> = (=> | | 1)");
        test.put("expected", 2); test.save(temp);
        var result = FuzzProcess.run(List.of("--replay", temp.resolve("current.properties").toString()), temp,
                Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertFalse(result.passed()); assertFalse(result.timedOut());
        assertTrue(result.fingerprint().startsWith("FUZZ_FAILURE=java.lang.AssertionError"));
        assertTrue(result.output().contains("expected: 2"));
        assertEquals("2", FuzzCase.read(temp.resolve("current.properties")).get("expected"));
    }

    @Test void reductionRetainsOnlyTheOriginalFailureAndNeverOverwritesItsInput() throws Exception {
        FuzzCase input = new FuzzCase("mutation", "irrelevant 😀 KEEP irrelevant");
        input.save(temp);
        Path original = temp.resolve("current.properties");
        var expected = new FuzzProcess.Result(1, false, "FUZZ_FAILURE=original-defect\n");
        Path reduced = FuzzProcess.minimize(original, expected, 100, candidate -> {
            String source = FuzzCase.read(candidate).get("source");
            // Removing KEEP produces a DIFFERENT failure; it must not be accepted as a reduction.
            return source.contains("KEEP") ? expected : new FuzzProcess.Result(1, false, "FUZZ_FAILURE=different-defect\n");
        });
        assertEquals("KEEP", FuzzCase.read(reduced).get("source"));
        assertEquals(input.data, FuzzCase.read(original).data);

        FuzzCase bytes = new FuzzCase("bytes", "// binary input");
        bytes.put("bytes", java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 9, 3, 4}));
        Path byteDirectory = temp.resolve("bytes"); bytes.save(byteDirectory);
        Path minimalBytes = FuzzProcess.minimize(byteDirectory.resolve("current.properties"), expected, 100, candidate -> {
            byte[] payload = java.util.Base64.getDecoder().decode(FuzzCase.read(candidate).get("bytes"));
            for (byte value : payload) if (value == 9) return expected;
            return new FuzzProcess.Result(0, false, "REPLAY PASS");
        });
        assertArrayEquals(new byte[]{9}, java.util.Base64.getDecoder().decode(FuzzCase.read(minimalBytes).get("bytes")));
    }
}
