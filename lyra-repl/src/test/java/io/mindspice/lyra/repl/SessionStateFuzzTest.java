package io.mindspice.lyra.repl;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Seeded state-machine checks against a Java model of publication, storage, aliases, and reset. */
public class SessionStateFuzzTest {
    @TestFactory Stream<DynamicTest> persistentSessionModel() {
        String seeds = System.getProperty("lyra.sessionFuzz.seeds", "7,83,137");
        int steps = Integer.parseInt(System.getProperty("lyra.sessionFuzz.steps", "48"));
        assertTrue(steps >= 12 && steps <= 10000, "lyra.sessionFuzz.steps must be 12..10000");
        List<Long> parsed = Stream.of(seeds.split(",", -1)).map(String::trim).map(Long::decode).toList();
        assertFalse(parsed.isEmpty(), "At least one session seed is required");
        assertEquals(parsed.size(), parsed.stream().distinct().count(), "Duplicate seeds waste the session test budget");
        return parsed.stream().map(seed ->
                DynamicTest.dynamicTest("persistent-session/seed=" + seed + "/steps=" + steps, () -> {
                    Path root = Path.of("target/session-fuzz").toAbsolutePath(); Files.createDirectories(root);
                    Path directory = Files.createTempDirectory(root, "seed-" + seed + "-");
                    Path log = directory.resolve("worker.log");
                    List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "--enable-preview", "-Xverify:all", "-Xmx384m", "-XX:MaxMetaspaceSize=192m", "-cp",
                            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            SessionStateFuzzTest.class.getName(), seed.toString(), Integer.toString(steps)));
                    Process worker = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    try {
                        assertTrue(worker.waitFor(Duration.ofSeconds(Math.max(45, steps * 2L))), "Session worker timed out: " + log);
                        assertEquals(0, worker.exitValue(), () -> "Session replay: mvn -pl lyra-repl -am test"
                                + " -Dtest=SessionStateFuzzTest,LanguageCoverageTest -Dsurefire.failIfNoSpecifiedTests=false -Dlyra.sessionFuzz.seeds="
                                + seed + " -Dlyra.sessionFuzz.steps=" + steps + "\nTranscript: " + log + "\n" + read(log));
                        assertTrue(read(log).contains("SESSION FUZZ PASS"), "Missing completion marker: " + log);
                    } finally {
                        if (worker.isAlive()) {
                            worker.descendants().forEach(ProcessHandle::destroyForcibly); worker.destroyForcibly();
                            assertTrue(worker.waitFor(Duration.ofSeconds(5)), "Session worker did not terminate");
                        }
                    }
                }));
    }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]); int steps = Integer.parseInt(args[1]);
        SplittableRandom random = new SplittableRandom(seed);
        try (var session = LyraSession.open()) {
            initialize(session);
            int count = 0;
            int[] current = {1, 2, 3}, alias = current;
            // Independent retained-nominal state model.
            List<Integer> boxes = new ArrayList<>();
            List<Integer> replacedReads = new ArrayList<>();
            List<Integer> savedReads = new ArrayList<>();
            int nominalEffects = 0;
            int savedCounter = 0;
            int orderedCounter = 0;
            int freshCounter = 0;
            for (int step = 0; step < steps; step++) {
                int delta = random.nextInt(1, 20), index = random.nextInt(3);
                System.out.println("STEP " + step + " seed=" + seed);
                // Cycle through every operation; random values and alias histories still differ by seed.
                switch (step % 20) {
                    case 0 -> { success(session, "count := (+ count " + delta + ")"); count += delta; }
                    case 1 -> { count++; assertEquals(Integer.toString(count), scalar(session, "(inc)")); }
                    case 2 -> { success(session, "items[" + index + "] := " + delta); current[index] = delta; }
                    case 3 -> { success(session, "items := Array<I32>[" + delta + " 2 3]"); current = new int[]{delta, 2, 3}; }
                    case 4 -> { success(session, "alias[" + index + "] := " + delta); alias[index] = delta; }
                    case 5 -> {
                        var before = session.workspaceState();
                        assertInstanceOf(EvaluationResult.CompilationFailure.class,
                                submit(session, "count := 999 let broken :Bool = 1"));
                        assertEquals(before, session.workspaceState());
                    }
                    case 6 -> {
                        var before = session.workspaceState();
                        assertInstanceOf(EvaluationResult.RuntimeFailure.class, submit(session,
                                "count := (+ count " + delta + ") let hidden = 9 let zero :I32 = 0 (% 1 zero)"));
                        count += delta;
                        assertEquals(before, session.workspaceState());
                        assertFalse(session.workspaceState().bindings().containsKey("hidden"));
                    }
                    case 7 -> {
                        boolean nil = random.nextBoolean(); int value = random.nextBoolean() ? 0 : delta;
                        success(session, "maybe := " + (nil ? "#NIL" : value));
                        assertEquals(Integer.toString(nil ? 99 : value), scalar(session, "(maybe : 99)"));
                    }
                    case 8 -> {
                        var before = session.workspaceState();
                        System.out.println("TYPE (inc)"); System.out.flush();
                        var type = session.type(EvaluationSource.of("fuzz-type.lyra", "(inc)"));
                        assertEquals("I32", type.canonicalType().orElseThrow()); assertTrue(type.diagnostics().isEmpty());
                        assertEquals(before, session.workspaceState());
                    }
                    case 9 -> {
                        success(session, "let label :String = \"replacement" + delta + "\"");
                        assertEquals("original", scalar(session, "(readLabel)"));
                    }
                    case 10 -> {
                        success(session, "let functions :Array<Fn<;I32>> = Array<Fn<;I32>>[inc]");
                        count++; assertEquals(Integer.toString(count), scalar(session, "(functions[0])"));
                    }
                    case 11 -> {
                        // Observations also retain source records: one per submission. Resetting every
                        // 20-step cycle keeps the bounded default 256-record registry well under budget.
                        System.out.println("RESET"); System.out.flush(); session.reset();
                        assertTrue(session.workspaceState().bindings().isEmpty());
                        initialize(session); count = 0; current = new int[]{1, 2, 3}; alias = current;
                        boxes.clear(); replacedReads.clear(); savedReads.clear();
                        nominalEffects = 0; savedCounter = 0; orderedCounter = 0; freshCounter = 0;
                    }
                    case 12 -> {
                        // Retained construction: a fresh generation observes an exact new instance value.
                        int value = delta;
                        success(session, "let @mut box" + boxes.size() + " :SessionBox = :SessionBox[" + value + "]");
                        boxes.add(value); replacedReads.add(-1);
                        int last = boxes.size() - 1;
                        if (boxes.size() > 1) {
                            assertEquals("false", scalar(session,
                                    "(eq? box" + (last - 1) + " box" + last + ")"));
                        }
                        assertEquals(Integer.toString(value), scalar(session, "box" + last + ":.value"));
                    }
                    case 13 -> {
                        // Shared alias: mutation through the alias is visible through the original name.
                        int last = boxes.size() - 1;
                        success(session, "let @mut aliasBox" + last + " :SessionBox = box" + last);
                        assertEquals("true", scalar(session, "(eq? aliasBox" + last + " box" + last + ")"));
                        success(session, "aliasBox" + last + ":.value := " + delta);
                        boxes.set(last, delta);
                    }
                    case 14 -> {
                        // Member replacement while saving the pre-replacement callable.
                        int last = boxes.size() - 1;
                        success(session, "let savedRead" + savedCounter + " :Fn<;I32> = box" + last + ":.read");
                        savedReads.add(last);
                        success(session, "box" + last + ":.read := (=> || " + delta + ")");
                        replacedReads.set(last, delta);
                        savedCounter++;
                    }
                    case 15 -> {
                        // Saved callable keeps the original self reader; the current slot returns the replacement.
                        int last = savedReads.get(savedReads.size() - 1);
                        int savedValue = boxes.get(last);
                        int currentValue = replacedReads.get(last);
                        assertEquals(Integer.toString(savedValue + 10 * currentValue),
                                scalar(session, "(+ (savedRead" + (savedCounter - 1) + ") (* box"
                                        + last + "::read[] 10I32))"));
                    }
                    case 16 -> {
                        // Ordered constructor/default effects and a callable-bearing composite.
                        nominalEffects += 11;
                        success(session, "let ordered" + orderedCounter
                                + " :SessionOrdered = :SessionOrdered[" + delta + "]");
                        assertEquals(Integer.toString(nominalEffects), scalar(session, "constructorEffects"));
                        int last = boxes.size() - 1;
                        int readValue = replacedReads.get(last) >= 0 ? replacedReads.get(last) : boxes.get(last);
                        success(session, "let callables" + orderedCounter
                                + " :Array<Fn<;I32>> = Array<Fn<;I32>>[box" + last
                                + ":.read (=> || " + delta + ")]");
                        assertEquals(Integer.toString(readValue), scalar(session, "(callables" + orderedCounter + "[0])"));
                        assertEquals(Integer.toString(delta), scalar(session, "(callables" + orderedCounter + "[1])"));
                        orderedCounter++;
                    }
                    case 17 -> {
                        // Failure publication: completed default effects are retained, no name is published.
                        nominalEffects += 1;
                        var before = session.workspaceState();
                        assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                                submit(session, "let stagedFail" + freshCounter
                                        + " :SessionFragile = :SessionFragile[]"));
                        assertEquals(before, session.workspaceState());
                        assertFalse(session.workspaceState().bindings().containsKey("stagedFail" + freshCounter));
                        assertEquals(Integer.toString(nominalEffects), scalar(session, "constructorEffects"));
                    }
                    case 18 -> {
                        // Recovery: repairing the divisor re-runs defaults and publishes the instance.
                        success(session, "divisor := 1");
                        nominalEffects += 1;
                        success(session, "let stagedRecover" + freshCounter
                                + " :SessionFragile = :SessionFragile[]");
                        assertEquals("1", scalar(session,
                                "(+ stagedRecover" + freshCounter + ":.touched stagedRecover"
                                        + freshCounter + ":.broken)"));
                        assertEquals(Integer.toString(nominalEffects), scalar(session, "constructorEffects"));
                    }
                    case 19 -> {
                        // Shared versus fresh identities across retained constructions.
                        success(session, "let freshA" + freshCounter + " :SessionFresh = :SessionFresh[]"
                                + " let freshB" + freshCounter + " :SessionFresh = :SessionFresh[]");
                        assertEquals("false", scalar(session, "(eq? freshA" + freshCounter
                                + " freshB" + freshCounter + ")"));
                        assertEquals("true", scalar(session, "(eq? freshA" + freshCounter
                                + " freshA" + freshCounter + ")"));
                        assertEquals("false", scalar(session, "(eq? freshA" + freshCounter
                                + ":.values freshB" + freshCounter + ":.values)"));
                        assertEquals("true", scalar(session, "(eq? freshA" + freshCounter
                                + ":.values freshA" + freshCounter + ":.values)"));
                        freshCounter++;
                    }
                    default -> throw new AssertionError();
                }
                assertEquals(Integer.toString(count), scalar(session, "count"));
                for (int i = 0; i < 3; i++) {
                    assertEquals(Integer.toString(current[i]), scalar(session, "items[" + i + "]"));
                    assertEquals(Integer.toString(alias[i]), scalar(session, "alias[" + i + "]"));
                }
                assertEquals(Boolean.toString(current == alias), scalar(session, "(eq? items alias)"));
            }
        }
        try (var limited = LyraSession.open(SessionOptions.builder().maxSourceRecords(1).build())) {
            success(limited, "let @mut count :I32 = 1");
            var before = limited.workspaceState();
            var rejected = assertInstanceOf(EvaluationResult.CompilationFailure.class, submit(limited, "count := 999"));
            assertEquals("LYC-EMIT-001", rejected.diagnostics().getFirst().code().value());
            assertTrue(rejected.diagnostics().getFirst().summary().contains("source registry"));
            assertEquals(before, limited.workspaceState());
            limited.reset();
            assertTrue(limited.workspaceState().bindings().isEmpty());
            assertEquals("2", scalar(limited, "2I32"));
        }
        System.out.println("SESSION FUZZ PASS seed=" + seed + " steps=" + steps);
    }

    private static void initialize(LyraSession session) {
        success(session, "let @mut count :I32 = 0 let inc :Fn<;I32> = (=> || { count := (++ count) count })");
        success(session, "let @mut items :Array<I32> = Array<I32>[1 2 3] let @mut alias :Array<I32> = items");
        success(session, "let @mut @nil maybe :I32 = #NIL let label :String = \"original\" let readLabel :Fn<;String> = (=> || label)");
        success(session, "let @mut constructorEffects :I32 = 0 let @mut divisor :I32 = 0"
                + " let touch :Fn<I32;I32> = (=> |v| { constructorEffects := (++ constructorEffects) v })"
                + " class SessionBox { let @pub @mut value :I32 = 0"
                + " let @pub @mut read :Fn<;I32> = (=> || self:.value)"
                + " SessionBox = (=> |start :I32| { self:.value := start }) }"
                + " class SessionOrdered { let @pub @mut value :I32 = 0"
                + " let @pub first :I32 = ::touch[1I32]"
                + " SessionOrdered = (=> |start :I32| { constructorEffects := (+ constructorEffects 10I32) self:.value := start }) }"
                + " class SessionFragile { let @pub touched :I32 = ::touch[1I32]"
                + " let @pub broken :I32 = (% 8 divisor) }"
                + " class SessionFresh { let @pub values :Array<I32> = Array<I32>[1I32 2I32] }");
    }

    private static EvaluationResult submit(LyraSession session, String source) {
        System.out.println("SUBMIT " + source); System.out.flush();
        return session.submit("session-fuzz.lyra", source);
    }

    private static EvaluationResult.Success success(LyraSession session, String source) {
        EvaluationResult result = submit(session, source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(LyraSession session, String source) {
        var value = success(session, source).value().orElseThrow();
        return assertInstanceOf(ValueSnapshot.Scalar.class, value.data()).value();
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
