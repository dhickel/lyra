package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.ir.CallableStorageRouteProof;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.IrTraversal;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceResolver;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.ModuleHandle;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #6 call homogeneity: after resolution, (f args) and ::f[args] share
 * canonical call semantics and lowering whenever the exact declaration/storage
 * route is proven and its complete entry/write boundary authenticates the
 * contract.  Opaque targets keep full per-call authentication.
 */
public final class CallableCallParityTest {

    private static CompiledArtifact compile(String source) {
        CompileResult result = LyraCompiler.compile(CompileRequest.source("callable-parity.lyra", source));
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("fixture failed to compile: " + result);
        }
        return success.artifact();
    }

    private static CompiledArtifact compileTwo(String dep, String main) {
        CompileRequest request = CompileRequest.builder().rootModule("main").resolver(SourceResolver.memory(
                ResolvedSource.memory(io.mindspice.lyra.compiler.source.LogicalModuleId.parse("dep"),
                        "memory:callable-parity/dep", dep),
                ResolvedSource.memory(io.mindspice.lyra.compiler.source.LogicalModuleId.parse("main"),
                        "memory:callable-parity/main", main))).build();
        CompileResult result = LyraCompiler.compile(request);
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("fixture failed to compile: " + result);
        }
        return success.artifact();
    }

    private static Object call(CompiledArtifact artifact, String export, String signature,
                               Object... args) throws Throwable {
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                return module.export(export, signature).methodHandle().invokeWithArguments(args);
            } finally {
                module.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Canonical semantics parity across proven routes

    @Test
    void localRecursiveParameterCaptureAndMutableRoutesShareCanonicalSemantics() throws Throwable {
        String source = """
                let @pub @mut shift :Fn<I32;I32> = (=> |x| (+ x 1))
                let @pub runS :Fn<I32;I32> = (=> |value| {
                  let @mut counter :I32 = 0
                  let local :Fn<I32;I32> = (=> |n| {
                    counter := (+ counter 1)
                    ((<= n 0) -> value : (local (- n (shift 1)))) })
                  let capture :Fn<I32;I32> = local
                  (capture 6) })
                let @pub runF :Fn<I32;I32> = (=> |value| {
                  let @mut counter :I32 = 0
                  let local :Fn<I32;I32> = (=> |n| {
                    counter := (+ counter 1)
                    ((<= n 0) -> value : ::local[(- n, ::shift[1])]) })
                  let capture :Fn<I32;I32> = local, ::capture[6] })
                """;
        CompiledArtifact artifact = compile(source);
        assertEquals(42, (int) call(artifact, "runS", "Fn<I32;I32>", 42));
        assertEquals(42, (int) call(artifact, "runF", "Fn<I32;I32>", 42));
    }

    @Test
    void mutableRebindingObservesTheSameCurrentSlotInBothSpellings() throws Throwable {
        String source = """
                let @pub @mut f :Fn<I32;I32> = (=> |x| (+ x 1))
                let @pub runS :Fn<;I32> = (=> | | { let saved :Fn<I32;I32> = f f := (=> |x| (+ x 10)) (+ (saved 1) (f 1)) })
                let @pub runF :Fn<;I32> = (=> | | { let saved :Fn<I32;I32> = f f := (=> |x| (+ x 10)) (+ ::saved[1], ::f[1]) })
                """;
        CompiledArtifact artifact = compile(source);
        assertEquals(13, (int) call(artifact, "runS", "Fn<;I32>"));
        assertEquals(13, (int) call(artifact, "runF", "Fn<;I32>"));
    }

    @Test
    void selectiveAndQualifiedImportsShareCanonicalSemantics() throws Throwable {
        CompiledArtifact selective = compileTwo(
                "let @pub @mut base :I32 = 10 let @pub add1 :Fn<I32;I32> = (=> |x| (+ x base))",
                "import dep->{add1}\n"
                        + "let @pub runS :Fn<I32;I32> = (=> |x| (add1 x))\n"
                        + "let @pub runF :Fn<I32;I32> = (=> |x| ::add1[x])");
        assertEquals(15, (int) call(selective, "runS", "Fn<I32;I32>", 5));
        assertEquals(15, (int) call(selective, "runF", "Fn<I32;I32>", 5));

        CompiledArtifact qualified = compileTwo(
                "let @pub add1 :Fn<I32;I32> = (=> |x| (+ x 1))",
                "import dep\n"
                        + "let @pub runS :Fn<I32;I32> = (=> |x| (dep->:.add1 x))\n"
                        + "let @pub runF :Fn<I32;I32> = (=> |x| dep->::add1[x])");
        assertEquals(6, (int) call(qualified, "runS", "Fn<I32;I32>", 5));
        assertEquals(6, (int) call(qualified, "runF", "Fn<I32;I32>", 5));
    }

    @Test
    void parenthesizedDirectCallsAndSiblingCommasRetainCallSemantics() throws Throwable {
        String source = """
                let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))
                let @pub runParen :Fn<I32,I32;I32> = (=> |a b| (::add[a b]))
                let @pub runSibling :Fn<I32;I32> = (=> |a| (+ ::add[a, 1], ::add[2, a]))
                let @pub runSiblingS :Fn<I32;I32> = (=> |a| (+ (add a 1) (add 2 a)))
                """;
        CompiledArtifact artifact = compile(source);
        assertEquals(7, (int) call(artifact, "runParen", "Fn<I32,I32;I32>", 3, 4));
        assertEquals(9, (int) call(artifact, "runSibling", "Fn<I32;I32>", 3));
        assertEquals(9, (int) call(artifact, "runSiblingS", "Fn<I32;I32>", 3));
    }

    @Test
    void intrinsicCallsKeepOneCanonicalBoundaryForBothSpellings() throws Throwable {
        String source = """
                import std->io->{print println}
                let @pub runS :Fn<I32;Unit> = (=> |x| { (print "v=") (println "x") })
                let @pub runF :Fn<I32;Unit> = (=> |x| { ::print["v="], ::println["x"] })
                """;
        CompiledArtifact artifact = compile(source);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        io.mindspice.lyra.runtime.LoadOptions options = new io.mindspice.lyra.runtime.LoadOptions(
                new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        new java.io.ByteArrayInputStream(new byte[0]), output,
                        new java.io.ByteArrayOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        try (LoadedArtifact loaded = LyraRuntime.load(artifact, options)) {
            ModuleHandle module = loaded.instantiate();
            try {
                module.export("runS", "Fn<I32;Unit>").methodHandle().invokeWithArguments(5);
                module.export("runF", "Fn<I32;Unit>").methodHandle().invokeWithArguments(5);
            } finally {
                module.close();
            }
        }
        assertEquals("v=x\nv=x\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void producerProofClassifiesAuthenticatedNamedStorageRoutesWithoutShapeInference() {
        SessionCompileResult.Success localRoutes = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest("routes.lyra", """
                        import std->io->{println}
                        class Box { let @pub apply :Fn<I32;I32> = (=> |x| (+ x 1)) }
                        let @pub routes :Fn<Fn<I32;I32>,Box;I32> = (=> |parameter box| {
                          let local :Fn<I32;I32> = (=> |x| x)
                          let @mut mutable :Fn<I32;I32> = local
                          let captured :Fn<;I32> = (=> | | (parameter 1))
                          let shared :Fn<;I32> = (=> | | (mutable 1))
                          (println "routes")
                          (+ (local 1) (+ (parameter 1) (+ (captured) (+ (shared) (box:.apply 1))))) })
                        """, SessionSnapshot.empty())));
        Set<CallableStorageRouteProof.RouteKind> localKinds = routeKinds(localRoutes.typedIr());
        assertTrue(localKinds.containsAll(Set.of(
                CallableStorageRouteProof.RouteKind.LOCAL_BINDING,
                CallableStorageRouteProof.RouteKind.PARAMETER_ENTRY,
                CallableStorageRouteProof.RouteKind.CAPTURE_VALUE,
                CallableStorageRouteProof.RouteKind.SHARED_CELL,
                CallableStorageRouteProof.RouteKind.INTRINSIC,
                CallableStorageRouteProof.RouteKind.NOMINAL_MEMBER_GETTER)), localKinds.toString());
        CallableStorageRouteProof memberProof = IrTraversal.preOrder(localRoutes.typedIr()).stream()
                .filter(IrNode.CallableCall.class::isInstance)
                .map(IrNode.CallableCall.class::cast)
                .map(IrNode.CallableCall::storageRouteProof)
                .flatMap(Optional::stream)
                .filter(proof -> proof.route()
                        == CallableStorageRouteProof.RouteKind.NOMINAL_MEMBER_GETTER)
                .findFirst().orElseThrow();
        assertTrue(memberProof.receiverSite().isPresent());
        assertNotEquals(memberProof.targetSite(), memberProof.receiverSite().orElseThrow());
    }

    @Test
    void sFormAndDirectNameArityFailuresRetainTheirExactCallSpans() {
        String prefix = "let f :Fn<I32;I32> = (=> |x| x) let value :I32 = ";
        String sSource = prefix + "(f 1 2)";
        String fSource = prefix + "::f[1 2]";
        CompileResult.Failure sFailure = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.source("arity-s.lyra", sSource)));
        CompileResult.Failure fFailure = assertInstanceOf(CompileResult.Failure.class,
                LyraCompiler.compile(CompileRequest.source("arity-f.lyra", fSource)));
        assertEquals(sFailure.diagnostics().getFirst().code(), fFailure.diagnostics().getFirst().code());
        var sSpan = sFailure.diagnostics().getFirst().primarySpan();
        var fSpan = fFailure.diagnostics().getFirst().primarySpan();
        assertEquals("(f 1 2)", sSource.substring(sSpan.startOffset(), sSpan.endOffset()));
        assertEquals("::f[1 2]", fSource.substring(fSpan.startOffset(), fSpan.endOffset()));
    }

    // ------------------------------------------------------------------
    // Proven-route bytecode parity

    @Test
    void provenSFormAndDirectCallsShareNormalizedBytecodeShapeWithoutPerCallAuthentication() {
        CompiledArtifact sForm = compile(
                "let @pub fib :Fn<I32;I32> = (=> |n| (match n 0 -> 0 1 -> 1 _ -> "
                        + "(+ (fib (- n 1)) (fib (- n 2)))))");
        CompiledArtifact direct = compile(
                "let @pub fib :Fn<I32;I32> = (=> |n| (match n 0 -> 0 1 -> 1 _ -> "
                        + "(+ ::fib[(- n 1)], ::fib[(- n 2)])))");
        List<String> sFormShape = normalizedInvokeShape(sForm);
        List<String> directShape = normalizedInvokeShape(direct);
        assertEquals(directShape, sFormShape,
                "proven S-expression and direct-name calls must share one normalized call/control-flow shape");
        for (List<String> shape : List.of(sFormShape, directShape)) {
            assertFalse(shape.stream().anyMatch(token -> token.contains("LyraSignature.parse")),
                    "the proven path must not parse a signature per call");
            assertFalse(shape.stream().anyMatch(token -> token.contains("requireAuthenticatedForGeneratedInvocation")),
                    "the proven path must not run a per-call authentication block");
            assertFalse(shape.stream().anyMatch(token -> token.contains("LyraClosureAuthority.resolveSignature")),
                    "the proven path must not run a per-call producer signature-cache lookup");
        }
    }

    @Test
    void namedRouteMatrixHasMatchingControlFlowAndCallInstructions() {
        for (String template : List.of(
                "let @pub run :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| CALL)",
                "let @pub make :Fn<Fn<I32;I32>;Fn<I32;I32>> = (=> |f| (=> |x| CALL))",
                "let @pub @mut f :Fn<I32;I32> = (=> |x| (+ x 1)) "
                        + "let @pub run :Fn<I32;I32> = (=> |x| CALL)",
                "let @pub run :Fn<I32;I32> = (=> |x| { "
                        + "let @mut f :Fn<I32;I32> = (=> |n| (+ n 1)) "
                        + "let captured :Fn<I32;I32> = (=> |x| CALL) (captured x) })",
                "import std->io->{println as f}\n"
                        + "let @pub run :Fn<String;Unit> = (=> |x| CALL)")) {
            assertEquals(normalizedClosureInvokeShapes(compile(template.replace("CALL", "::f[x]"))),
                    normalizedClosureInvokeShapes(compile(template.replace("CALL", "(f x)"))), template);
        }
        String dependency = "let @pub f :Fn<I32;I32> = (=> |x| (+ x 1))";
        for (String[] pair : List.of(
                new String[]{"import dep->{f}", "(f x)", "::f[x]"},
                new String[]{"import dep", "(dep->:.f x)", "dep->::f[x]"})) {
            String prefix = pair[0] + "\nlet @pub run :Fn<I32;I32> = (=> |x| ";
            assertEquals(normalizedClosureInvokeShapes(compileTwo(dependency, prefix + pair[2] + ")")),
                    normalizedClosureInvokeShapes(compileTwo(dependency, prefix + pair[1] + ")")));
        }
    }

    @Test
    void provenMemberSFormAndDirectCallsShareReceiverSelectionAndInvokeShape() {
        String prefix = "class Box { "
                + "let @pub @mut apply :Fn<I32;I32> = (=> |x| (+ x 1)) "
                + "let @pub replace :Fn<I32;I32> = (=> |x| { self:.apply := (=> |y| (+ y 100)) x }) } ";
        CompiledArtifact sForm = compile(prefix
                + "let @pub run :Fn<Box,I32;I32> = (=> |box x| (box:.apply (box:.replace x)))");
        CompiledArtifact direct = compile(prefix
                + "let @pub run :Fn<Box,I32;I32> = (=> |box x| box::apply[(box:.replace x)])");
        assertEquals(normalizedClosureInvokeShapes(direct), normalizedClosureInvokeShapes(sForm),
                "proven member spellings must select the receiver/current slot once and invoke identically");
    }

    @Test
    void sFormSelfTailMatchesDirectSelfTailLoweringInMatchCondAndBranchResults() throws Throwable {
        List<String[]> fixtures = List.of(
                new String[]{"branchS", "(=> |n| ((<= n 0) -> 0 : (down (- n 1))))"},
                new String[]{"matchS", "(=> |n| (match n 0 -> 0 _ -> (down (- n 1))))"},
                new String[]{"condS", "(=> |n| (cond (<= n 0) -> 0 _ -> (down (- n 1))))"});
        for (String[] fixture : fixtures) {
            CompiledArtifact artifact = compile(
                    "let @pub down :Fn<I32;I32> = " + fixture[1]
                            + " let @pub probe :Fn<;I32> = (=> | | (down 400000))");
            assertEquals(0, (int) call(artifact, "probe", "Fn<;I32>"),
                    fixture[0] + " must keep constant-stack self-tail semantics");
            ClassModel closure = artifact.classes().entrySet().stream()
                    .filter(entry -> entry.getKey().contains(".$lyra$closure$"))
                    .map(entry -> ClassFile.of().parse(entry.getValue()))
                    .findFirst().orElseThrow();
            MethodModel invoke = closure.methods().stream()
                    .filter(method -> method.methodName().stringValue().equals("invoke"))
                    .filter(method -> method.methodType().stringValue().equals("(I)I"))
                    .findFirst().orElseThrow();
            List<Instruction> code = instructions((CodeAttribute) invoke.code().orElseThrow());
            assertTrue(code.stream().anyMatch(instruction ->
                            instruction.opcode() == Opcode.GOTO || instruction.opcode() == Opcode.GOTO_W),
                    fixture[0] + " must contain a constant-stack loop back edge");
            assertTrue(code.stream().filter(InvokeInstruction.class::isInstance)
                            .map(InvokeInstruction.class::cast)
                            .noneMatch(call -> call.name().stringValue().equals("invoke")
                                    && call.owner().name().stringValue().contains("$lyra$closure$")),
                    fixture[0] + " must not recursively invoke its generated closure");
        }
    }

    @Test
    void mutableSelfTailChecksSlotIdentityForBothSpellingsAndFallsBackToInvocation() throws Throwable {
        String source = """
                let @pub @mut f :Fn<I32;I32> = (=> |n| ((<= n 0) -> 0 : (f (- n 1))))
                let @pub @mut g :Fn<I32;I32> = (=> |n| ((<= n 0) -> 0 : ::g[(- n 1)]))
                let @pub runS :Fn<;I32> = (=> | | { let saved :Fn<I32;I32> = f f := (=> |n| 42) (saved 5) })
                let @pub runF :Fn<;I32> = (=> | | { let saved :Fn<I32;I32> = g g := (=> |n| 42), ::saved[5] })
                let @pub deepS :Fn<;I32> = (=> | | (f 400000))
                let @pub deepF :Fn<;I32> = (=> | | ::g[400000])
                """;
        CompiledArtifact artifact = compile(source);
        assertEquals(42, (int) call(artifact, "runS", "Fn<;I32>"));
        assertEquals(42, (int) call(artifact, "runF", "Fn<;I32>"));
        assertEquals(0, (int) call(artifact, "deepS", "Fn<;I32>"));
        assertEquals(0, (int) call(artifact, "deepF", "Fn<;I32>"));
    }

    @Test
    void mutableSelfSelectedBeforeArgumentRebindingStillExecutesTheSelectedClosure() throws Throwable {
        for (String call : List.of("(f { f := (=> |v| 99) 0 })",
                "::f[{ f := (=> |v| 99) 0 }]")) {
            CompiledArtifact artifact = compile("let @pub @mut f :Fn<I32;I32> = "
                    + "(=> |n| ((== n 0) -> 7 : " + call + "))");
            try (LoadedArtifact loaded = LyraRuntime.load(artifact);
                 ModuleHandle module = loaded.instantiate()) {
                var function = module.export("f", "Fn<I32;I32>").methodHandle();
                assertEquals(7, (int) function.invokeWithArguments(1),
                        "argument rebinding must not retarget the selected self-tail call");
                assertEquals(99, (int) function.invokeWithArguments(1),
                        "a subsequent call must select the replacement");
            }
        }
    }

    @Test
    void aliasedAndCapturedCellSelfReferencesKeepIdentitySafeguards() throws Throwable {
        String source = """
                let @pub make :Fn<;Fn<I32;I32>> = (=> | |
                  { let @mut local :Fn<I32;I32> = (=> |n| ((<= n 0) -> 0 : (local (- n 1))))
                    local })
                let @pub deep :Fn<;I32> = (=> | | { let g :Fn<I32;I32> = (make) (g 400000) })
                let @pub rebind :Fn<;I32> = (=> | | {
                  let @mut local :Fn<I32;I32> = (=> |n| ((<= n 0) -> 0 : (local (- n 1))))
                  let saved :Fn<I32;I32> = local
                  local := (=> |n| 77)
                  (saved 5) })
                let @pub alias :Fn<;I32> = (=> | |
                  { let @mut local :Fn<I32;I32> = (=> |n| ((<= n 0) -> 0 : (local (- n 1))))
                    let other :Fn<I32;I32> = local
                    (other 5) })
                """;
        CompiledArtifact artifact = compile(source);
        assertEquals(0, (int) call(artifact, "deep", "Fn<;I32>"));
        assertEquals(77, (int) call(artifact, "rebind", "Fn<;I32>"));
        assertEquals(0, (int) call(artifact, "alias", "Fn<;I32>"));
    }

    // ------------------------------------------------------------------
    // LYR-STACK translation across the complete call boundary

    @Test
    void nonTailSFormRecursionOverflowProducesOrderedSourceMappedLyrStackFrames() throws Throwable {
        String source = "let dive :Fn<I32;I32> = (=> |n| "
                + "((<= n 0) -> 0 : (+ 1 (dive (- n 1))))) "
                + "let @pub outer :Fn<I32;I32> = (=> |n| (+ 0 (dive n)))";
        CompiledArtifact artifact = compile(source);
        LyraRuntimeException failure = null;
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                try {
                    module.export("outer", "Fn<I32;I32>").methodHandle().invokeWithArguments(1_000_000);
                } catch (LyraRuntimeException expected) {
                    failure = expected;
                }
            } finally {
                module.close();
            }
        }
        assertTrue(failure != null, "non-tail overflow must produce a structured failure");
        assertEquals("LYR-STACK", failure.code());
        assertFalse(failure.frames().isEmpty(), "LYR-STACK must carry Lyra source frames");
        for (var frame : failure.frames()) {
            assertEquals("callable-parity.lyra", frame.span().sourceId().value());
            assertTrue(frame.span().startOffset() >= 0
                            && frame.span().startOffset() <= frame.span().endOffset()
                            && frame.span().endOffset() <= source.length(),
                    "every LYR-STACK frame must carry an in-bounds source span");
        }
        List<String> names = failure.frames().stream().map(frame -> frame.functionName()).toList();
        int lastDive = names.lastIndexOf("dive");
        int outer = names.indexOf("outer");
        int module = names.indexOf("<module>");
        assertTrue(lastDive >= 0 && outer > lastDive && module > outer,
                "frames must be ordered from the overflowing callee through caller and facade: " + names);
        assertEquals(1, names.stream().filter("outer"::equals).count(),
                "the nonrecursive outer call boundary must add one frame");
        assertEquals(1, names.stream().filter("<module>"::equals).count(),
                "the generated facade boundary must add one frame");
    }

    @Test
    void stackOverflowRegionCoversTargetSelectionAndArgumentEvaluation() {
        CompiledArtifact artifact = compile(
                "let choose :Fn<Fn<I32;I32>;Fn<I32;I32>> = (=> |f| f) "
                        + "let deep :Fn<I32;I32> = (=> |n| ((<= n 0) -> 0 : (+ 1 (deep (- n 1))))) "
                        + "let @pub apply :Fn<I32;I32> = (=> |n| ((choose deep) (deep n)))");
        // The computed outer call must carry a StackOverflowError
        // catch whose range starts at or before the target selection/argument
        // evaluation instructions and ends after the invocation, and the catch
        // type must be exactly StackOverflowError so other VirtualMachineError
        // instances escape unchanged.
        for (var entry : artifact.classes().entrySet()) {
            if (!entry.getKey().contains(".$lyra$closure$")) continue;
            ClassModel model = ClassFile.of().parse(entry.getValue());
            for (MethodModel method : model.methods()) {
                if (!method.methodName().stringValue().equals("invoke")
                        || !method.methodType().stringValue().equals("(I)I")) continue;
                CodeAttribute code = (CodeAttribute) method.code().orElseThrow();
                List<Instruction> instructions = instructions(code);
                java.util.List<java.lang.classfile.CodeElement> elements = code.elementList();
                List<Integer> invokeOffsets = new ArrayList<>();
                for (int index = 0; index < instructions.size(); index++) {
                    Instruction instruction = instructions.get(index);
                    if (instruction instanceof InvokeInstruction invocation
                            && invocation.name().stringValue().equals("invoke")
                            && invocation.owner().name().stringValue().contains("$lyra$fn$")) {
                        invokeOffsets.add(index);
                    }
                }
                if (invokeOffsets.size() < 3) continue;
                long broadStackRegions = code.exceptionHandlers().stream()
                        .filter(handler -> handler.catchType().map(type -> type.name().stringValue()
                                .equals("java/lang/StackOverflowError")).orElse(false))
                        .filter(handler -> {
                            int start = instructionIndexAt(elements, handler.tryStart());
                            int end = instructionIndexAt(elements, handler.tryEnd());
                            return invokeOffsets.stream().filter(index -> start <= index && index < end).count() >= 3;
                        }).count();
                assertEquals(1, broadStackRegions,
                        "the outer computed call must have one StackOverflowError region spanning target, argument, and invoke");
                assertTrue(code.exceptionHandlers().stream().noneMatch(handler -> handler.catchType()
                                .map(type -> Set.of("java/lang/VirtualMachineError", "java/lang/Error")
                                        .contains(type.name().stringValue())).orElse(false)),
                        "generated calls must not catch broader VM error classes");
                return;
            }
        }
        throw new AssertionError("computed target/argument fixture did not produce the expected invoke shape");
    }

    @Test
    void overflowDuringArgumentEvaluationAddsTheOuterCallBoundaryOnce() throws Throwable {
        String source = "let choose :Fn<Fn<I32;I32>;Fn<I32;I32>> = (=> |f| f) "
                + "let deep :Fn<I32;I32> = (=> |n| "
                + "((<= n 0) -> 0 : (+ 1 (deep (- n 1))))) "
                + "let @pub apply :Fn<I32;I32> = (=> |n| ((choose deep) (deep n)))";
        CompiledArtifact artifact = compile(source);
        LyraRuntimeException failure;
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                failure = assertThrows(LyraRuntimeException.class,
                        () -> module.export("apply", "Fn<I32;I32>").methodHandle()
                                .invokeWithArguments(1_000_000));
            } finally {
                module.close();
            }
        }
        assertEquals("LYR-STACK", failure.code());
        int outerStart = source.indexOf("((choose deep) (deep n))");
        int innerStart = source.indexOf("(deep n)", outerStart);
        long outerCount = failure.frames().stream().filter(frame ->
                frame.span().startOffset() == outerStart
                        && frame.span().endOffset()
                        == outerStart + "((choose deep) (deep n))".length()).count();
        long innerCount = failure.frames().stream().filter(frame ->
                frame.span().startOffset() == innerStart
                        && frame.span().endOffset() == innerStart + "(deep n)".length()).count();
        assertEquals(1, innerCount, "the overflowing argument call must add one frame");
        assertEquals(1, outerCount, "the enclosing target/argument boundary must add one frame");
        int innerFrame = java.util.stream.IntStream.range(0, failure.frames().size())
                .filter(index -> failure.frames().get(index).span().startOffset() == innerStart)
                .findFirst().orElseThrow();
        int outerFrame = java.util.stream.IntStream.range(0, failure.frames().size())
                .filter(index -> failure.frames().get(index).span().startOffset() == outerStart)
                .findFirst().orElseThrow();
        assertTrue(innerFrame < outerFrame,
                "overflow frames must remain ordered from argument callee to enclosing call");
    }

    @Test
    void targetSelectionOverflowAddsEverySourceBoundaryExactlyOnce() throws Throwable {
        String source = """
                import std->io->{println}
                let choose :Fn<;Fn<I32;I32>> = (=> || { (println "target") (=> |x| x) })
                let @pub run :Fn<;I32> = (=> || ((choose) 7))
                """;
        StackOverflowError overflow = new StackOverflowError("injected at intrinsic target selection");
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override
            public void write(int value) { throw overflow; }
        };
        var options = new io.mindspice.lyra.runtime.LoadOptions(
                new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        new java.io.ByteArrayInputStream(new byte[0]), output, output,
                        java.nio.charset.StandardCharsets.UTF_8));
        try (LoadedArtifact loaded = LyraRuntime.load(compile(source), options);
             ModuleHandle module = loaded.instantiate()) {
            LyraRuntimeException failure = assertThrows(LyraRuntimeException.class,
                    () -> module.export("run", "Fn<;I32>").methodHandle().invokeWithArguments());
            assertEquals("LYR-STACK", failure.code());
            List<String> frames = failure.frames().stream().map(frame ->
                    source.substring(frame.span().startOffset(), frame.span().endOffset())).toList();
            int previous = -1;
            for (String boundary : List.of("(println \"target\")", "(choose)", "((choose) 7)")) {
                assertEquals(1, frames.stream().filter(boundary::equals).count(), frames.toString());
                int position = frames.indexOf(boundary);
                assertTrue(position > previous, frames.toString());
                previous = position;
            }
        }
    }

    @Test
    void nonStackVirtualMachineErrorsEscapeIntrinsicBoundariesUnchanged() throws Throwable {
        CompiledArtifact artifact = compile(
                "import std->io->{println} let @pub run :Fn<;Unit> = (=> | | (println \"x\"))");
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override
            public void write(int value) {
                throw new ProbeVmError();
            }
        };
        var options = new io.mindspice.lyra.runtime.LoadOptions(
                new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        new java.io.ByteArrayInputStream(new byte[0]), output, output,
                        java.nio.charset.StandardCharsets.UTF_8));
        try (LoadedArtifact loaded = LyraRuntime.load(artifact, options)) {
            ModuleHandle module = loaded.instantiate();
            try {
                assertThrows(ProbeVmError.class,
                        () -> module.export("run", "Fn<;Unit>").methodHandle().invokeWithArguments());
            } finally {
                module.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Nilable-Fn coalesce planning/checkcast ordering

    @Test
    void nilableFnCoalesceCompilesVerifiesAndExecutesEveryBranch() throws Throwable {
        CompiledArtifact artifact = compile("""
                let @pub runNil :Fn<I32;I32> = (=> |x| {
                  let @nil f :Fn<I32;I32> = #NIL
                  let g :Fn<I32;I32> = (f : (=> |v| v))
                  (g x) })
                let @pub runValue :Fn<I32;I32> = (=> |x| {
                  let @nil f :Fn<I32;I32> = (=> |v| (+ v 10))
                  let g :Fn<I32;I32> = (f : (=> |v| v))
                  (g x) })
                let @pub runTarget :Fn<I32;I32> = (=> |x| {
                  let @nil f :Fn<I32;I32> = #NIL
                  ((f : (=> |v| v)) x) })
                """);
        assertEquals(5, (int) call(artifact, "runNil", "Fn<I32;I32>", 5));
        assertEquals(15, (int) call(artifact, "runValue", "Fn<I32;I32>", 5));
        assertEquals(5, (int) call(artifact, "runTarget", "Fn<I32;I32>", 5));
    }

    @Test
    void invalidNilableFnCoalesceSourceIsAnOrdinaryDiagnostic() {
        // A nilable callable used without narrowing and a mismatched coalesce
        // type must remain structured diagnostics, never a compiler invariant.
        CompileResult narrowing = LyraCompiler.compile(CompileRequest.source(
                "callable-parity-bad.lyra",
                "let @pub run :Fn<I32;I32> = (=> |x| "
                        + "{ let @nil f :Fn<I32;I32> = #NIL (f x) })"));
        assertInstanceOf(CompileResult.Failure.class, narrowing);
        CompileResult mismatched = LyraCompiler.compile(CompileRequest.source(
                "callable-parity-bad2.lyra",
                "let @pub run :Fn<;I32> = (=> | | "
                        + "{ let @nil f :Fn<I32;I32> = #NIL let g :I32 = (f : (=> |v| v)) g })"));
        assertInstanceOf(CompileResult.Failure.class, mismatched);
    }

    // ------------------------------------------------------------------
    // Receiver/method parity

    @Test
    void receiverMethodsSelectTheCurrentSlotOnceBeforeArgumentsInBothSpellings() throws Throwable {
        String source = """
                class Box {
                    let @pub @mut next :Fn<I32;I32> = (=> |x| (+ x 1))
                    let @pub replace :Fn<I32;I32> = (=> |x| { self:.next := (=> |y| (+ y 100)) x })
                }
                let @pub make :Fn<;Box> = (=> | | :Box[])
                let @pub runS :Fn<Box,I32;I32> = (=> |b x| (b:.next (b:.replace x)))
                let @pub runF :Fn<Box,I32;I32> = (=> |b x| b::next[(b:.replace x)])
                let @pub savedS :Fn<Box,I32;I32> = (=> |b x| {
                  let saved :Fn<I32;I32> = b:.next
                  (b:.replace x)
                  (saved x) })
                let @pub savedF :Fn<Box,I32;I32> = (=> |b x| {
                  let saved :Fn<I32;I32> = b:.next
                  b::replace[x], ::saved[x] })
                let @pub secondS :Fn<Box,I32;I32> = (=> |b x| { let box :Box = b (box:.next x) })
                let @pub secondF :Fn<Box,I32;I32> = (=> |b x| { let box :Box = b box::next[x] })
                """;
        CompiledArtifact artifact = compile(source);
        String signature = null;
        // Resolve the nominal type name from the published export metadata.
        signature = artifact.metadata().exports().stream()
                .filter(export -> export.name().equals("runS"))
                .findFirst().orElseThrow().signature().canonicalSpelling();
        String makeSignature = artifact.metadata().exports().stream()
                .filter(export -> export.name().equals("make"))
                .findFirst().orElseThrow().signature().canonicalSpelling();
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                // Selection before arguments: replacement during argument
                // evaluation cannot retarget the already-selected callable.
                // Each spelling receives a fresh box because the call itself
                // replaces the member slot.
                assertEquals(4, (int) module.export("runS", signature).methodHandle()
                        .invokeWithArguments(module.export("make", makeSignature).methodHandle().invokeWithArguments(), 3));
                assertEquals(4, (int) module.export("runF", signature).methodHandle()
                        .invokeWithArguments(module.export("make", makeSignature).methodHandle().invokeWithArguments(), 3));
                // The replacement is now the current slot for new calls.
                Object replaced = module.export("make", makeSignature).methodHandle().invokeWithArguments();
                module.export("runS", signature).methodHandle().invokeWithArguments(replaced, 6);
                assertEquals(106, (int) module.export("secondS", signature).methodHandle()
                        .invokeWithArguments(replaced, 6));
                assertEquals(106, (int) module.export("secondF", signature).methodHandle()
                        .invokeWithArguments(replaced, 6));
                // Saved references retain their original selection.
                Object savedBoxS = module.export("make", makeSignature).methodHandle().invokeWithArguments();
                assertEquals(8, (int) module.export("savedS", signature).methodHandle()
                        .invokeWithArguments(savedBoxS, 7));
                Object savedBoxF = module.export("make", makeSignature).methodHandle().invokeWithArguments();
                assertEquals(8, (int) module.export("savedF", signature).methodHandle()
                        .invokeWithArguments(savedBoxF, 7));
            } finally {
                module.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Dynamic targets keep full per-call authentication

    @Test
    void opaqueAggregateAndLambdaTargetsKeepFullPerCallAuthentication() throws Throwable {
        CompiledArtifact artifact = compile(
                "let @pub invokeFirst :Fn<Array<Fn<;I32>>;I32> = (=> |values| (values[0])) "
                        + "let @pub callInline :Fn<I32;I32> = (=> |x| ((=> :I32 |v :I32| v) x)) "
                        + "let @pub value :Fn<;I32> = (=> | | 7)");
        try (FacadeHandle facade = FacadeHandle.open(artifact)) {
            assertEquals(7, (int) facade.call("callInline", "Fn<I32;I32>", 7));
            Class<?> functionInterface = facade.rawValue("value").getClass().getInterfaces()[0];
            Object forged = forgedCallable(functionInterface, 99);
            Object rejected = java.lang.reflect.Array.newInstance(functionInterface, 1);
            java.lang.reflect.Array.set(rejected, 0, forged);
            java.lang.reflect.InvocationTargetException wrapped = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> facade.call("invokeFirst", "Fn<Array<Fn<;I32>>;I32>", rejected));
            assertEquals("LYR-LINK", ((LyraRuntimeException) wrapped.getCause()).code());
        }
    }

    @Test
    void certifiedRetainedNamesUseExactExternalBindingRouteForBothSpellings() {
        SessionCompileResult.Success producer = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "retained-named-producer.lyra",
                        "import std->io\nlet make :Fn<;I32> = (=> || 7)",
                        SessionSnapshot.empty())));
        SessionCompileResult.Success consumer = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "retained-named-consumer.lyra", """
                                let @pub runS :Fn<;I32> = (=> || (make))
                                let @pub runF :Fn<;I32> = (=> || ::make[])
                                """, producer.stagedSnapshot())));
        List<CallableStorageRouteProof> proofs = IrTraversal.preOrder(consumer.typedIr()).stream()
                .filter(node -> node instanceof IrNode.CallableCall
                        || node instanceof IrNode.DirectCall)
                .map(node -> node instanceof IrNode.CallableCall callable
                        ? callable.storageRouteProof()
                        : ((IrNode.DirectCall) node).storageRouteProof())
                .flatMap(Optional::stream)
                .toList();
        assertEquals(2, proofs.size());
        assertTrue(proofs.stream().allMatch(proof -> proof.route()
                == CallableStorageRouteProof.RouteKind.EXTERNAL_BINDING));
        assertEquals(proofs.get(0).declarationId(), proofs.get(1).declarationId());
        assertEquals(proofs.get(0).signature(), proofs.get(1).signature());
        assertTrue(proofs.stream().allMatch(proof -> proof.moduleId().isEmpty()
                && proof.exportId().isEmpty()
                && proof.producerId().isEmpty()
                && proof.generationId().isEmpty()));
        List<String> shape = normalizedClosureInvokeShapes(consumer.artifact());
        assertFalse(shape.stream().anyMatch(token ->
                        token.contains("requireAuthenticatedForGeneratedInvocation")),
                "the certified external accessor must not repeat invocation authentication");
        assertFalse(shape.stream().anyMatch(token -> token.contains("$lyra$signature$")),
                "the proven named path needs no dynamic signature field");
        assertFalse(shape.stream().anyMatch(token -> token.contains("LyraSignature.parse")),
                "the proven named path must not parse a signature per call");
    }

    @Test
    void retainedRawIntrinsicCallableDoesNotAcquireAnExternalBindingProof() {
        SessionCompileResult.Success producer = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "retained-raw-producer.lyra", """
                                import std->io
                                let raw :Fn<String;Unit> = io->:.println
                                """, SessionSnapshot.empty())));
        SessionCompileResult.Success consumer = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "retained-raw-consumer.lyra",
                        "let @pub run :Fn<String;Unit> = (=> |value| (raw value))",
                        producer.stagedSnapshot())));
        IrNode.CallableCall call = IrTraversal.preOrder(consumer.typedIr()).stream()
                .filter(IrNode.CallableCall.class::isInstance)
                .map(IrNode.CallableCall.class::cast)
                .findFirst().orElseThrow();
        assertTrue(call.storageRouteProof().isEmpty(),
                "a retained raw intrinsic closure must remain dynamically authenticated");
        List<String> shape = normalizedClosureInvokeShapes(consumer.artifact());
        assertTrue(shape.stream().anyMatch(token ->
                        token.contains("requireAuthenticatedForGeneratedInvocation")));
        assertTrue(shape.stream().anyMatch(token -> token.contains("$lyra$signature$")));
    }

    @Test
    void writeBoundariesAuthenticateCallableStorageBeforeProvenRoutesReadIt() throws Throwable {
        CompiledArtifact artifact = compile("""
                let @pub accept :Fn<Array<Fn<;I32>>;I32> = (=> |values| { let g :Fn<;I32> = values[0] 42 })
                let @pub acceptRebind :Fn<Array<Fn<;I32>>;I32> = (=> |values| {
                  let @mut g :Fn<;I32> = (=> | | 7)
                  g := values[0]
                  42 })
                let @pub acceptNil :Fn<;I32> = (=> | | { let @nil g :Fn<;I32> = #NIL 42 })
                let @pub value :Fn<;I32> = (=> | | 7)
                """);
        try (FacadeHandle facade = FacadeHandle.open(artifact)) {
            assertEquals(42, (int) facade.call("acceptNil", "Fn<;I32>"));
            Class<?> functionInterface = facade.rawValue("value").getClass().getInterfaces()[0];
            Object forged = forgedCallable(functionInterface, 99);
            Object rejected = java.lang.reflect.Array.newInstance(functionInterface, 1);
            java.lang.reflect.Array.set(rejected, 0, forged);
            for (String export : List.of("accept", "acceptRebind")) {
                java.lang.reflect.InvocationTargetException wrapped = assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> facade.call(export, "Fn<Array<Fn<;I32>>;I32>", rejected));
                assertEquals("LYR-LINK", ((LyraRuntimeException) wrapped.getCause()).code(),
                        export + " must reject a forged callable at its write boundary");
            }
        }
    }

    @Test
    void callableWritesAndDynamicCallsRejectValuesReplacedAfterEntryAuthentication() throws Throwable {
        // Trusted Java can mutate a live array. Inject only after generated entry checks,
        // so a facade rejection cannot accidentally stand in for the tested boundary.
        CompiledArtifact artifact = compile("""
                import std->io->{println}
                let @pub value :Fn<;I32> = (=> || 7)
                let @pub initialize :Fn<Array<Fn<;I32>>;I32> = (=> |values| {
                  (println "replace") let f :Fn<;I32> = values[0] 42 })
                let @pub rebind :Fn<Array<Fn<;I32>>;I32> = (=> |values| {
                  let @mut f :Fn<;I32> = value (println "replace") f := values[0] 42 })
                let @pub dynamic :Fn<Array<Fn<;I32>>;I32> = (=> |values| {
                  (println "replace") (values[0]) })
                """);
        Object[] replacement = new Object[2];
        int[] writes = {0};
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override
            public void write(int value) {
                writes[0]++;
                java.lang.reflect.Array.set(replacement[0], 0, replacement[1]);
            }
        };
        var options = new io.mindspice.lyra.runtime.LoadOptions(
                new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        new java.io.ByteArrayInputStream(new byte[0]), output, output,
                        java.nio.charset.StandardCharsets.UTF_8));
        try (LoadedArtifact loaded = LyraRuntime.load(artifact, options);
             ModuleHandle module = loaded.instantiate()) {
            Object valid = module.export("value", "Fn<;I32>").functionValue();
            Class<?> functionInterface = valid.getClass().getInterfaces()[0];
            replacement[0] = java.lang.reflect.Array.newInstance(functionInterface, 1);
            int[] unauthorizedInvocations = {0};
            replacement[1] = Proxy.newProxyInstance(functionInterface.getClassLoader(),
                    new Class<?>[]{functionInterface}, (proxy, method, arguments) -> {
                        unauthorizedInvocations[0]++;
                        return 99;
                    });
            for (String export : List.of("initialize", "rebind", "dynamic")) {
                java.lang.reflect.Array.set(replacement[0], 0, valid);
                writes[0] = 0;
                LyraRuntimeException failure = assertThrows(LyraRuntimeException.class,
                        () -> module.export(export, "Fn<Array<Fn<;I32>>;I32>")
                                .methodHandle().invokeWithArguments(replacement[0]));
                assertTrue(writes[0] > 0, "the replacement must occur after function entry");
                assertEquals("LYR-LINK", failure.code(), export);
                assertEquals(0, unauthorizedInvocations[0], "foreign SAM must never execute");
            }
        }
    }

    private static Object forgedCallable(Class<?> functionInterface, int result) {
        return Proxy.newProxyInstance(functionInterface.getClassLoader(),
                new Class<?>[]{functionInterface},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> true;
                    case "hashCode" -> 1;
                    case "toString" -> "forged";
                    default -> result;
                });
    }

    /** Defines one artifact's generated classes and opens one facade instance. */
    private static final class FacadeHandle implements AutoCloseable {
        private final GeneratedClassLoader loader;
        private final Object instance;
        private final Class<?> facade;

        private FacadeHandle(GeneratedClassLoader loader, Class<?> facade, Object instance) {
            this.loader = loader;
            this.facade = facade;
            this.instance = instance;
        }

        static FacadeHandle open(CompiledArtifact artifact) throws Throwable {
            GeneratedClassLoader loader = new GeneratedClassLoader(artifact.classes());
            String facadeName = artifact.classes().keySet().stream()
                    .filter(name -> name.contains(".$lyra$facade$"))
                    .findFirst().orElseThrow();
            Class<?> facade = Class.forName(facadeName, true, loader);
            Object instance = facade.getMethod("$lyra$create").invoke(null);
            return new FacadeHandle(loader, facade, instance);
        }

        Object rawValue(String export) throws Throwable {
            return facade.getMethod("value$" + export).invoke(instance);
        }

        Object call(String export, String signature, Object... arguments) throws Throwable {
            Method method = java.util.Arrays.stream(facade.getMethods())
                    .filter(candidate -> candidate.getName().equals(export))
                    .filter(candidate -> candidate.getParameterCount() == arguments.length)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException(
                            export + "/" + arguments.length));
            return method.invoke(instance, arguments);
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            try {
                facade.getMethod("close").invoke(instance);
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            loader.clear();
            if (failure != null) {
                if (failure instanceof Exception exception) throw exception;
                throw new RuntimeException(failure);
            }
        }
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private Map<String, byte[]> classes;

        private GeneratedClassLoader(Map<String, byte[]> classes) {
            super(CallableCallParityTest.class.getClassLoader());
            this.classes = Map.copyOf(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        private void clear() {
            classes = Map.of();
        }
    }

    // ------------------------------------------------------------------
    // Per-instance expected-signature fields

    @Test
    void dynamicSignaturesLiveInDeterministicPerInstanceFieldsInitializedFromProducerAuthority() {
        CompiledArtifact artifact = compile(
                "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x)) "
                        + "let @pub id :Fn<I32;I32> = (=> |x| x)");
        boolean found = false;
        for (var entry : artifact.classes().entrySet()) {
            if (!entry.getKey().contains(".$lyra$closure$")
                    && !entry.getKey().contains(".$lyra$state$")) continue;
            ClassModel model = ClassFile.of().parse(entry.getValue());
            boolean hasSignatureFields = model.fields().stream().anyMatch(field ->
                    field.fieldName().stringValue().startsWith("$lyra$signature$"));
            for (var field : model.fields()) {
                if (!field.fieldName().stringValue().startsWith("$lyra$signature$")) continue;
                assertFalse(field.flags().has(AccessFlag.STATIC),
                        "expected-signature fields must be per-instance, never global");
                assertTrue(field.flags().has(AccessFlag.PRIVATE)
                                && field.flags().has(AccessFlag.FINAL),
                        "expected-signature fields must be immutable implementation details");
                assertEquals("Lio/mindspice/lyra/runtime/LyraSignature;",
                        field.fieldType().stringValue());
            }
            if (!hasSignatureFields) continue;
            found = true;
            for (MethodModel method : model.methods()) {
                if (!method.methodName().stringValue().equals("<init>")) continue;
                CodeAttribute code = (CodeAttribute) method.code().orElseThrow();
                boolean resolves = instructions(code).stream()
                        .filter(InvokeInstruction.class::isInstance)
                        .map(InvokeInstruction.class::cast)
                        .anyMatch(invocation -> invocation.owner().name().stringValue()
                                .equals("io/mindspice/lyra/runtime/LyraClosureAuthority")
                                && invocation.name().stringValue().equals("resolveSignature"));
                assertTrue(resolves,
                        "instance signature fields must be initialized from the producer authority");
            }
        }
        assertTrue(found, "the dynamic fixture must plan at least one instance signature field");
    }

    @Test
    void independentInstancesResolveTheirOwnSignatureFieldsAndStayIsolated() throws Throwable {
        CompiledArtifact artifact = compile(
                "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x)) "
                        + "let @pub id :Fn<I32;I32> = (=> |x| x)");
        try (FacadeHandle first = FacadeHandle.open(artifact);
             FacadeHandle second = FacadeHandle.open(artifact)) {
            Object firstId = first.rawValue("id");
            Object secondId = second.rawValue("id");
            assertEquals(5, (int) first.call("apply", "Fn<Fn<I32;I32>,I32;I32>", firstId, 5));
            assertEquals(9, (int) second.call("apply", "Fn<Fn<I32;I32>,I32;I32>", secondId, 9));
            assertEquals(5, (int) first.call("apply", "Fn<Fn<I32;I32>,I32;I32>", firstId, 5));
        }
    }

    // ------------------------------------------------------------------
    // Nominal getter/adapter signature metadata

    @Test
    void nominalCallableGettersAndAdaptersResolveSignatureMetadataOncePerObjectInsteadOfPerRead() {
        String body = """
                class Box {
                    let @pub @mut apply :Fn<I32;I32> = (=> |x| (+ x 1))
                    let @mut hidden :Fn<I32;I32> = (=> |x| (+ x 2))
                    let @pub hiddenCall :Fn<I32;I32> = (=> |x| (self:.hidden x))
                    let @pub echo :Fn<U32;U32> = (=> |x| x)
                    let @pub fs :Array<Fn<I32;I32>> = Array<Fn<I32;I32>>[]
                }
                let @pub run :Fn<Box,I32;I32> = (=> |box x| (box:.apply x))
                let @pub hiddenRun :Fn<Box,I32;I32> = (=> |box x| (box:.hiddenCall x))
                """;
        assertNominalSignatureMetadata(compile(body), false);
        SessionCompileResult.Success session = assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "nominal-metadata.lyra", body, SessionSnapshot.empty())));
        assertNominalSignatureMetadata(session.artifact(), true);
    }

    private static void assertNominalSignatureMetadata(CompiledArtifact artifact, boolean session) {
        ClassModel nominal = artifact.classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$nominal$"))
                .map(entry -> ClassFile.of().parse(entry.getValue()))
                .findFirst().orElseThrow(() -> new AssertionError("generated nominal class is missing"));
        java.util.List<java.lang.classfile.FieldModel> signatureFields = nominal.fields().stream()
                .filter(field -> field.fieldName().stringValue().startsWith("$lyra$signature$"))
                .toList();
        assertEquals(2, signatureFields.size(),
                "one metadata field per distinct callable member signature, deduplicated");
        for (java.lang.classfile.FieldModel field : signatureFields) {
            assertTrue(field.flags().has(AccessFlag.PRIVATE) && field.flags().has(AccessFlag.FINAL)
                            && !field.flags().has(AccessFlag.STATIC),
                    "expected-signature metadata must be per-instance, never global");
            assertEquals("Lio/mindspice/lyra/runtime/LyraSignature;", field.fieldType().stringValue());
            assertEquals(16, field.fieldName().stringValue()
                    .substring("$lyra$signature$".length()).length());
        }
        MethodModel constructor = nominal.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("<init>"))
                .findFirst().orElseThrow();
        java.util.List<InvokeInstruction> constructorCalls = instructions(
                (CodeAttribute) constructor.code().orElseThrow()).stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast).toList();
        assertEquals(2, constructorCalls.stream().filter(call ->
                        call.owner().name().stringValue()
                                .equals("io/mindspice/lyra/runtime/LyraNominalObject")
                                && call.name().stringValue().equals("resolveExpectedSignature"))
                        .count(),
                "the constructor must resolve each distinct signature exactly once");
        assertFalse(constructorCalls.stream().anyMatch(call ->
                        call.owner().name().stringValue()
                                .equals("io/mindspice/lyra/runtime/LyraClosureAuthority")
                                && call.name().stringValue().equals("resolveSignature")),
                "the constructor must resolve through the nominal object's producer authority");
        for (MethodModel method : nominal.methods()) {
            String name = method.methodName().stringValue();
            boolean accessBoundary = name.startsWith("$lyra$get$")
                    || name.startsWith("$lyra$public$get$")
                    || name.startsWith("$lyra$set$")
                    || name.startsWith("$lyra$public$set$")
                    || name.startsWith("$lyra$initialize$")
                    || name.startsWith("$lyra$initialization$get$");
            if (!accessBoundary) continue;
            CodeAttribute code = (CodeAttribute) method.code().orElseThrow();
            java.util.List<InvokeInstruction> calls = instructions(code).stream()
                    .filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).toList();
            assertFalse(calls.stream().anyMatch(call ->
                            call.owner().name().stringValue()
                                    .equals("io/mindspice/lyra/runtime/LyraClosureAuthority")
                                    && call.name().stringValue().equals("resolveSignature")),
                    name + " must not resolve its signature per read/store");
            assertFalse(calls.stream().anyMatch(call ->
                            call.owner().name().stringValue()
                                    .equals("io/mindspice/lyra/runtime/LyraSignature")
                                    && call.name().stringValue().equals("parse")),
                    name + " must not parse its signature per read/store");
            if (name.startsWith("$lyra$get$") || name.startsWith("$lyra$public$get$")) {
                boolean readsMetadata = instructions(code).stream()
                        .filter(FieldInstruction.class::isInstance)
                        .map(FieldInstruction.class::cast)
                        .anyMatch(read -> read.opcode() == Opcode.GETFIELD
                                && read.name().stringValue().startsWith("$lyra$signature$"));
                assertTrue(readsMetadata, name + " must read its pre-resolved signature metadata");
                assertTrue(calls.stream().anyMatch(call ->
                                call.owner().name().stringValue()
                                        .equals("io/mindspice/lyra/runtime/LyraNominalObject")
                                        && (call.name().stringValue().equals("checkGeneratedRead")
                                        || call.name().stringValue().equals("checkPublicRead"))),
                        name + " keeps its exact field-route check");
                assertTrue(calls.stream().anyMatch(call ->
                                call.owner().name().stringValue()
                                        .equals("io/mindspice/lyra/runtime/LyraClosureSupport")),
                        name + " keeps its full candidate authentication");
            }
        }
        if (!session) return;
        java.util.List<ClassModel> delegates = artifact.classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$delegate$"))
                .map(entry -> ClassFile.of().parse(entry.getValue()))
                .toList();
        assertFalse(delegates.isEmpty(), "session emission must plan callable member delegates");
        for (ClassModel delegate : delegates) {
            for (MethodModel method : delegate.methods()) {
                java.util.List<InvokeInstruction> calls = instructions(
                        (CodeAttribute) method.code().orElseThrow()).stream()
                        .filter(InvokeInstruction.class::isInstance)
                        .map(InvokeInstruction.class::cast).toList();
                assertFalse(calls.stream().anyMatch(call ->
                                call.owner().name().stringValue()
                                        .equals("io/mindspice/lyra/runtime/LyraClosureAuthority")
                                        && call.name().stringValue().equals("resolveSignature")),
                        "delegate adapters must not resolve signatures per invocation");
                assertFalse(calls.stream().anyMatch(call ->
                                call.owner().name().stringValue()
                                        .equals("io/mindspice/lyra/runtime/LyraSignature")
                                        && call.name().stringValue().equals("parse")),
                        "delegate adapters must not parse signatures per invocation");
            }
        }
        MethodModel generatedGet = nominal.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("$lyra$get$0"))
                .findFirst().orElseThrow();
        java.util.List<InvokeInstruction> getCalls = instructions(
                (CodeAttribute) generatedGet.code().orElseThrow()).stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast).toList();
        assertTrue(getCalls.stream().anyMatch(call ->
                        call.name().stringValue().equals("issueCallableMemberRoute")),
                "the delegated getter keeps its occurrence-route issue boundary");
        assertTrue(getCalls.stream().anyMatch(call ->
                        call.name().stringValue().equals("checkGeneratedRead")),
                "the delegated getter keeps its exact field-route check");
    }

    // ------------------------------------------------------------------
    // Helpers

    private static Set<CallableStorageRouteProof.RouteKind> routeKinds(TypedIr ir) {
        java.util.EnumSet<CallableStorageRouteProof.RouteKind> result =
                java.util.EnumSet.noneOf(CallableStorageRouteProof.RouteKind.class);
        IrTraversal.walk(ir, node -> {
            if (node instanceof IrNode.CallableCall call) {
                call.storageRouteProof().map(CallableStorageRouteProof::route).ifPresent(result::add);
            }
        });
        return Set.copyOf(result);
    }

    private static final class ProbeVmError extends VirtualMachineError {
    }

    private static int instructionIndexAt(
            java.util.List<java.lang.classfile.CodeElement> elements,
            java.lang.classfile.Label label) {
        int count = 0;
        for (var element : elements) {
            if (element instanceof LabelTarget target && target.label().equals(label)) return count;
            if (element instanceof Instruction) count++;
        }
        throw new AssertionError("unbound bytecode label: " + label);
    }

    private static List<String> normalizedClosureInvokeShapes(CompiledArtifact artifact) {
        ArrayList<String> shapes = new ArrayList<>();
        artifact.classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$closure$"))
                .map(entry -> ClassFile.of().parse(entry.getValue()))
                .forEach(model -> model.methods().stream()
                        .filter(method -> method.methodName().stringValue().equals("invoke"))
                        .forEach(method -> shapes.add(normalize(method.methodType().stringValue()) + " "
                                + normalizedInstructions((CodeAttribute) method.code().orElseThrow()))));
        shapes.sort(String::compareTo);
        return List.copyOf(shapes);
    }

    private static List<String> normalizedInvokeShape(CompiledArtifact artifact) {
        ClassModel closure = artifact.classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$closure$"))
                .map(entry -> ClassFile.of().parse(entry.getValue()))
                .findFirst().orElseThrow();
        MethodModel invoke = closure.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("invoke"))
                .filter(method -> method.methodType().stringValue().equals("(I)I"))
                .findFirst().orElseThrow();
        return normalizedInstructions((CodeAttribute) invoke.code().orElseThrow());
    }

    private static List<String> normalizedInstructions(CodeAttribute code) {
        List<String> shape = new ArrayList<>();
        for (Instruction instruction : instructions(code)) {
            if (instruction instanceof InvokeInstruction invocation) {
                shape.add(invocation.opcode().name() + " "
                        + normalize(invocation.owner().name().stringValue()) + "."
                        + invocation.name().stringValue() + " "
                        + normalize(invocation.type().stringValue()));
            } else if (instruction instanceof FieldInstruction field) {
                shape.add(field.opcode().name() + " "
                        + normalize(field.owner().name().stringValue()) + "."
                        + field.name().stringValue() + " "
                        + normalize(field.type().stringValue()));
            } else if (instruction instanceof NewObjectInstruction allocation) {
                shape.add("NEW " + normalize(allocation.className().name().stringValue()));
            } else if (instruction instanceof BranchInstruction branch) {
                shape.add(branch.opcode().name() + " -> "
                        + instructionIndexAt(code.elementList(), branch.target()));
            } else if (instruction instanceof LookupSwitchInstruction lookup) {
                shape.add("LOOKUPSWITCH default=" + instructionIndexAt(code.elementList(), lookup.defaultTarget())
                        + " " + lookup.cases().stream().map(value -> value.caseValue() + "->"
                        + instructionIndexAt(code.elementList(), value.target())).toList());
            } else if (instruction instanceof TableSwitchInstruction table) {
                shape.add("TABLESWITCH " + table.lowValue() + ".." + table.highValue()
                        + " default=" + instructionIndexAt(code.elementList(), table.defaultTarget())
                        + " " + table.cases().stream().map(value -> value.caseValue() + "->"
                        + instructionIndexAt(code.elementList(), value.target())).toList());
            } else if (instruction instanceof LoadInstruction load) {
                shape.add(load.opcode().name() + " " + load.slot());
            } else if (instruction instanceof StoreInstruction store) {
                shape.add(store.opcode().name() + " " + store.slot());
            } else if (instruction instanceof IncrementInstruction increment) {
                shape.add(increment.opcode().name() + " " + increment.slot() + " " + increment.constant());
            } else {
                shape.add(instruction.opcode().name());
            }
        }
        for (var handler : code.exceptionHandlers()) {
            shape.add("CATCH " + handler.catchType().map(type -> type.name().stringValue()).orElse("all")
                    + " " + instructionIndexAt(code.elementList(), handler.tryStart())
                    + ".." + instructionIndexAt(code.elementList(), handler.tryEnd())
                    + " -> " + instructionIndexAt(code.elementList(), handler.handler()));
        }
        return shape;
    }

    private static String normalize(String reference) {
        return reference.replaceAll("\\$lyra\\$(closure|state|fn|tuple|cell|facade|nominal)\\$[0-9a-f]{16,64}(?:\\$[0-9]+)?",
                "\\$lyra\\$$1\\$H").replaceAll("[0-9]+", "N");
    }

    private static List<Instruction> instructions(CodeAttribute code) {
        return code.elementStream()
                .filter(Instruction.class::isInstance)
                .map(Instruction.class::cast)
                .toList();
    }
}
