package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.api.*;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end source construction and exact nominal JVM representation coverage. */
class NominalBytecodeTest {
    @Test void ordinaryMethodsCannotMutateAggregatesThroughImmutableSelf() {
        for (String source : java.util.List.of("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub mutate :Fn<;Unit> = (=> || { self:.values[0] := 7 })
                }
                """, """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut mutate :Fn<;Unit> = (=> || {})
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| {
                    box:.mutate := (=> || { self:.values[0] := 7 })
                })
                """)) {
            CompileResult.Failure failure = assertInstanceOf(
                    CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("ordinary-self-mutation.lyra", source).build()));
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code());
            int start = source.indexOf("self:.values[0]");
            assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                            io.mindspice.lyra.compiler.source.SourceId.path(
                                    "ordinary-self-mutation.lyra"),
                            start, start + "self:.values[0]".length()),
                    failure.diagnostics().getFirst().primarySpan());
        }
    }

    @Test void selfAliasAggregateMutationsRequireTheExactConstructor() throws Throwable {
        // Immutable-self provenance must survive alias bindings, captures,
        // and parameters that receive self.  Only the exact constructor
        // lambda for the nominal may mutate an aggregate through the root.
        String[] rejected = {
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Box = self
                        alias:.values[0] := 7
                        0
                    })
                }
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;I32> = (=> || 0)
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| {
                    box:.poke := (=> || {
                        let @mut alias :Box = self
                        alias:.values[0] := 7
                        0
                    })
                })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let transfer :Fn<@mut Box;I32> = (=> |@mut alias| {
                        alias:.values[0] := 7
                        0
                    })
                    let @pub poke :Fn<;I32> = (=> || self::transfer[self])
                }
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Box = self
                        let mutate :Fn<;I32> = (=> :I32 || { alias:.values[0] := 7  0 })
                        ::mutate[]
                    })
                }
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Array<I32> = self:.values
                        alias[0] := 7
                        alias[0]
                    })
                }
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let selected :Fn<;Array<I32>> = (=> || self:.values)
                        let @mut alias :Array<I32> = ::selected[]
                        alias[0] := 7
                        alias[0]
                    })
                }
                """};
        for (String source : rejected) {
            CompileResult.Failure failure = assertInstanceOf(
                    CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("alias-self-mutation.lyra", source).build()),
                    source);
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code(), source);
        }

        // The exact constructor lambda still owns its nominal's root, both
        // directly and through a local alias, and the writes execute.
        var artifact = compile("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut aliasValues :Array<I32> = Array<I32>[3 4]
                    Box = (=> || {
                        self:.values[0] := 7
                        let @mut alias :Box = self
                        alias:.aliasValues[0] := 8
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """);
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<;" + nominal + ">").methodHandle();
            Object box = make.invokeWithArguments();
            int[] values = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$0").invoke(box);
            int[] aliasValues = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$1").invoke(box);
            assertEquals(7, values[0]);
            assertEquals(2, values[1]);
            assertEquals(8, aliasValues[0]);
            assertEquals(4, aliasValues[1]);
        }
    }

    @Test void condBranchesEstablishDefiniteInitializationWithoutDoubleExecuting() throws Throwable {
        var artifact = compile("""
                class Box {
                    let @pub value :I32
                    Box = (=> |flag :Bool| {
                        (cond flag -> (self:.value := 1I32) _ -> (self:.value := 2I32))
                    })
                }
                let @pub make :Fn<Bool;Box> = (=> |flag| :Box[flag])
                """);
        String boxType = artifact.metadata().nominalSchemas().schemas().getFirst()
                .type().canonicalSpelling();
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<Bool;" + boxType + ">").methodHandle();
            Object truthy = make.invokeWithArguments(true);
            Object falsey = make.invokeWithArguments(false);
            assertEquals(1, truthy.getClass().getMethod("$lyra$public$get$0").invoke(truthy));
            assertEquals(2, falsey.getClass().getMethod("$lyra$public$get$0").invoke(falsey));
        }
    }

    @Test void branchMergedAliasesJoinEveryBranchProvenance() {
        // A control-flow merge must join the provenance of every reachable
        // branch.  A rebind inside one conditional/match arm may not remove
        // the self provenance the other path still carries; the mutation
        // after the merge must reject exactly like the direct alias form.
        String[] rejected = {
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;I32> = (=> || 0)
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                let install :Fn<@mut Box,Bool;Unit> = (=> |@mut box flag| {
                    box:.poke := (=> || {
                        let @mut alias :Box = self
                        (flag -> (alias := :Box[]))
                        alias:.values[0] := 7
                        0
                    })
                })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;I32> = (=> || 0)
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                let install :Fn<@mut Box,I32;Unit> = (=> |@mut box selector| {
                    box:.poke := (=> || {
                        let @mut alias :Box = self
                        (match selector 0I32 -> (alias := :Box[]) _ -> ())
                        alias:.values[0] := 7
                        0
                    })
                })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;I32> = (=> || 0)
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                let install :Fn<@mut Box;Unit> = (=> |@mut box| {
                    box:.poke := (=> || {
                        let @mut alias :Box = self
                        (#NIL -> (alias := :Box[]))
                        alias:.values[0] := 7
                        0
                    })
                })
                """};
        for (String source : rejected) {
            CompileResult.Failure failure = assertInstanceOf(
                    CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("branch-merged-self-alias.lyra", source).build()),
                    source);
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code(), source);
        }
    }

    @Test void twoLevelCallableForwardingRejectsMutationThroughForwardedSelf() {
        // self -> forward.param -> mutate.param must propagate to the
        // fixed point, not just to the immediate callee.
        String source = """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let mutate :Fn<@mut Box;Unit> = (=> |@mut inner| { inner:.values[0] := 7 })
                    let forward :Fn<@mut Box;Unit> = (=> |@mut outer| { self::mutate[outer] })
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Box = self
                        alias::forward[alias]
                        0
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """;
        CompileResult.Failure failure = assertInstanceOf(
                CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("two-level-forwarding.lyra", source).build()),
                source);
        assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                failure.diagnostics().getFirst().code(), source);
        int start = source.indexOf("inner:.values[0]");
        assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                        io.mindspice.lyra.compiler.source.SourceId.path(
                                "two-level-forwarding.lyra"),
                        start, start + "inner:.values[0]".length()),
                failure.diagnostics().getFirst().primarySpan());
    }

    @Test void identityCallResultsCarrySelfProvenance() {
        // A call result conservatively carries the union of its target and
        // argument provenance; an identity-returning callable cannot launder
        // self through its result.
        String source = """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let id :Fn<Box;Box> = (=> |value| value)
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Box = self::id[self]
                        alias:.values[0] := 7
                        0
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """;
        CompileResult.Failure failure = assertInstanceOf(
                CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("identity-self-alias.lyra", source).build()),
                source);
        assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                failure.diagnostics().getFirst().code(), source);
        int start = source.indexOf("alias:.values[0]");
        assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                        io.mindspice.lyra.compiler.source.SourceId.path(
                                "identity-self-alias.lyra"),
                        start, start + "alias:.values[0]".length()),
                failure.diagnostics().getFirst().primarySpan());
    }

    @Test void closureCallResultsCarrySelfBodyProvenance() throws Throwable {
        // A call whose target resolves to a lambda in the analyzed graph
        // must carry that lambda's body-result provenance: a closure that
        // returns captured self-derived state cannot launder it through its
        // call result, a direct alias of the closure, a higher-order
        // argument, a tuple projection or a field-stored call.
        String[] rejected = {
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let id :Fn<;Box> = (=> || self)
                        let @mut alias :Box = ::id[]
                        alias:.values[0] := 7
                        alias:.values[0]
                    })
                }
                let @pub run :Fn<;I32> = (=> || { let box :Box = :Box[] box::poke[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let id :Fn<;Box> = (=> || self)
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Box = self::id[]
                        alias:.values[0] := 7
                        alias:.values[0]
                    })
                }
                let @pub run :Fn<;I32> = (=> || { let box :Box = :Box[] box::poke[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let pair :Fn<;Tuple<Box,I32>> = (=> || Tuple[self 1])
                        let @mut alias :Box = ::pair[]:.0
                        alias:.values[0] := 7
                        alias:.values[0]
                    })
                }
                let @pub run :Fn<;I32> = (=> || { let box :Box = :Box[] box::poke[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let id :Fn<;Box> = (=> || self)
                        let aliasOf :Fn<;Box> = id
                        let @mut alias :Box = ::aliasOf[]
                        alias:.values[0] := 7
                        alias:.values[0]
                    })
                }
                let @pub run :Fn<;I32> = (=> || { let box :Box = :Box[] box::poke[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub poke :Fn<;I32> = (=> || {
                        let id :Fn<;Box> = (=> || self)
                        let apply :Fn<Fn<;Box>;Box> = (=> |h| ::h[])
                        let @mut alias :Box = ::apply[id]
                        alias:.values[0] := 7
                        alias:.values[0]
                    })
                }
                let @pub run :Fn<;I32> = (=> || { let box :Box = :Box[] box::poke[] })
                """};
        for (String source : rejected) {
            CompileResult.Failure failure = assertInstanceOf(
                    CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("closure-self-alias.lyra", source).build()),
                    source);
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code(), source);
            int start = source.indexOf("alias:.values[0]");
            assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                            io.mindspice.lyra.compiler.source.SourceId.path(
                                    "closure-self-alias.lyra"),
                            start, start + "alias:.values[0]".length()),
                    failure.diagnostics().getFirst().primarySpan(), source);
        }

        // The closure over-approximation must not reject constructor-owned
        // forms: a self-returning closure called inside the exact
        // constructor keeps its mutation authority and still compiles
        // (executing a closure that captures an uninitialized nominal self
        // remains a separate pre-existing runtime limitation), while the
        // constructor-local alias and the direct constructor write keep
        // compiling and executing.
        CompileResult closure = LyraCompiler.compile(CompileRequest.builder()
                .source("constructor-closure-alias.lyra", """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    Box = (=> || {
                        let id :Fn<;Box> = (=> || self)
                        let @mut alias :Box = ::id[]
                        alias:.values[0] := 7
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """).build());
        assertInstanceOf(CompileResult.Success.class, closure);
        var artifact = compile("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut aliasValues :Array<I32> = Array<I32>[3 4]
                    Box = (=> || {
                        let @mut alias :Box = self
                        alias:.aliasValues[0] := 8
                        self:.values[0] := 7
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """);
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<;" + nominal + ">").methodHandle();
            Object box = make.invokeWithArguments();
            int[] values = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$0").invoke(box);
            int[] aliasValues = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$1").invoke(box);
            assertEquals(7, values[0]);
            assertEquals(2, values[1]);
            assertEquals(8, aliasValues[0]);
            assertEquals(4, aliasValues[1]);
        }
    }

    @Test void boundedSelfProvenanceAnalysisFailsClosedOnDeepForwarding() {
        // Eight backward-ordered forwarding levels do not converge inside
        // the eight-pass bound; the analysis must fail closed with the
        // structured resolver diagnostic mapped to the forwarding call site
        // that was still moving at the bound, never publish a partial state.
        StringBuilder members = new StringBuilder();
        members.append(
                "let g9 :Fn<@mut Box;Unit> = (=> |@mut x9| { x9:.values[0] := 7 })\n");
        for (int i = 8; i >= 1; i--) {
            members.append("let g").append(i).append(" :Fn<@mut Box;Unit> = (=> |@mut x")
                    .append(i).append("| { self::g").append(i + 1).append("[x")
                    .append(i).append("] })\n");
        }
        String source = """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                %s
                    let @pub poke :Fn<;I32> = (=> || {
                        let @mut alias :Box = self
                        self::g1[alias]
                        self:.values[0]
                    })
                }
                let @pub run :Fn<;I32> = (=> || { let box :Box = :Box[] box::poke[] })
                """.formatted(members.toString().stripTrailing());
        CompileResult.Failure failure = assertInstanceOf(
                CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("deep-forwarding-self-alias.lyra", source).build()),
                source);
        assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                failure.diagnostics().getFirst().code());
        assertTrue(failure.diagnostics().getFirst().summary().contains(
                        "could not be decided within the bounded analysis"),
                failure.diagnostics().getFirst().summary());
        int start = source.indexOf("self::g8[x7]");
        assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                        io.mindspice.lyra.compiler.source.SourceId.path(
                                "deep-forwarding-self-alias.lyra"),
                        start, start + "self::g8[x7]".length()),
                failure.diagnostics().getFirst().primarySpan());
    }

    @Test void constructorCallResultAliasesStayLegalInsideTheExactConstructor() throws Throwable {
        // Constructor-owned self stays legal for direct roots, local
        // aliases and conditional-merged aliases, and the writes execute.
        var artifact = compile("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut aliasValues :Array<I32> = Array<I32>[3 4]
                    Box = (=> || {
                        self:.values[0] := 7
                        let @mut alias :Box = (#T -> self : self)
                        alias:.values[1] := 8
                        let @mut direct :Box = self
                        direct:.aliasValues[0] := 9
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """);
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<;" + nominal + ">").methodHandle();
            Object box = make.invokeWithArguments();
            int[] values = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$0").invoke(box);
            int[] aliasValues = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$1").invoke(box);
            assertEquals(7, values[0]);
            assertEquals(8, values[1]);
            assertEquals(9, aliasValues[0]);
            assertEquals(4, aliasValues[1]);
        }
    }

    @Test void ordinaryMutableAggregatesAndAliasRebindsKeepTheirBehavior() throws Throwable {
        // Non-self mutable aggregates, alias rebinds and tuple-member
        // rebinds are not self provenance and keep compiling and executing.
        var artifact = compile("""
                let @mut values :Array<I32> = Array<I32>[1 2]
                let @pub run :Fn<;I32> = (=> || {
                    let @mut local :Array<I32> = Array<I32>[1 2]
                    (local := Array<I32>[3 4])
                    (local[0] := 7)
                    values[0] := 9
                    (+ local[0] values[0])
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var run = module.export("run", "Fn<;I32>").methodHandle();
            assertEquals(16, (int) run.invokeExact());
        }
    }

    @Test void reboundMemberSelfClosuresTaintTheMemberSlot() {
        // Assigning a self-returning closure (or any self-carrying value)
        // into a member slot taints the member declaration, so a later
        // call through the member slot - on the same instance, a different
        // instance, or through a tuple projection of the result - carries
        // the taint and cannot launder a mutation through it.
        String[] rejected = {
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;Box> = (=> || :Box[])
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| { box:.poke := (=> || self) })
                let @pub run :Fn<;I32> = (=> || {
                    let @mut box :Box = :Box[]
                    (install box)
                    let @mut alias :Box = box::poke[]
                    alias:.values[0] := 7
                    alias:.values[0]
                })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;Tuple<Box,I32>> = (=> || Tuple[:Box[] 0])
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| { box:.poke := (=> || Tuple[self 1]) })
                let @pub run :Fn<;I32> = (=> || {
                    let @mut box :Box = :Box[]
                    (install box)
                    let @mut alias :Box = box::poke[]:.0
                    alias:.values[0] := 7
                    alias:.values[0]
                })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;Box> = (=> || :Box[])
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| { box:.poke := (=> || self) })
                let @pub run :Fn<;I32> = (=> || {
                    let @mut first :Box = :Box[]
                    let @mut second :Box = :Box[]
                    (install first)
                    let @mut alias :Box = second::poke[]
                    alias:.values[0] := 7
                    alias:.values[0]
                })
                """};
        for (String source : rejected) {
            CompileResult.Failure failure = assertInstanceOf(
                    CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("rebound-member-self-closure.lyra", source).build()),
                    source);
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code(), source);
            int start = source.indexOf("alias:.values[0]");
            assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                            io.mindspice.lyra.compiler.source.SourceId.path(
                                    "rebound-member-self-closure.lyra"),
                            start, start + "alias:.values[0]".length()),
                    failure.diagnostics().getFirst().primarySpan(), source);
        }
    }

    @Test void aliasedMemberAggregatesPreserveOriginTaint() {
        // A member-backed aggregate keeps the originating member declaration
        // when copied through a local or aggregate projection. Writing a
        // self-returning closure through either alias taints later reads of
        // that member on every instance.
        String[] rejected = {
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut slots :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let @pub run :Fn<;I32> = (=> || {
                        let closure :Fn<;Box> = (=> || self)
                        let @mut other :Box = :Box[]
                        let @mut aggregate :Array<Fn<;Box>> = other:.slots
                        aggregate[0] := closure
                        let @mut alias :Box = (other:.slots[0])
                        alias:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let @mut box :Box = :Box[] box::run[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut slots :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let @pub run :Fn<;I32> = (=> || {
                        let closure :Fn<;Box> = (=> || self)
                        let @mut other :Box = :Box[]
                        let pair :Tuple<Array<Fn<;Box>>> = Tuple[other:.slots]
                        let @mut aggregate :Array<Fn<;Box>> = pair:.0
                        aggregate[0] := closure
                        let @mut alias :Box = (other:.slots[0])
                        alias:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let @mut box :Box = :Box[] box::run[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut slots :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let @pub run :Fn<;I32> = (=> || {
                        let closure :Fn<;Box> = (=> || self)
                        let @mut other :Box = :Box[]
                        let @mut holder :Array<Array<Fn<;Box>>> =
                                Array[Array[(=> || :Box[])]]
                        holder[0] := other:.slots
                        let @mut aggregate :Array<Fn<;Box>> = holder[0]
                        aggregate[0] := closure
                        let @mut alias :Box = (other:.slots[0])
                        alias:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let @mut box :Box = :Box[] box::run[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut slots :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let install :Fn<@mut Array<Fn<;Box>>,Fn<;Box>;Unit> =
                            (=> |@mut aggregate closure| { aggregate[0] := closure })
                    let @pub run :Fn<;I32> = (=> || {
                        let closure :Fn<;Box> = (=> || self)
                        let @mut other :Box = :Box[]
                        let @mut aggregate :Array<Fn<;Box>> = other:.slots
                        self::install[aggregate closure]
                        let @mut alias :Box = (other:.slots[0])
                        alias:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let @mut box :Box = :Box[] box::run[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut slots :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let @pub @mut forwarded :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let @pub run :Fn<;I32> = (=> || {
                        let closure :Fn<;Box> = (=> || self)
                        let @mut other :Box = :Box[]
                        let @mut carrier :Box = :Box[]
                        carrier:.forwarded := other:.slots
                        let @mut aggregate :Array<Fn<;Box>> = carrier:.forwarded
                        aggregate[0] := closure
                        let @mut alias :Box = (other:.slots[0])
                        alias:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let @mut box :Box = :Box[] box::run[] })
                """,
                """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut slots :Array<Fn<;Box>> = Array[(=> || :Box[])]
                    let @pub run :Fn<;I32> = (=> || {
                        let closure :Fn<;Box> = (=> || self)
                        let @mut other :Box = :Box[]
                        let @mut aggregate :Array<Fn<;Box>> = other:.slots
                        (:= aggregate[0] closure)
                        let @mut alias :Box = (other:.slots[0])
                        alias:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let @mut box :Box = :Box[] box::run[] })
                """};
        for (String source : rejected) {
            CompileResult.Failure failure = assertInstanceOf(
                    CompileResult.Failure.class,
                    LyraCompiler.compile(CompileRequest.builder()
                            .source("aliased-member-aggregate.lyra", source).build()),
                    source);
            assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                    failure.diagnostics().getFirst().code(), source);
            int start = source.indexOf("alias:.values[0]");
            assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                            io.mindspice.lyra.compiler.source.SourceId.path(
                                    "aliased-member-aggregate.lyra"),
                            start, start + "alias:.values[0]".length()),
                    failure.diagnostics().getFirst().primarySpan(), source);
        }
    }

    @Test void memberStorageTransfersPreserveTheMutationPosition() {
        String source = """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub run :Fn<;I32> = (=> || {
                        let @mut other :Box = :Box[]
                        other:.values := self:.values
                        other:.values[0] := 7
                        self:.values[0]
                    })
                }
                let @pub go :Fn<;I32> = (=> || { let box :Box = :Box[] box::run[] })
                """;
        CompileResult.Failure failure = assertInstanceOf(
                CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .source("member-storage-transfer.lyra", source).build()),
                source);
        assertEquals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                failure.diagnostics().getFirst().code());
        int start = source.indexOf("other:.values[0]");
        assertEquals(io.mindspice.lyra.compiler.source.SourceSpan.of(
                        io.mindspice.lyra.compiler.source.SourceId.path(
                                "member-storage-transfer.lyra"),
                        start, start + "other:.values[0]".length()),
                failure.diagnostics().getFirst().primarySpan());
    }

    @Test void explicitNominalFunctionArrayLiteralsCompileAndExecute() throws Throwable {
        var artifact = compile("""
                class Box {
                    let @pub value :I32 = 7
                }
                let @pub run :Fn<;I32> = (=> || {
                    let makers :Array<Fn<;Box>> = Array<Fn<;Box>>[(=> || :Box[])]
                    let box :Box = (makers[0])
                    box:.value
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var run = module.export("run", "Fn<;I32>").methodHandle();
            assertEquals(7, (int) run.invokeExact());
        }
    }

    @Test void constructorInstalledMemberValuesStayLegalInsideTheExactConstructor() throws Throwable {
        // A member tainted by an assignment stays usable inside the exact
        // constructor: a self-returning closure installed by the constructor
        // and read back through the member slot still compiles (executing a
        // closure that captures an uninitialized nominal self remains a
        // separate pre-existing runtime limitation), and an aggregate
        // installed by the constructor and read back through the member
        // slot compiles and executes.
        CompileResult closure = LyraCompiler.compile(CompileRequest.builder()
                .source("constructor-member-closure.lyra", """
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;Box> = (=> || :Box[])
                    Box = (=> || {
                        self:.poke := (=> || self)
                        let @mut alias :Box = self::poke[]
                        alias:.values[0] := 7
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """).build());
        assertInstanceOf(CompileResult.Success.class, closure);
        var artifact = compile("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut extra :Array<I32> = Array<I32>[3 4]
                    Box = (=> || {
                        self:.extra := self:.values
                        let @mut alias :Array<I32> = self:.extra
                        alias[0] := 7
                        self:.values[1] := 8
                    })
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                """);
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<;" + nominal + ">").methodHandle();
            Object box = make.invokeWithArguments();
            int[] values = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$0").invoke(box);
            int[] extra = (int[]) box.getClass()
                    .getMethod("$lyra$public$get$1").invoke(box);
            assertEquals(7, values[0]);
            assertEquals(8, values[1]);
            // extra aliases values after the constructor rebind.
            assertEquals(7, extra[0]);
            assertEquals(8, extra[1]);
        }
    }

    @Test void freshObjectClosureMemberSlotsStayLegal() throws Throwable {
        // A member slot holding a fresh-object-returning closure carries no
        // self provenance: installing it by rebind and calling it through
        // the member slot keeps compiling and executing.
        var artifact = compile("""
                class Box {
                    let @pub @mut values :Array<I32> = Array<I32>[1 2]
                    let @pub @mut poke :Fn<;Box> = (=> || :Box[])
                }
                let install :Fn<@mut Box;Unit> = (=> |@mut box| { box:.poke := (=> || :Box[]) })
                let @pub run :Fn<;I32> = (=> || {
                    let @mut first :Box = :Box[]
                    let @mut second :Box = :Box[]
                    (install first)
                    let @mut alias :Box = second::poke[]
                    alias:.values[0] := 7
                    alias:.values[0]
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var run = module.export("run", "Fn<;I32>").methodHandle();
            assertEquals(7, (int) run.invokeExact());
        }
    }

    @Test void failedSourceFactoryInvalidatesItsTicketAndLeavesProducerUsable() throws Throwable {
        var artifact = compile("""
                class Fallible {
                    let @pub value :I32
                    Fallible = (=> |value :I32 divisor :I32| {
                        self:.value := value
                        let ignored :F64 = (/ value divisor)
                    })
                }
                let @pub make :Fn<I32,I32;Fallible> = (=> |value divisor| :Fallible[value divisor])
                """);
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<I32,I32;" + nominal + ">").methodHandle();
            var failure = assertThrows(LyraRuntimeException.class,
                    () -> make.invokeWithArguments(9, 0));
            assertEquals("LYR-ARITH", failure.code());
            Object value = make.invokeWithArguments(9, 3);
            assertEquals(9, value.getClass().getMethod("$lyra$public$get$0").invoke(value));
        }
        var stateName = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$state$")).findFirst().orElseThrow();
        var state = java.lang.classfile.ClassFile.of().parse(artifact.classes().get(stateName));
        assertTrue(state.methods().stream().filter(method -> method.methodName().stringValue().startsWith("$lyra$new$"))
                .flatMap(method -> method.code().stream()).flatMap(code -> code.elementStream())
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                .anyMatch(call -> call.owner().name().stringValue().equals(
                        "io/mindspice/lyra/runtime/LyraNominalConstruction")
                        && call.name().stringValue().equals("fail")));
    }

    @Test void constructionArgumentsEvaluateOnceFromLeftToRight() throws Throwable {
        var artifact = compile("""
                let @mut count :I32 = 0
                let next :Fn<;I32> = (=> || { count := (++ count) count })
                struct Pair { let first :I32 let second :I32 }
                let @pub run :Fn<;I32> = (=> || {
                    let pair :Pair = :Pair[(next) (next)]
                    (+ (* pair:.first 10) pair:.second)
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var run = module.export("run", "Fn<;I32>").methodHandle();
            assertEquals(12, (int) run.invokeExact());
            assertEquals(34, (int) run.invokeExact());
        }
    }

    @Test void retainedFactoryEmissionCannotReplaySessionRootInitializers() {
        var compiled = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "retained-emission.lyra", """
                        let @mut rootRuns :I32 = 0
                        let initializeRoot :Fn<;I32> = (=> || {
                            rootRuns := (++ rootRuns)
                            rootRuns
                        })
                        let rootValue :I32 = ::initializeRoot[]
                        let @mut defaults :I32 = 0
                        class Once {
                            let @pub first :I32 = { defaults := (++ defaults) defaults }
                            let @pub second :I32 = { defaults := (++ defaults) defaults }
                        }
                        let @pub make :Fn<;Once> = (=> || :Once[])
                        """, SessionSnapshot.empty())));
        CompiledArtifact artifact = compiled.artifact();
        String stateName = artifact.classes().keySet().stream()
                .filter(name -> name.contains(".$lyra$state$")).findFirst().orElseThrow();
        var state = java.lang.classfile.ClassFile.of().parse(artifact.classes().get(stateName));
        var factory = state.methods().stream()
                .filter(method -> method.methodName().stringValue().startsWith("$lyra$new$"))
                .findFirst().orElseThrow();
        var factoryCalls = factory.code().orElseThrow().elementStream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).toList();
        assertEquals(1, factoryCalls.stream().filter(call -> call.owner().name().stringValue().equals(
                        "io/mindspice/lyra/runtime/LyraNominalConstruction")
                        && call.name().stringValue().equals("begin")).count());
        assertEquals(1, factoryCalls.stream().filter(call -> call.owner().name().stringValue().equals(
                        "io/mindspice/lyra/runtime/LyraNominalConstruction")
                        && call.name().stringValue().equals("complete")).count());
        assertEquals(2, factoryCalls.stream().filter(call ->
                call.name().stringValue().startsWith("$lyra$initialize$")).count());
        assertEquals(0, factoryCalls.stream().filter(call -> call.owner().name().stringValue().equals(
                        "io/mindspice/lyra/runtime/ModuleLifecycle")
                        && (call.name().stringValue().equals("beginSessionBinding")
                        || call.name().stringValue().equals("initializeSessionBinding"))).count());

        var execute = state.methods().stream().filter(method ->
                        method.methodName().stringValue().equals("$lyra$sessionExecute"))
                .findFirst().orElseThrow();
        var executeCalls = execute.code().orElseThrow().elementStream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).toList();
        assertEquals(5, executeCalls.stream().filter(call -> call.owner().name().stringValue().equals(
                        "io/mindspice/lyra/runtime/ModuleLifecycle")
                        && call.name().stringValue().equals("beginSessionBinding")).count());
        assertEquals(5, executeCalls.stream().filter(call -> call.owner().name().stringValue().equals(
                        "io/mindspice/lyra/runtime/ModuleLifecycle")
                        && call.name().stringValue().equals("initializeSessionBinding")).count());
    }

    @Test void qualifiedConstructionUsesTheDefiningModuleFactory() throws Throwable {
        for (String mainSource : java.util.List.of("""
                import model as m
                let @pub make :Fn<I32;m->Point> = (=> |value| :m->Point[value])
                """, """
                import model->{Point as P}
                let @pub make :Fn<I32;P> = (=> |value| :P[value])
                """)) {
            var request = CompileRequest.builder().rootModule("main").resolver(SourceResolver.memory(
                    ResolvedSource.memory(io.mindspice.lyra.compiler.source.LogicalModuleId.parse("model"),
                            "memory:nominal/model", "struct @pub Point { let x :I32 }"),
                    ResolvedSource.memory(io.mindspice.lyra.compiler.source.LogicalModuleId.parse("main"),
                            "memory:nominal/main", mainSource))).build();
            var artifact = compile(request);
            var nominal = artifact.metadata().nominalSchemas().schemas().stream()
                    .filter(value -> value.type().id().name().equals("Point")).findFirst().orElseThrow().type();
            try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
                Object point = module.export("make", "Fn<I32;" + nominal + ">")
                        .methodHandle().invokeWithArguments(27);
                assertEquals(27, point.getClass().getMethod("$lyra$public$get$0").invoke(point));
                assertEquals(nominal, ((LyraNominalObject) point).nominalType());
            }
        }
    }

    @Test void nominalReferencesAreTruthTestableInAllTruthContexts() throws Throwable {
        var artifact = compile("""
                struct Point { let x :I32 }
                class Box { let value :I32 = 1 }
                let @pub structConditional :Fn<;Bool> = (=> || {
                    let point :Point = :Point[1]
                    (point -> #T : #F)
                })
                let @pub classConditional :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (box -> #T : #F)
                })
                let @pub directAnd :Fn<;Bool> = (=> || {
                    let point :Point = :Point[1]
                    let box :Box = :Box[]
                    (and point box)
                })
                let @pub directOr :Fn<;Bool> = (=> || {
                    let point :Point = :Point[1]
                    let box :Box = :Box[]
                    (or (not box) point)
                })
                let @pub directXor :Fn<;Bool> = (=> || {
                    let point :Point = :Point[1]
                    (xor point #F)
                })
                let @pub directNot :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (not box)
                })
                let @pub conditionalChain :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (cond box -> #T _ -> #F)
                })
                let @pub guardedMatch :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (match 1 _ when box -> #T _ -> #F)
                })
                let @pub nilableNil :Fn<;Bool> = (=> || {
                    let @nil point :Point = #NIL
                    (point -> #T : #F)
                })
                let @pub nilablePresent :Fn<;Bool> = (=> || {
                    let @nil point :Point = :Point[1]
                    (point -> #T : #F)
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            assertTrue((boolean) module.export("structConditional", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("classConditional", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("directAnd", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("directOr", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("directXor", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertFalse((boolean) module.export("directNot", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("conditionalChain", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("guardedMatch", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertFalse((boolean) module.export("nilableNil", "Fn<;Bool>")
                    .methodHandle().invokeExact());
            assertTrue((boolean) module.export("nilablePresent", "Fn<;Bool>")
                    .methodHandle().invokeExact());
        }
    }

    @Test void sourceFactoriesExecuteStructDefaultsAndClassConstructors() throws Throwable {
        var structs = compile("""
                struct Point {
                    let first :I32 = self:.required
                    let @mut required :I32
                    let next :I32 = (+ self:.first 1)
                }
                let @pub make :Fn<I32;Point> = (=> |value| :Point[value])
                let @pub read :Fn<Point;I32> = (=> |point| point:.required)
                let @pub set :Fn<@mut Point,I32;Unit> = (=> |@mut point value| { point:.required := value })
                """);
        var pointSchema = structs.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(structs); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<I32;" + pointSchema + ">").methodHandle();
            Object point = make.invokeWithArguments(41);
            Class<?> type = point.getClass();
            assertEquals(41, type.getMethod("$lyra$public$get$0").invoke(point));
            assertEquals(41, type.getMethod("$lyra$public$get$1").invoke(point));
            assertEquals(42, type.getMethod("$lyra$public$get$2").invoke(point));
            var read = module.export("read", "Fn<" + pointSchema + ";I32>").methodHandle();
            var set = module.export("set", "Fn<@mut" + pointSchema + ",I32;Unit>").methodHandle();
            assertEquals(41, read.invokeWithArguments(point));
            set.invokeWithArguments(point, 73);
            assertEquals(73, read.invokeWithArguments(point));
        }

        var classes = compile("""
                class Counter {
                    let @pub @mut value :I32
                    Counter = (=> |start :I32| { self:.value := start })
                    let @pub increment :Fn<;Unit> = (=> || { self:.value := (++ self:.value) })
                    let @pub current :Fn<;I32> = (=> || self:.value)
                }
                let @pub make :Fn<I32;Counter> = (=> |value| :Counter[value])
                let @pub run :Fn<I32;I32> = (=> |value| {
                    let counter :Counter = :Counter[value]
                    counter::increment[]
                    counter::current[]
                })
                """);
        var counterSchema = classes.metadata().nominalSchemas().schemas().getFirst().type();
        try (var loaded = LyraRuntime.load(classes); var module = loaded.instantiate()) {
            var make = module.export("make", "Fn<I32;" + counterSchema + ">").methodHandle();
            Object counter = make.invokeWithArguments(17);
            assertEquals(17, counter.getClass().getMethod("$lyra$public$get$0").invoke(counter));
            assertEquals(18, module.export("run", "Fn<I32;I32>").methodHandle().invokeWithArguments(17));
        }

        var methods = compile("""
                class Cell { let @pub @mut read :Fn<;I32> = (=> || 1) }
                let @pub savedBehavior :Fn<;I32> = (=> || {
                    let @mut cell :Cell = :Cell[]
                    let saved :Fn<;I32> = cell:.read
                    cell:.read := (=> || 2)
                    (+ (* (saved) 10) cell::read[])
                })
                """);
        try (var loaded = LyraRuntime.load(methods); var module = loaded.instantiate()) {
            assertEquals(12, (int) module.export("savedBehavior", "Fn<;I32>")
                    .methodHandle().invokeExact());
        }
    }

    @Test void directMethodReplacementCapturesTheSelectedReceiverAsContextualSelf() throws Throwable {
        var artifact = compile("""
                class Counter {
                    let @pub @mut value :I32 = 0
                    let @pub @mut change :Fn<I32;I32> = (=> |delta| {
                        self:.value := (+ self:.value delta)
                        self:.value
                    })
                }
                let install :Fn<@mut Counter;Unit> = (=> |@mut counter| {
                    counter:.change := (=> |delta| {
                        self:.value := (+ self:.value (* delta 2))
                        let read :Fn<;I32> = (=> || self:.value)
                        (read)
                    })
                })
                let @pub run :Fn<;I32> = (=> || {
                    let @mut counter :Counter = :Counter[]
                    let saved :Fn<I32;I32> = counter:.change
                    (install counter)
                    let old :I32 = (saved 3)
                    let current :I32 = counter::change[4]
                    (+ (* old 10) current)
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            assertEquals(41, (int) module.export("run", "Fn<;I32>")
                    .methodHandle().invokeExact());
        }
    }

    @Test void replacementSelectsReceiverOnceAndExistingCallablesKeepTheirReceiver() throws Throwable {
        var artifact = compile("""
                let @mut selections :I32 = 0
                let next :Fn<;I32> = (=> || { selections := (++ selections) 0 })
                class Counter {
                    let @pub @mut value :I32
                    Counter = (=> |start :I32| { self:.value := start })
                    let @pub @mut change :Fn<I32;I32> = (=> |delta| {
                        self:.value := (+ self:.value delta)
                        self:.value
                    })
                }
                let @pub run :Fn<;I32> = (=> || {
                    let first :Counter = :Counter[1]
                    let @mut second :Counter = :Counter[10]
                    let original :Fn<I32;I32> = first:.change
                    second:.change := original
                    second::change[2]
                    let @mut values :Array<Counter> = Array[:Counter[0]]
                    values[(next)]:.change := (=> |delta| {
                        self:.value := (+ self:.value delta)
                        self:.value
                    })
                    values[0]::change[5]
                    (+ (* selections 100) (+ (* first:.value 10) second:.value))
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            assertEquals(140, (int) module.export("run", "Fn<;I32>")
                    .methodHandle().invokeExact());
        }
    }

    @Test void structsCompareStructurallyWithCyclesWhileClassesUseIdentity() throws Throwable {
        var artifact = compile("""
                class Box { let @pub value :I32 = 7 }
                struct Node {
                    let @mut value :I32
                    let @mut @nil next :Node = #NIL
                }
                struct Holder { let box :Box }
                let @pub equalValues :Fn<;Bool> = (=> || (== :Node[3] :Node[3]))
                let @pub differentValues :Fn<;Bool> = (=> || (== :Node[3] :Node[4]))
                let @pub equalCycles :Fn<;Bool> = (=> || {
                    let @mut left :Node = :Node[5]
                    let @mut right :Node = :Node[5]
                    left:.next := left
                    right:.next := right
                    (== left right)
                })
                let @pub differentCycles :Fn<;Bool> = (=> || {
                    let @mut left :Node = :Node[5]
                    let @mut right :Node = :Node[6]
                    left:.next := left
                    right:.next := right
                    (== left right)
                })
                let @pub unequalCycles :Fn<;Bool> = (=> || {
                    let @mut left :Node = :Node[5]
                    let @mut right :Node = :Node[6]
                    left:.next := left
                    right:.next := right
                    (!= left right)
                })
                let @pub matchedCycle :Fn<;Bool> = (=> || {
                    let @mut left :Node = :Node[5]
                    let @mut right :Node = :Node[5]
                    left:.next := left
                    right:.next := right
                    (match left right -> #T _ -> #F)
                })
                let @pub freshContexts :Fn<;Bool> = (=> || {
                    let @mut left :Node = :Node[5]
                    let @mut right :Node = :Node[5]
                    left:.next := left
                    right:.next := right
                    let before :Bool = (== left right)
                    right:.value := 6
                    let after :Bool = (== left right)
                    (and before (not after))
                })
                let @pub nilStructs :Fn<;Bool> = (=> || {
                    let @nil empty :Node = #NIL
                    (== empty #NIL)
                })
                let @pub classAlias :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (== box box)
                })
                let @pub classDistinct :Fn<;Bool> = (=> || (== :Box[] :Box[]))
                let @pub classIdentity :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (eq? box box)
                })
                let @pub nestedClassIdentity :Fn<;Bool> = (=> || {
                    let box :Box = :Box[]
                    (== :Holder[box] :Holder[box])
                })
                let @pub nestedStructs :Fn<;Bool> = (=> ||
                    (== Tuple[Array[:Node[9]]] Tuple[Array[:Node[9]]]))
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            assertTrue((boolean) module.export("equalValues", "Fn<;Bool>").methodHandle().invokeExact());
            assertFalse((boolean) module.export("differentValues", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("equalCycles", "Fn<;Bool>").methodHandle().invokeExact());
            assertFalse((boolean) module.export("differentCycles", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("unequalCycles", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("matchedCycle", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("freshContexts", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("nilStructs", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("classAlias", "Fn<;Bool>").methodHandle().invokeExact());
            assertFalse((boolean) module.export("classDistinct", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("classIdentity", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("nestedClassIdentity", "Fn<;Bool>").methodHandle().invokeExact());
            assertTrue((boolean) module.export("nestedStructs", "Fn<;Bool>").methodHandle().invokeExact());
        }
    }

    @Test void emittedNominalSignaturesLoadAndRejectSameClassForeignProducers() throws Throwable {
        var artifact = compile("struct Node { } let @pub echo :Fn<@nil Node;@nil Node> = (=> |value| value) "
                + "let @pub echoArray :Fn<Array<@nil Node>;Array<@nil Node>> = (=> |values| values)");
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        String signature = "Fn<@nil" + nominal + ";@nil" + nominal + ">";
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var echo = module.export("echo", signature).methodHandle();
            assertNull(echo.invokeWithArguments((Object) null));
            var type = echo.type().parameterType(0).asSubclass(LyraNominalObject.class);
            assertEquals(type, echo.type().returnType());
            var echoArray = module.export("echoArray", "Fn<Array<@nil" + nominal + ">;Array<@nil" + nominal + ">>").methodHandle();
            Object[] values = (Object[]) java.lang.reflect.Array.newInstance(type, 2);
            assertSame(values, echoArray.invokeWithArguments((Object) values));
            var foreignProducer = producer(artifact);
            var foreignTicket = begin(artifact, foreignProducer, type);
            var foreign = type.getConstructor(LyraNominalConstruction.class).newInstance(foreignTicket);
            foreignTicket.complete(foreign); foreignProducer.open();
            assertThrows(LyraLinkException.class, () -> echo.invokeWithArguments(foreign));
            values[1] = foreign;
            assertThrows(LyraLinkException.class, () -> echoArray.invokeWithArguments((Object) values));
            foreignProducer.close();
        }
    }

    @Test void nominalSignatureMetadataIsResolvedOncePerObjectAndFailedClosedLifecyclesStayFailClosed() throws Throwable {
        var artifact = compile("""
                class Box {
                    let @pub @mut apply :Fn<I32;I32> = (=> |x| (+ x 1))
                    let @mut hidden :Fn<I32;I32> = (=> |x| (+ x 2))
                }
                let @pub make :Fn<;Box> = (=> || :Box[])
                let @pub run :Fn<Box,I32;I32> = (=> |box x| (box:.apply x))
                """);
        var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
        var type = representation(artifact, "Box");
        var producer = producer(artifact);
        var ticket = begin(artifact, producer, type);
        var box = type.getConstructor(LyraNominalConstruction.class).newInstance(ticket);
        // One deterministic private final non-static metadata field per
        // distinct callable member signature, resolved exactly once from the
        // bound producer authority during construction.
        var metadata = java.util.Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.getName().startsWith("$lyra$signature$")).toList();
        assertEquals(1, metadata.size(), "apply and hidden share one exact Fn<I32;I32> metadata field");
        var signatureField = metadata.getFirst();
        assertTrue(Modifier.isPrivate(signatureField.getModifiers())
                && Modifier.isFinal(signatureField.getModifiers())
                && !Modifier.isStatic(signatureField.getModifiers()));
        signatureField.setAccessible(true);
        assertEquals(LyraSignature.parse("Fn<I32;I32>"), signatureField.get(box));
        // A failed construction leaves every getter boundary fail-closed even
        // though its metadata field was already resolved.
        producer.open();
        ticket.fail(new IllegalStateException("failed construction"));
        assertInstanceOf(LyraInitializationException.class, assertThrows(InvocationTargetException.class,
                () -> box.getClass().getMethod("$lyra$public$get$0").invoke(box)).getCause());
        producer.close();
        // The exact language-constructed getter path resolves its own metadata
        // per object, returns the stored closure, invokes it, and rejects
        // reads after the module closes.
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            Object first = module.export("make", "Fn<;" + nominal + ">").methodHandle().invokeWithArguments();
            Object second = module.export("make", "Fn<;" + nominal + ">").methodHandle().invokeWithArguments();
            var firstField = java.util.Arrays.stream(first.getClass().getDeclaredFields())
                    .filter(field -> field.getName().startsWith("$lyra$signature$")).findFirst().orElseThrow();
            var secondField = java.util.Arrays.stream(second.getClass().getDeclaredFields())
                    .filter(field -> field.getName().startsWith("$lyra$signature$")).findFirst().orElseThrow();
            firstField.setAccessible(true);
            secondField.setAccessible(true);
            assertEquals(LyraSignature.parse("Fn<I32;I32>"), firstField.get(first));
            assertEquals(LyraSignature.parse("Fn<I32;I32>"), secondField.get(second));
            Object stored = first.getClass().getMethod("$lyra$public$get$0").invoke(first);
            java.lang.reflect.Method invoke = stored.getClass().getMethod("invoke", int.class);
            invoke.setAccessible(true);
            assertEquals(9, invoke.invoke(stored, 8));
            assertSame(stored, first.getClass().getMethod("$lyra$public$get$0").invoke(first));
            module.close();
            assertInstanceOf(LyraClosedException.class, assertThrows(InvocationTargetException.class,
                    () -> first.getClass().getMethod("$lyra$public$get$0").invoke(first)).getCause());
        }
    }

    @Test void typedFacadeRejectsForeignNominalStoresBeforeChangingTheBinding() throws Exception {
        var artifact = compile("struct Node { } let @pub @mut @nil stored :Node = #NIL "
                + "let @pub read :Fn<;@nil Node> = (=> || stored)");
        var type = representation(artifact, "Node");
        String facadeName = artifact.classes().keySet().stream().filter(name -> name.contains(".$lyra$facade$")).findFirst().orElseThrow();
        var facadeType = Class.forName(facadeName, true, type.getClassLoader());
        Object facade = facadeType.getMethod("$lyra$create").invoke(null);
        var setter = java.util.Arrays.stream(facadeType.getMethods()).filter(method -> method.getParameterCount() == 1
                && method.getParameterTypes()[0] == type && method.getReturnType() == void.class).findFirst().orElseThrow();
        var getters = java.util.Arrays.stream(facadeType.getMethods()).filter(method -> method.getParameterCount() == 0
                && method.getReturnType() == type).toList();
        assertFalse(getters.isEmpty());
        var producer = producer(artifact);
        var ticket = begin(artifact, producer, type);
        var foreign = type.getConstructor(LyraNominalConstruction.class).newInstance(ticket);
        ticket.complete(foreign); producer.open();
        assertInstanceOf(LyraLinkException.class, assertThrows(InvocationTargetException.class,
                () -> setter.invoke(facade, foreign)).getCause());
        for (var getter : getters) assertNull(getter.invoke(facade));
        producer.close();
        facadeType.getMethod("close").invoke(facade);
    }

    /**
     * Same-generation execution of every nilable member-read contract
     * consumer (issue #8): explicit annotation, coalesce, predicate narrowing
     * and value match must compile to bytecode and execute under current nil
     * rules without reaching the IR boundary.
     */
    @Test void nilableMemberReadContractsCompileAndExecuteInOneGeneration() throws Throwable {
        var artifact = compile("""
                class Box { let @pub @nil n :I32 = #NIL }
                class Full { let @pub @nil n :I32 = 5I32 }
                class Plain { let @pub n :I32 = 4I32 }
                let @pub annotated :Fn<;I32> = (=> || {
                    let box :Box = :Box[]
                    let v :@nil I32 = box:.n
                    ((!= v #NIL) -> 1I32 : 0I32)
                })
                let @pub widened :Fn<;I32> = (=> || {
                    let box :Plain = :Plain[]
                    let v :@nil I32 = box:.n
                    ((!= v #NIL) -> 1I32 : 0I32)
                })
                let @pub coalesced :Fn<;I32> = (=> || {
                    let box :Box = :Box[]
                    (box:.n : 7I32)
                })
                let @pub narrowed :Fn<;I32> = (=> || {
                    let box :Full = :Full[]
                    (box:.n narrowed -> narrowed : 0I32)
                })
                let @pub matchedNil :Fn<;I32> = (=> || {
                    let box :Box = :Box[]
                    (match box:.n #NIL -> 1I32 _ -> 0I32)
                })
                let @pub matchedValue :Fn<;I32> = (=> || {
                    let box :Plain = :Plain[]
                    (match box:.n 4I32 -> 1I32 _ -> 0I32)
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            assertEquals(0, (int) module.export("annotated", "Fn<;I32>").methodHandle().invokeExact());
            assertEquals(1, (int) module.export("widened", "Fn<;I32>").methodHandle().invokeExact());
            assertEquals(7, (int) module.export("coalesced", "Fn<;I32>").methodHandle().invokeExact());
            assertEquals(5, (int) module.export("narrowed", "Fn<;I32>").methodHandle().invokeExact());
            assertEquals(1, (int) module.export("matchedNil", "Fn<;I32>").methodHandle().invokeExact());
            assertEquals(1, (int) module.export("matchedValue", "Fn<;I32>").methodHandle().invokeExact());
        }
    }

    private static CompiledArtifact compile(String source) {
        return compile(CompileRequest.source("nominal-bytecode.lyra", source));
    }

    private static CompiledArtifact compile(CompileRequest request) {
        var result = LyraCompiler.compile(request);
        if (!(result instanceof CompileResult.Success success)) throw new AssertionError(result);
        return success.artifact();
    }

    private static Class<? extends LyraNominalObject> representation(CompiledArtifact artifact, String name) throws Exception {
        var schema = artifact.metadata().nominalSchemas().schemas().stream()
                .filter(value -> value.type().id().name().equals(name)).findFirst().orElseThrow();
        String binaryName = artifact.classes().keySet().stream()
                .filter(value -> value.endsWith(".$lyra$nominal$" + schema.type().id().stableHash())).findFirst().orElseThrow();
        var loader = new ClassLoader(NominalBytecodeTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String requested) throws ClassNotFoundException {
                byte[] bytes = artifact.classes().get(requested);
                if (bytes == null) throw new ClassNotFoundException(requested);
                return defineClass(requested, bytes, 0, bytes.length);
            }
        };
        return Class.forName(binaryName, true, loader).asSubclass(LyraNominalObject.class);
    }

    private static ModuleLifecycle producer(CompiledArtifact artifact) {
        return ModuleLifecycle.forArtifact(artifact.metadata().rootModuleId(),
                ModuleLifecycle.newArtifactKey(RuntimeOptions.defaults(), artifact.metadata()));
    }

    private static LyraNominalConstruction begin(CompiledArtifact artifact, ModuleLifecycle producer,
                                                 Class<? extends LyraNominalObject> representation) {
        var schema = artifact.metadata().nominalSchemas().schemas().stream().filter(value ->
                representation.getName().endsWith(value.type().id().stableHash())).findFirst().orElseThrow();
        return LyraNominalConstruction.begin(producer.closureAuthority(), schema.type().canonicalSpelling(), representation);
    }

    @Test void sourceEmitsAndLoadsExactPrimitiveObjectMethods() throws Throwable {
        var artifact = compile("""
                struct Point { let x :I32 let @mut y :I64 }
                let @pub answer :Fn<;I32> = (=> || 42)
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            assertEquals(42, (int) module.export("answer", "Fn<;I32>").methodHandle().invokeExact());
        }
        var type = representation(artifact, "Point");
        var repeated = compile("struct Point { let x :I32 let @mut y :I64 }\nlet @pub answer :Fn<;I32> = (=> || 42)\n");
        assertEquals(artifact.classes().keySet(), repeated.classes().keySet());
        artifact.classes().forEach((name, bytes) -> assertArrayEquals(bytes, repeated.classes().get(name), name));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(LyraNominalObject.class, type.getSuperclass());
        assertEquals(int.class, type.getDeclaredField("$lyra$field$0").getType());
        assertEquals(long.class, type.getDeclaredField("$lyra$field$1").getType());
        assertTrue(Modifier.isPrivate(type.getDeclaredField("$lyra$field$0").getModifiers()));
        assertThrows(NoSuchMethodException.class, type::getConstructor);
        assertThrows(NoSuchMethodException.class, () -> type.getMethod("$lyra$public$set$0", int.class));
        var producer = producer(artifact);
        var ticket = begin(artifact, producer, type);
        var value = type.getConstructor(LyraNominalConstruction.class).newInstance(ticket);
        type.getMethod("$lyra$initialize$0", LyraNominalConstruction.class, int.class).invoke(value, ticket, 11);
        type.getMethod("$lyra$initialize$1", LyraNominalConstruction.class, long.class).invoke(value, ticket, 17L);
        ticket.complete(value);
        producer.open();
        assertEquals(11, type.getMethod("$lyra$public$get$0").invoke(value));
        type.getMethod("$lyra$public$set$1", long.class).invoke(value, 23L);
        assertEquals(23L, type.getMethod("$lyra$public$get$1").invoke(value));
        type.getMethod("$lyra$set$1", LyraClosureAuthority.class, long.class).invoke(value, producer.closureAuthority(), 29L);
        assertEquals(29L, type.getMethod("$lyra$public$get$1").invoke(value));
        var unrelated = producer(artifact);
        assertInstanceOf(LyraLinkException.class, assertThrows(InvocationTargetException.class, () ->
                type.getMethod("$lyra$set$1", LyraClosureAuthority.class, long.class).invoke(value, unrelated.closureAuthority(), 31L)).getCause());
        assertEquals(29L, type.getMethod("$lyra$public$get$1").invoke(value));
        unrelated.open(); unrelated.close();
        var wrongThread = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { type.getMethod("$lyra$public$get$0").invoke(value); }
            catch (InvocationTargetException failure) { wrongThread.set(failure.getCause()); }
            catch (Throwable failure) { wrongThread.set(failure); }
        });
        thread.start(); thread.join();
        assertInstanceOf(LyraThreadException.class, wrongThread.get());
        producer.close();
        var failure = assertThrows(InvocationTargetException.class, () -> type.getMethod("$lyra$public$get$0").invoke(value));
        assertInstanceOf(LyraClosedException.class, failure.getCause());
    }

    @Test void emittedReferenceChecksPreserveAliasesAndRejectForeignObjects() throws Exception {
        var artifact = compile("struct Node { let @mut @nil next :Node = #NIL let text :String let items :Array<@nil String> }");
        var type = representation(artifact, "Node");
        var producer = producer(artifact);
        var ticket = begin(artifact, producer, type);
        var value = type.getConstructor(LyraNominalConstruction.class).newInstance(ticket);
        type.getMethod("$lyra$initialize$0", LyraNominalConstruction.class, type).invoke(value, ticket, value);
        assertSame(value, type.getMethod("$lyra$initialization$get$0", LyraNominalConstruction.class).invoke(value, ticket));
        var setText = type.getMethod("$lyra$initialize$1", LyraNominalConstruction.class, String.class);
        assertInstanceOf(NullPointerException.class, assertThrows(InvocationTargetException.class,
                () -> setText.invoke(value, ticket, null)).getCause());
        setText.invoke(value, ticket, "node");
        String[] items = { "a", null, "b" };
        type.getMethod("$lyra$initialize$2", LyraNominalConstruction.class, String[].class).invoke(value, ticket, items);
        ticket.complete(value); producer.open();
        assertSame(items, type.getMethod("$lyra$public$get$2").invoke(value));
        type.getMethod("$lyra$public$set$0", type).invoke(value, value);
        assertSame(value, type.getMethod("$lyra$public$get$0").invoke(value));
        var foreignProducer = producer(artifact);
        var foreignTicket = begin(artifact, foreignProducer, type);
        var foreign = type.getConstructor(LyraNominalConstruction.class).newInstance(foreignTicket);
        type.getMethod("$lyra$initialize$0", LyraNominalConstruction.class, type).invoke(foreign, foreignTicket, null);
        setText.invoke(foreign, foreignTicket, "foreign");
        type.getMethod("$lyra$initialize$2", LyraNominalConstruction.class, String[].class).invoke(foreign, foreignTicket, items);
        foreignTicket.complete(foreign); foreignProducer.open();
        assertInstanceOf(LyraLinkException.class, assertThrows(InvocationTargetException.class,
                () -> type.getMethod("$lyra$public$set$0", type).invoke(value, foreign)).getCause());
        assertSame(value, type.getMethod("$lyra$public$get$0").invoke(value));
        producer.close(); foreignProducer.close();
    }

    @Test void privateFieldsHaveNoPublicSurfaceAndInterfaceProxiesAreNotMethods() throws Exception {
        var privateArtifact = compile("class Secret { let hidden :I32 = 7 }");
        var secret = representation(privateArtifact, "Secret");
        assertThrows(NoSuchMethodException.class, () -> secret.getMethod("$lyra$public$get$0"));
        var secretProducer = producer(privateArtifact);
        var secretTicket = begin(privateArtifact, secretProducer, secret);
        var secretValue = secret.getConstructor(LyraNominalConstruction.class).newInstance(secretTicket);
        secret.getMethod("$lyra$initialize$0", LyraNominalConstruction.class, int.class).invoke(secretValue, secretTicket, 7);
        secretTicket.complete(secretValue); secretProducer.open();
        assertEquals(7, secret.getMethod("$lyra$get$0", LyraClosureAuthority.class).invoke(secretValue, secretProducer.closureAuthority()));
        secretProducer.close();

        var artifact = compile("class Functions { let @pub @mut callback :Fn<;I32> = (=> || 7) }");
        var type = representation(artifact, "Functions");
        var classModel = java.lang.classfile.ClassFile.of().parse(artifact.classes().get(type.getName()));
        assertTrue(classModel.methods().stream().flatMap(method -> method.code().stream()).flatMap(code -> code.elementStream())
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                .noneMatch(call -> call.owner().name().stringValue().equals("io/mindspice/lyra/runtime/LyraSignature")
                        && call.name().stringValue().equals("parse")));
        var producer = producer(artifact);
        var ticket = begin(artifact, producer, type);
        var value = type.getConstructor(LyraNominalConstruction.class).newInstance(ticket);
        var functionType = type.getDeclaredField("$lyra$field$0").getType();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var proxy = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { functionType },
                (ignored, method, arguments) -> { calls.incrementAndGet(); return 7; });
        assertInstanceOf(LyraLinkException.class, assertThrows(InvocationTargetException.class, () ->
                type.getMethod("$lyra$initialize$0", LyraNominalConstruction.class, functionType).invoke(value, ticket, proxy)).getCause());
        assertThrows(LyraInitializationException.class, () -> ticket.complete(value));
        assertEquals(0, calls.get());
        ticket.fail(new IllegalStateException("rejected foreign callback"));
        producer.open(); producer.close();
    }

    @Test void seededEmittedFieldsMatchIndependentValuesAndMutability() throws Exception {
        int cases = Integer.getInteger("lyra.nominal.bytecode.cases", Integer.getInteger("lyra.fuzz.cases", 24));
        for (long seed : new long[] { 719, 20260912 }) {
            var random = new java.util.Random(seed);
            for (int index = 0; index < cases; index++) {
                boolean wide = random.nextBoolean();
                boolean mutable = random.nextBoolean();
                boolean classType = random.nextBoolean();
                String scalar = wide ? "I64" : "I32";
                Object expected;
                if (wide) expected = (long) random.nextInt(1_000_000);
                else expected = random.nextInt(1_000_000);
                String constructor = classType ? "" : "value";
                String parameters = classType ? "" : scalar;
                String fieldDefault = classType ? " = " + expected : "";
                String source = (classType ? "class" : "struct") + " Sample { let "
                        + (classType ? "@pub " : "") + (mutable ? "@mut " : "")
                        + "value :" + scalar + fieldDefault + " }\n"
                        + "let @pub make :Fn<" + parameters + ";Sample> = (=> |"
                        + (classType ? "" : "value") + "| :Sample[" + constructor + "])\n"
                        + (mutable ? "let @pub set :Fn<@mut Sample," + scalar
                        + ";Unit> = (=> |@mut sample value| { sample:.value := value })" : "");
                String replay = "seed=" + seed + ", index=" + index + "\n" + source;
                var artifact = compile(source);
                var nominal = artifact.metadata().nominalSchemas().schemas().getFirst().type();
                Class<?> primitive = wide ? long.class : int.class;
                try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
                    var make = module.export("make", "Fn<" + parameters + ";" + nominal + ">")
                            .methodHandle();
                    Object value = classType ? make.invokeWithArguments()
                            : make.invokeWithArguments(expected);
                    Class<?> type = value.getClass();
                    assertEquals(primitive, type.getDeclaredField("$lyra$field$0").getType(), replay);
                    assertEquals(expected, type.getMethod("$lyra$public$get$0").invoke(value), replay);
                    if (mutable) {
                        Object next;
                        if (wide) next = (long) random.nextInt(1_000_000);
                        else next = random.nextInt(1_000_000);
                        module.export("set", "Fn<@mut" + nominal + "," + scalar + ";Unit>")
                                .methodHandle().invokeWithArguments(value, next);
                        assertEquals(next, type.getMethod("$lyra$public$get$0").invoke(value), replay);
                    } else {
                        assertThrows(NoSuchMethodException.class,
                                () -> type.getMethod("$lyra$public$set$0", primitive), replay);
                    }
                } catch (Throwable failure) {
                    if (failure instanceof Exception exception) throw exception;
                    if (failure instanceof Error error) throw error;
                    throw new AssertionError(replay, failure);
                }
            }
        }
    }
}
