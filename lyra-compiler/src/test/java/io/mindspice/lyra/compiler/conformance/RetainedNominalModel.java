package io.mindspice.lyra.compiler.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Independent retained-nominal transfer/certificate model.
 *
 * <p>Every generated case is a three-generation session program: a producer
 * generation that declares nominals with retained member initializers, a
 * construction generation that instantiates those factories against the staged
 * snapshot, and observation generations that read the constructed members back
 * through the authenticated session storage domain.  Expected values, transfer
 * shapes, effect sums and failure verdicts are computed here with plain Java
 * arithmetic over the constants this generator chose.  No production compiler
 * or runtime helper ever supplies an expected value, variant kind or code.</p>
 *
 * <p>The generation is deterministic for a fixed seed and {@code retainedOrdinal}
 * (the number of completed mode rotations).  Ten profiles rotate, so every
 * valid campaign budget (which always runs at least ten retained cases per
 * seed) executes every profile, every operation name, and the four pinned
 * issue-#7 Unit/intrinsic observation shapes (exact Unit values and one
 * actual namespace-member callable invocation) exactly once or more.</p>
 */
final class RetainedNominalModel {
    private RetainedNominalModel() { }

    /** Profile names, rotating deterministically per retained case ordinal. */
    static final List<String> PROFILES = List.of(
            "values", "calls", "composites", "sequences", "alternatives",
            "ranges-construct", "fresh", "pinned-unit", "failures", "slots");

    /** The complete retained operation catalog; the driver requires nonzero counts for every name. */
    static final List<String> ALL_OPS = List.of(
            "guard-silent", "inventory-exact",
            "literal", "reference", "member-self", "operator", "conversion",
            "lambda", "direct-call", "callable-call", "aggregate-call",
            "array", "tuple-callables", "index", "string-index", "length", "nilable-index",
            "declare-rebind", "block-shadow", "overwritten-rebind",
            "conditional", "unit-conditional", "coalesce", "match", "match-guard",
            "nil-member-annotate", "nil-member-coalesce", "nil-member-narrow", "nil-member-match",
            "range", "construct", "construct-args",
            "alias-preserved", "shared-result", "fresh-result", "same-slot",
            "distinct-instances",
            "loop-iter", "loop-while", "namespace-direct", "namespace-member",
            "construction-effects",
            "runtime-failure", "failure-effects", "recovery",
            "saved-slot", "current-slot", "factory-shadow",
            "forge-route", "forge-inventory", "link-mismatch");

    /** Fixed model constants shared by every producer preamble. */
    record Constants(int sharedA, int sharedB, int makeA, int makeB, int inner, int baseline) { }

    /** One issued-transfer expectation for a named member of a named nominal. */
    record MemberExpectation(String nominal, String member, String variant, int structure) { }

    /** One generation step: a source submission, its modeled outcome, and the operations it executes. */
    record Step(String source, String outcome, String type, String expected, List<String> ops) {
        Step { // outcome: declared (execute/commit only), value (read+compare), failure (expect code)
            if (!List.of("declared", "value", "failure").contains(outcome)) {
                throw new IllegalArgumentException("unknown step outcome: " + outcome);
            }
            ops = ops == null ? List.of() : List.copyOf(ops);
        }

        Step(String source, String outcome, String type, String expected) {
            this(source, outcome, type, expected, List.of());
        }
    }

    /** One complete deterministic retained case. */
    record Plan(String profile, String producer, List<MemberExpectation> members,
                List<Step> steps, String output, int retainedOrdinal) {
        Plan {
            members = List.copyOf(members);
            steps = List.copyOf(steps);
        }

        /** Operations actually executed for this profile; the runner must match this exactly. */
        List<String> ops() {
            return planOps(profile);
        }
    }

    static String profileAt(int retainedOrdinal) {
        return PROFILES.get(Math.floorMod(retainedOrdinal, PROFILES.size()));
    }

    static List<String> opsAt(int retainedOrdinal) {
        return planOps(profileAt(retainedOrdinal));
    }

    private static List<String> planOps(String profile) {
        List<String> ops = new ArrayList<>(List.of("guard-silent", "inventory-exact"));
        ops.addAll(switch (profile) {
            case "values" -> List.of("literal", "reference", "member-self", "operator",
                    "conversion", "forge-route", "forge-inventory", "link-mismatch");
            case "calls" -> List.of("lambda", "direct-call", "callable-call", "aggregate-call");
            case "composites" -> List.of("array", "tuple-callables", "index", "string-index", "length",
                    "nilable-index");
            case "sequences" -> List.of("declare-rebind", "block-shadow", "overwritten-rebind");
            case "alternatives" -> List.of("conditional", "unit-conditional", "coalesce", "match", "match-guard",
                    "nil-member-annotate", "nil-member-coalesce", "nil-member-narrow", "nil-member-match");
            case "ranges-construct" -> List.of("range", "conversion", "construct", "construct-args", "aggregate-call");
            case "fresh" -> List.of("alias-preserved", "shared-result", "fresh-result", "same-slot",
                    "distinct-instances");
            case "pinned-unit" -> List.of("loop-iter", "loop-while", "namespace-direct", "namespace-member",
                    "construction-effects");
            case "failures" -> List.of("runtime-failure", "failure-effects", "recovery");
            case "slots" -> List.of("saved-slot", "current-slot", "factory-shadow");
            default -> throw new AssertionError(profile);
        });
        return List.copyOf(ops);
    }

    static Plan generate(SplittableRandom random, int retainedOrdinal) {
        return switch (profileAt(retainedOrdinal)) {
            case "values" -> values(random, retainedOrdinal);
            case "calls" -> calls(random, retainedOrdinal);
            case "composites" -> composites(random, retainedOrdinal);
            case "sequences" -> sequences(random, retainedOrdinal);
            case "alternatives" -> alternatives(random, retainedOrdinal);
            case "ranges-construct" -> rangesConstruct(random, retainedOrdinal);
            case "fresh" -> fresh(random, retainedOrdinal);
            case "pinned-unit" -> pinnedUnit(random, retainedOrdinal);
            case "failures" -> failures(random, retainedOrdinal);
            case "slots" -> slots(random, retainedOrdinal);
            default -> throw new AssertionError(profileAt(retainedOrdinal));
        };
    }

    private static int bound(SplittableRandom random, int min, int max) {
        return random.nextInt(min, max);
    }

    /** Shared helpers every non-pinned producer declares; constants stay in the model. */
    private static String preamble(SplittableRandom random, Constants constants) {
        int maybeValue = bound(random, 1, 10);
        return "let @mut count :I32 = 0\n"
                + "let baseline :I32 = " + constants.baseline() + "I32\n"
                + "let @pub @mut shared :Array<I32> = Array<I32>[" + constants.sharedA() + "I32 "
                + constants.sharedB() + "I32]\n"
                + "let @pub inc :Fn<I32;I32> = (=> |v| (+ v 1I32))\n"
                + "let @pub zero :Fn<;I32> = (=> || 0I32)\n"
                + "let @pub make :Fn<;Array<I32>> = (=> || Array<I32>[" + constants.makeA() + "I32 "
                + constants.makeB() + "I32])\n"
                + "let @nil maybe :I32 = " + maybeValue + "I32\n"
                + "class Inner { @pub value :I32 = " + constants.inner() + "I32 }\n"
                + "struct Pair { left :I32 }\n";
    }

    private static String holders() {
        return "class Holder { @pub value :Tuple<Inner> = ::makeTuple[] }\n"
                + "let @pub makeTuple :Fn<;Tuple<Inner>> = (=> || Tuple[:Inner[]])\n";
    }

    private static Plan values(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int literal = bound(random, 1, 10), o0 = bound(random, 1, 10), o1 = bound(random, 1, 10);
        int converted = bound(random, 1, 30000);
        String producer = preamble(random, constants) + holders() + """
                class Box {
                    @pub literal :I32 = %dI32
                    @pub reference :I32 = baseline
                    @pub member :I32 = self:.literal
                    @pub op :I32 = (+ %dI32 %dI32)
                    @pub converted :I32 = I32[%dI16]
                }
                """.formatted(literal, o0, o1, converted);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "literal", "Value", 0),
                new MemberExpectation("Box", "reference", "Reference", 0),
                new MemberExpectation("Box", "member", "Reference", 0),
                new MemberExpectation("Box", "op", "Apply", 2),
                new MemberExpectation("Box", "converted", "Apply", 1));
        int sum = literal + constants.baseline() + literal + (o0 + o1) + converted;
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[]", "declared", "", ""),
                new Step("(+ box:.literal box:.reference box:.member box:.op box:.converted)",
                        "value", "I32", Integer.toString(sum)));
        return new Plan("values", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan calls(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int lambda = bound(random, 1, 10), direct = bound(random, 1, 10);
        String producer = preamble(random, constants) + """
                class Box {
                    @pub lambda :Fn<;I32> = (=> || %dI32)
                    @pub direct :I32 = ::inc[%dI32]
                    @pub callable :I32 = (zero)
                    @pub made :Array<I32> = ::make[]
                }
                """.formatted(lambda, direct);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "lambda", "Lambda", 0),
                new MemberExpectation("Box", "direct", "Call", 1),
                new MemberExpectation("Box", "callable", "CallableCall", 0),
                new MemberExpectation("Box", "made", "Call", 0));
        int sum = (direct + 1) + 0 + lambda * 10 + constants.makeA() * 100;
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[]", "declared", "", ""),
                new Step("(+ box:.direct box:.callable (* box::lambda[] 10I32) (* box:.made[0I32] 100I32))",
                        "value", "I32", Integer.toString(sum)));
        return new Plan("calls", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan composites(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int a0 = bound(random, 1, 10), a1 = bound(random, 1, 10);
        int t0 = bound(random, 1, 10), t1 = bound(random, 1, 10);
        int x0 = bound(random, 1, 10), x1 = bound(random, 1, 10);
        int l0 = bound(random, 1, 10), l1 = bound(random, 1, 10), l2 = bound(random, 1, 10);
        int n0 = bound(random, 1, 10);
        String producer = preamble(random, constants) + """
                class Box {
                    @pub array :Array<I32> = Array<I32>[%dI32 %dI32]
                    @pub tuple :Tuple<I32,Fn<;I32>> = Tuple[%dI32 (=> || %dI32)]
                    @pub index :I32 = Array<I32>[%dI32 %dI32][1I32]
                    @pub char :Char = "ab"[1I32]
                    @pub sized :I32 = Array<I32>[%dI32 %dI32 %dI32]:.length
                    @pub nilIndex :@nil I32 = Array<@nil I32>[#NIL %dI32][1I32]
                }
                """.formatted(a0, a1, t0, t1, x0, x1, l0, l1, l2, n0);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "array", "Composite", 2),
                new MemberExpectation("Box", "tuple", "Composite", 2),
                new MemberExpectation("Box", "index", "Project", 1),
                new MemberExpectation("Box", "char", "Project", 1),
                new MemberExpectation("Box", "sized", "Project", 0),
                new MemberExpectation("Box", "nilIndex", "Project", 1));
        int sum = a1 + x1 + 3 + t0 * 10 + t1 * 100 + n0 * 1000;
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[]", "declared", "", ""),
                new Step("(+ box:.array[1I32] box:.index box:.sized (* box:.tuple:.0 10I32) (* (box:.tuple:.1) 100I32) (* ((!= box:.nilIndex #NIL) -> " + n0 + "I32 : 0I32) 1000I32))",
                        "value", "I32", Integer.toString(sum)),
                new Step("((== box:.char 'b') -> 1I32 : 0I32)", "value", "I32", "1"));
        return new Plan("composites", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan sequences(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int d0 = bound(random, 1, 10), d1 = bound(random, 1, 10);
        int s0 = bound(random, 1, 10), s1 = bound(random, 1, 10);
        int o0 = bound(random, 1, 10), o1 = bound(random, 1, 10), o2 = bound(random, 1, 10);
        String producer = preamble(random, constants) + """
                class Box {
                    @pub declare :I32 = { let @mut local :I32 = %dI32 local := %dI32 local }
                    @pub shadow :I32 = { let selected :I32 = %dI32 { let selected :I32 = %dI32 selected } }
                    @pub overwritten :I32 = { let @mut cell :I32 = %dI32 cell := %dI32 cell := %dI32 cell }
                }
                """.formatted(d0, d1, s0, s1, o0, o1, o2);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "declare", "Sequence", 3),
                new MemberExpectation("Box", "shadow", "Sequence", 2),
                new MemberExpectation("Box", "overwritten", "Sequence", 4));
        int sum = d1 + s1 * 10 + o2 * 100;
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[]", "declared", "", ""),
                new Step("(+ box:.declare (* box:.shadow 10I32) (* box:.overwritten 100I32))",
                        "value", "I32", Integer.toString(sum)));
        return new Plan("sequences", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan alternatives(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        boolean nilable = random.nextBoolean();
        int maybeValue = bound(random, 1, 10);
        int k0 = bound(random, 1, 10), k1 = bound(random, 1, 10);
        int q = bound(random, 1, 10);
        int m0 = bound(random, 1, 10), m1 = bound(random, 1, 10), m2 = bound(random, 1, 10);
        String producer = "let @mut count :I32 = 0\n"
                + "let @pub @mut shared :Array<I32> = Array<I32>[" + constants.sharedA() + "I32 "
                + constants.sharedB() + "I32]\n"
                + "let @pub inc :Fn<I32;I32> = (=> |v| (+ v 1I32))\n"
                + "let @pub zero :Fn<;I32> = (=> || 0I32)\n"
                + "let @pub make :Fn<;Array<I32>> = (=> || Array<I32>[" + constants.makeA() + "I32 "
                + constants.makeB() + "I32])\n"
                + "let @nil maybe :I32 = " + (nilable ? "#NIL" : maybeValue + "I32") + "\n"
                + "class Inner { @pub value :I32 = " + constants.inner() + "I32 }\n"
                + "struct Pair { left :I32 }\n" + """
                class Box {
                    @pub conditional :I32 = (#T -> %dI32 : %dI32)
                    @pub unitCond :Unit = (#T -> ())
                    @pub coalesced :I32 = (maybe : %dI32)
                    @pub matched :I32 = (match %dI32 %dI32 -> %dI32 _ -> %dI32)
                    @pub guarded :I32 = (match %dI32 %dI32 when #T -> %dI32 _ -> %dI32)
                    @pub present :@nil I32 = maybe
                    @pub absent :@nil I32 = #NIL
                    @pub narrowable :@nil I32 = maybe
                    @pub matchable :@nil I32 = #NIL
                }
                """.formatted(k0, k1, q, m0, m0, m1, m2, m0, m0, m1, m2);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "conditional", "Alternative", 2),
                new MemberExpectation("Box", "unitCond", "Alternative", 2),
                new MemberExpectation("Box", "coalesced", "Alternative", 1),
                new MemberExpectation("Box", "matched", "Alternative", 2),
                new MemberExpectation("Box", "guarded", "Alternative", 2),
                new MemberExpectation("Box", "present", "Reference", 0),
                new MemberExpectation("Box", "absent", "Value", 0),
                new MemberExpectation("Box", "narrowable", "Reference", 0),
                new MemberExpectation("Box", "matchable", "Value", 0));
        int coalesced = nilable ? q : maybeValue;
        int annotated = nilable ? 0 : 1;
        int narrowed = nilable ? 0 : maybeValue;
        int sum = k0 + coalesced * 10 + m1 * 100 + m1 * 1000;
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[]", "declared", "", ""),
                new Step("(+ box:.conditional (* box:.coalesced 10I32) (* box:.matched 100I32) (* box:.guarded 1000I32))",
                        "value", "I32", Integer.toString(sum)),
                new Step("box:.unitCond", "declared", "", ""),
                new Step("{ let v :@nil I32 = box:.present ((!= v #NIL) -> 1I32 : 0I32) }",
                        "value", "I32", Integer.toString(annotated)),
                new Step("(box:.absent : " + q + "I32)", "value", "I32", Integer.toString(q)),
                new Step("(box:.narrowable narrowed -> narrowed : 0I32)",
                        "value", "I32", Integer.toString(narrowed)),
                new Step("(match box:.matchable #NIL -> 1I32 _ -> 0I32)",
                        "value", "I32", "1"));
        return new Plan("alternatives", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan rangesConstruct(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int r0 = bound(random, 0, 5), r1 = bound(random, 6, 12);
        int converted = bound(random, 1, 30000);
        int pair = bound(random, 1, 10);
        String producer = preamble(random, constants) + """
                class Box {
                    @pub range :Range<I32> = (%dI32..%dI32:1I32)
                    @pub converted :I32 = I32[%dI16]
                    @pub nested :Inner = :Inner[]
                    @pub paired :Pair = :Pair[%dI32]
                    @pub made :Array<I32> = ::make[]
                }
                """.formatted(r0, r1, converted, pair);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "range", "Apply", 3),
                new MemberExpectation("Box", "converted", "Apply", 1),
                new MemberExpectation("Box", "nested", "Construct", 0),
                new MemberExpectation("Box", "paired", "Construct", 1),
                new MemberExpectation("Box", "made", "Call", 0));
        int sum = converted + constants.inner() * 10 + pair * 100 + constants.makeA() * 1000;
        String rangeLiteral = "(" + r0 + "I32.." + r1 + "I32:1I32)";
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[]", "declared", "", ""),
                new Step("(+ box:.converted (* box:.nested:.value 10I32) (* box:.paired:.left 100I32) (* box:.made[0I32] 1000I32))",
                        "value", "I32", Integer.toString(sum)),
                new Step("((== box:.range " + rangeLiteral + ") -> 1I32 : 0I32)", "value", "I32", "1"));
        return new Plan("ranges-construct", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan fresh(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int f0 = bound(random, 1, 10), f1 = bound(random, 1, 10);
        String producer = preamble(random, constants) + """
                class Box {
                    @pub values :Array<I32> = shared
                    @pub alias :Array<I32> = self:.values
                }
                class Fresh { @pub values :Array<I32> = Array<I32>[%dI32 %dI32] }
                """.formatted(f0, f1);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Box", "values", "Apply", 1),
                new MemberExpectation("Box", "alias", "Reference", 0),
                new MemberExpectation("Fresh", "values", "Composite", 2));
        List<Step> steps = List.of(
                new Step("let box :Box = :Box[] let second :Box = :Box[] let first :Fresh = :Fresh[] let another :Fresh = :Fresh[]",
                        "declared", "", ""),
                new Step("""
                        (+ ((eq? box:.alias box:.values) -> 1I32 : 0I32)
                           ((eq? box second) -> 100I32 : 0I32)
                           ((eq? box:.values shared) -> 10I32 : 0I32)
                           ((eq? box:.values box:.values) -> 1000I32 : 0I32)
                           ((eq? first:.values another:.values) -> 10000I32 : 0I32)
                           ((eq? first:.values first:.values) -> 100000I32 : 0I32))
                        """, "value", "I32", "101011",
                        List.of("same-slot", "distinct-instances")));
        return new Plan("fresh", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan pinnedUnit(SplittableRandom random, int retainedOrdinal) {
        String producer = "import std->io\n" + """
                class LoopC {
                    @pub iterated :Unit = iter[(0I32..2I32:1I32) || ()]
                    @pub looped :Unit = while[|| #F || ()]
                }
                class Intrinsic {
                    @pub printed :Unit = io->::println["probe"]
                    @pub printer :Fn<String;Unit> = io->:.println
                }
                """;
        List<MemberExpectation> members = List.of(
                new MemberExpectation("LoopC", "iterated", "Loop", 0),
                new MemberExpectation("LoopC", "looped", "Loop", 0),
                new MemberExpectation("Intrinsic", "printed", "Call", 1),
                new MemberExpectation("Intrinsic", "printer", "Reference", 0));
        // Issue #7 flipped inventory: construction executes each Unit initializer
        // exactly once, and later observations read the exact Unit values; the
        // namespace-member case actually invokes the retained callable.
        List<Step> steps = List.of(
                new Step("let loopC :LoopC = :LoopC[] let intrinsic :Intrinsic = :Intrinsic[]",
                        "declared", "", "", List.of("construction-effects")),
                new Step("loopC:.iterated", "value", "Unit", ""),
                new Step("loopC:.looped", "value", "Unit", ""),
                new Step("intrinsic:.printed", "value", "Unit", ""),
                new Step("intrinsic::printer[\"member\"]", "value", "Unit", ""));
        return new Plan("pinned-unit", producer, members, steps, "probe\nmember\n", retainedOrdinal);
    }

    private static Plan failures(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        String producer = preamble(random, constants) + """
                let @mut effects :I32 = 0
                let @mut divisor :I32 = 0
                class Failure {
                    @pub touched :I32 = { effects := (++ effects) effects }
                """ + "                    @pub fail :I32 = (% 8 divisor)\n                }\n";
        List<MemberExpectation> members = List.of(
                new MemberExpectation("Failure", "touched", "Sequence", 2),
                new MemberExpectation("Failure", "fail", "Apply", 2));
        List<Step> steps = List.of(
                new Step("let staged :Failure = :Failure[]", "failure", "", "LYR-ARITH"),
                new Step("effects", "value", "I32", "1"),
                new Step("divisor := 1 let staged :Failure = :Failure[]",
                        "declared", "", "", List.of("recovery")),
                new Step("(+ staged:.touched (* effects 10I32) staged:.fail)", "value", "I32", "22"));
        return new Plan("failures", producer, members, steps, "", retainedOrdinal);
    }

    private static Plan slots(SplittableRandom random, int retainedOrdinal) {
        Constants constants = constants(random);
        int replacement = bound(random, 1, 10), sourceValue = bound(random, 1, 10);
        int shadowValue = bound(random, 1, 10);
        String producer = preamble(random, constants) + """
                class Counter { @pub @mut read :Fn<;I32> = (=> || 1I32) }
                let @mut holder :Counter = :Counter[]
                let saved :Fn<;I32> = holder:.read
                class SlotBox {
                    @pub current :I32 = holder::read[]
                    @pub original :I32 = (saved)
                }
                let source :Fn<;I32> = (=> || %dI32)
                class Shadow { @pub value :I32 = ::source[] }
                """.formatted(sourceValue);
        List<MemberExpectation> members = List.of(
                new MemberExpectation("SlotBox", "current", "CallableCall", 0),
                new MemberExpectation("SlotBox", "original", "CallableCall", 0),
                new MemberExpectation("Shadow", "value", "Call", 0));
        List<Step> steps = List.of(
                new Step("holder:.read := (=> || " + replacement + "I32)", "declared", "", ""),
                new Step("let slotBox :SlotBox = :SlotBox[]", "declared", "", ""),
                new Step("(+ (* slotBox:.current 10I32) slotBox:.original)", "value", "I32",
                        Integer.toString(replacement * 10 + 1)),
                new Step("let source :Fn<;I32> = (=> || " + shadowValue + "I32)", "declared", "", ""),
                new Step("let shadow :Shadow = :Shadow[]", "declared", "", ""),
                new Step("shadow:.value", "value", "I32", Integer.toString(sourceValue)));
        return new Plan("slots", producer, members, steps, "", retainedOrdinal);
    }

    private static Constants constants(SplittableRandom random) {
        return new Constants(bound(random, 1, 10), bound(random, 1, 10),
                bound(random, 1, 10), bound(random, 1, 10), bound(random, 1, 10),
                bound(random, 1, 10));
    }
}
