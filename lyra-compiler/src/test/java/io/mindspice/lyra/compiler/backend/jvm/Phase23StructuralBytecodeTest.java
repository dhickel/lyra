package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.ExportMetadata;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.Attributes;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LineNumberTableAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural Phase 23 gate for the production Class-File API emitter.
 *
 * <p>The test deliberately inspects the generated closure bodies rather than
 * only testing source-level results.  Primitive steady-state paths must keep
 * primitive descriptors and must not introduce wrapper allocations or boxing
 * calls.  Required runtime support calls and reference values remain allowed.</p>
 */
public final class Phase23StructuralBytecodeTest {
    private static final Set<String> BOXING_TYPES = Set.of(
            "java/lang/Boolean",
            "java/lang/Byte",
            "java/lang/Character",
            "java/lang/Short",
            "java/lang/Integer",
            "java/lang/Long",
            "java/lang/Float",
            "java/lang/Double");

    @Test
    public void primitiveSteadyStatePathsRetainPrimitiveAbiWithoutBoxing() {
        List<Fixture> fixtures = List.of(
                new Fixture("add", "Fn<I32,I32;I32>", "(II)I", Set.of(Opcode.IADD, Opcode.IRETURN),
                        "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))"),
                new Fixture("narrow", "Fn<U8;U8>", "(B)B", Set.of(Opcode.IRETURN),
                        "let @pub narrow :Fn<U8;U8> = (=> |value| value)"),
                new Fixture("wide", "Fn<F64,F64;F64>", "(DD)D", Set.of(Opcode.DADD, Opcode.DRETURN),
                        "let @pub wide :Fn<F64,F64;F64> = (=> |left right| (+ left right))"),
                new Fixture("branch", "Fn<I32;I32>", "(I)I", Set.of(Opcode.IF_ICMPGT, Opcode.IRETURN),
                        "let @pub branch :Fn<I32;I32> = (=> |value| ((<= value 0) -> 1 : 2))"),
                new Fixture("valueMatch", "Fn<I32;I32>", "(I)I", Set.of(Opcode.LOOKUPSWITCH, Opcode.IRETURN),
                        "let @pub valueMatch :Fn<I32;I32> = (=> |value| { let selected :I32 = "
                                + "(match value ?? 1 -> 11 ?? 2 -> 22 ?? _ -> 33) selected })"),
                new Fixture("conditionalMatch", "Fn<I32;I32>", "(I)I", Set.of(Opcode.IRETURN),
                        "let @pub conditionalMatch :Fn<I32;I32> = (=> |value| "
                                + "::match[_ ?? (< value 0) -> 1 ?? value -> 2 ?? _ -> 3])"),
                new Fixture("guardedMatch", "Fn<I32,I32;I32>", "(II)I", Set.of(Opcode.IRETURN),
                        "let @pub guardedMatch :Fn<I32,I32;I32> = (=> |value pattern| "
                                + "(match value ?? pattern when (> value 0) -> 1 ?? _ -> 2))"),
                new Fixture("unsignedMatch", "Fn<U32;I32>", "(I)I", Set.of(Opcode.IRETURN),
                        "let @pub unsignedMatch :Fn<U32;I32> = (=> |value| "
                                + "(match value ?? 4294967295I64 -> 1 ?? _ -> 2))"),
                new Fixture("arrayAt", "Fn<Array<I32>,I32;I32>", "([II)I", Set.of(Opcode.IALOAD, Opcode.IRETURN),
                        "let @pub arrayAt :Fn<Array<I32>,I32;I32> = (=> |values index| values[index])"),
                new Fixture("stringLength", "Fn<String;I32>", "(Ljava/lang/String;)I", Set.of(Opcode.INVOKEVIRTUAL, Opcode.IRETURN),
                        "let @pub stringLength :Fn<String;I32> = (=> |value| value:.length)"));

        for (Fixture fixture : fixtures) {
            CompiledArtifact artifact = compile(fixture.source());
            ExportMetadata export = artifact.metadata().exports().stream()
                    .filter(value -> value.name().equals(fixture.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing export " + fixture.name()));
            assertEquals(fixture.descriptor(), export.jvmDescriptor(),
                    fixture.name() + " must publish its specialized primitive descriptor");
            assertFalse(containsBoxingDescriptor(export.jvmDescriptor()),
                    fixture.name() + " descriptor must not publish a wrapper type");

            ClassModel closure = soleClosure(artifact);
            MethodModel invoke = closure.methods().stream()
                    .filter(method -> method.methodName().stringValue().equals("invoke"))
                    .filter(method -> method.methodType().stringValue().equals(fixture.descriptor()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            fixture.name() + " closure has no exact " + fixture.descriptor() + " invoke method"));
            assertTrue(invoke.flags().has(AccessFlag.PUBLIC),
                    fixture.name() + " closure invoke method must remain public");
            assertFalse(containsBoxingDescriptor(invoke.methodType().stringValue()),
                    fixture.name() + " invoke descriptor must not contain wrapper types");

            CodeAttribute code = (CodeAttribute) invoke.code().orElseThrow(
                    () -> new AssertionError(fixture.name() + " closure invoke has no Code attribute"));
            Set<Opcode> opcodes = instructions(code).stream()
                    .map(Instruction::opcode)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(opcodes.containsAll(fixture.requiredOpcodes()),
                    fixture.name() + " must contain its primitive/control instructions: "
                            + fixture.requiredOpcodes());
            assertNoPerCallSignatureParsing(artifact, fixture);
            for (Instruction instruction : instructions(code)) {
                if (instruction instanceof NewObjectInstruction allocation) {
                    String owner = allocation.className().name().stringValue();
                    assertFalse(BOXING_TYPES.contains(owner),
                            fixture.name() + " allocates wrapper " + owner);
                }
                if (instruction instanceof InvokeInstruction invocation) {
                    String owner = invocation.owner().name().stringValue();
                    assertFalse(isBoxingInvocation(invocation),
                            fixture.name() + " invokes a boxing or unboxing helper " + owner + "."
                                    + invocation.name().stringValue());
                    assertFalse(containsBoxingDescriptor(invocation.type().stringValue()),
                            fixture.name() + " invokes a wrapper-shaped descriptor "
                                    + invocation.type().stringValue());
                }
            }
        }
    }

    @Test
    public void directSelfTailCallHasNoGeneratedRecursiveInvokeAndKeepsConstantStack() throws Throwable {
        String source = "let @pub tailSum :Fn<I32,I32;I32> = "
                + "(=> |n acc| ((<= n 0) -> acc : ::tailSum[(- n 1) (+ acc n)]))";
        CompiledArtifact artifact = compile(source);
        ClassModel closure = soleClosure(artifact);
        MethodModel invoke = closure.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("invoke"))
                .filter(method -> method.methodType().stringValue().equals("(II)I"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tail closure has no (II)I invoke method"));
        CodeAttribute code = (CodeAttribute) invoke.code().orElseThrow();

        Set<Opcode> opcodes = instructions(code).stream()
                .map(Instruction::opcode)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(opcodes.contains(Opcode.GOTO) || opcodes.contains(Opcode.GOTO_W),
                "direct self-tail lowering must contain a loop back edge");

        List<InvokeInstruction> invokes = instructions(code).stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .toList();
        assertTrue(invokes.stream().noneMatch(value ->
                        value.name().stringValue().equals("invoke")
                                && value.owner().name().stringValue().startsWith("lyra/generated/$lyra$fn$")),
                "direct self-tail lowering must not call the generated function interface recursively");
        assertTrue(invokes.stream().noneMatch(value ->
                        value.name().stringValue().equals("invoke")
                                && value.owner().name().stringValue().contains("$lyra$closure$")),
                "direct self-tail lowering must not call a generated closure recursively");

        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                MethodHandle handle = module.export("tailSum", "Fn<I32,I32;I32>").methodHandle();
                int result = (int) handle.invokeExact(50_000, 0);
                assertEquals(1_250_025_000, result,
                        "the lowered tail loop must preserve the accumulator result");
            } finally {
                module.close();
            }
        }
    }

    @Test
    public void integralMatchUsesLookupSwitchAndRetainsArmSourceLines() throws Throwable {
        String source = """
                let @pub choose :Fn<I32;I32> = (=> |value| {
                  let selected :I32 = (match value
                    ?? 1 -> 11
                    ?? 2 -> 22
                    ?? 3 -> 33
                    ?? _ -> 44)
                  selected })
                """;
        CompiledArtifact artifact = compile(source);
        ClassModel closure = soleClosure(artifact);
        MethodModel invoke = closure.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("invoke"))
                .filter(method -> method.methodType().stringValue().equals("(I)I"))
                .findFirst().orElseThrow();
        CodeAttribute code = (CodeAttribute) invoke.code().orElseThrow();
        assertTrue(instructions(code).stream().anyMatch(
                        instruction -> instruction.opcode() == Opcode.LOOKUPSWITCH),
                "constant integral match arms must lower to one JVM lookup switch");
        LineNumberTableAttribute lines = code.findAttribute(Attributes.lineNumberTable()).orElseThrow();
        Set<Integer> lineNumbers = lines.lineNumbers().stream()
                .map(java.lang.classfile.attribute.LineNumberInfo::lineNumber)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(lineNumbers.containsAll(Set.of(3, 4, 5, 6)),
                "match arm result source lines must survive direct lowering");

        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                MethodHandle handle = module.export("choose", "Fn<I32;I32>").methodHandle();
                assertEquals(11, (int) handle.invokeExact(1));
                assertEquals(22, (int) handle.invokeExact(2));
                assertEquals(44, (int) handle.invokeExact(9));
            } finally {
                module.close();
            }
        }
    }

    @Test
    public void unsignedNarrowMatchSwitchUsesMathematicalValues() throws Throwable {
        CompiledArtifact artifact = compile("""
                let @pub choose :Fn<U8;I32> = (=> |value| {
                  let selected :I32 = (match value
                    ?? 0U8 -> 10
                    ?? 255U8 -> 20
                    ?? _ -> 30)
                  selected })
                """);
        MethodModel invoke = soleClosure(artifact).methods().stream()
                .filter(method -> method.methodName().stringValue().equals("invoke"))
                .filter(method -> method.methodType().stringValue().equals("(B)I"))
                .findFirst().orElseThrow();
        assertTrue(instructions((CodeAttribute) invoke.code().orElseThrow()).stream()
                .anyMatch(instruction -> instruction.opcode() == Opcode.LOOKUPSWITCH));
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                MethodHandle handle = module.export("choose", "Fn<U8;I32>").methodHandle();
                assertEquals(20, (int) handle.invokeExact((byte) -1));
            } finally {
                module.close();
            }
        }
    }

    @Test
    public void matchResultsPreserveDirectSelfTailLowering() throws Throwable {
        CompiledArtifact artifact = compile(
                "let @pub countdown :Fn<I32;I32> = (=> |n| "
                        + "(match n ?? 0 -> 7 ?? _ -> ::countdown[(- n 1)]))");
        ClassModel closure = soleClosure(artifact);
        MethodModel invoke = closure.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("invoke"))
                .filter(method -> method.methodType().stringValue().equals("(I)I"))
                .findFirst().orElseThrow();
        List<Instruction> code = instructions((CodeAttribute) invoke.code().orElseThrow());
        assertTrue(code.stream().anyMatch(instruction ->
                        instruction.opcode() == Opcode.GOTO || instruction.opcode() == Opcode.GOTO_W),
                "tail match fallback must jump back to the closure loop");
        assertTrue(code.stream().filter(InvokeInstruction.class::isInstance)
                        .map(InvokeInstruction.class::cast)
                        .noneMatch(call -> call.name().stringValue().equals("invoke")
                                && call.owner().name().stringValue().contains("$lyra$closure$")),
                "tail match result must not recursively invoke its generated closure");
        try (LoadedArtifact loaded = LyraRuntime.load(artifact)) {
            ModuleHandle module = loaded.instantiate();
            try {
                MethodHandle handle = module.export("countdown", "Fn<I32;I32>").methodHandle();
                assertEquals(7, (int) handle.invokeExact(50_000));
            } finally {
                module.close();
            }
        }
    }

    private static CompiledArtifact compile(String body) {
        CompileResult result = LyraCompiler.compile(
                CompileRequest.source("phase23-structural.lyra", body));
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("Phase 23 structural fixture failed to compile: " + result);
        }
        return success.artifact();
    }

    private static ClassModel soleClosure(CompiledArtifact artifact) {
        List<ClassModel> closures = artifact.classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$closure$"))
                .map(entry -> ClassFile.of().parse(entry.getValue()))
                .toList();
        assertEquals(1, closures.size(), "the one-lambda structural fixture must emit one closure class");
        ClassModel closure = closures.getFirst();
        assertNotNull(closure, "the closure class model must be present");
        return closure;
    }

    private static void assertNoPerCallSignatureParsing(CompiledArtifact artifact, Fixture fixture) {
        ClassModel facade = artifact.classes().entrySet().stream()
                .filter(entry -> entry.getKey().contains(".$lyra$facade$"))
                .map(entry -> ClassFile.of().parse(entry.getValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("generated facade is missing"));
        MethodModel method = facade.methods().stream()
                .filter(value -> value.methodName().stringValue().equals(fixture.name()))
                .filter(value -> value.methodType().stringValue().equals(fixture.descriptor()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        fixture.name() + " facade method has no exact " + fixture.descriptor() + " descriptor"));
        CodeAttribute code = (CodeAttribute) method.code().orElseThrow(
                () -> new AssertionError(fixture.name() + " facade method has no Code attribute"));
        for (Instruction instruction : instructions(code)) {
            if (instruction instanceof InvokeInstruction invocation) {
                String owner = invocation.owner().name().stringValue();
                String name = invocation.name().stringValue();
                assertFalse(owner.equals("io/mindspice/lyra/runtime/LyraSignature")
                                && name.equals("parse"),
                        fixture.name() + " must not parse its signature on every facade call");
                assertFalse(owner.equals("io/mindspice/lyra/runtime/LyraClosureSupport")
                                && name.equals("requireAuthenticated"),
                        fixture.name() + " must not re-authenticate its stored closure on every facade call");
            }
        }
    }

    private static List<Instruction> instructions(CodeAttribute code) {
        return code.elementStream()
                .filter(Instruction.class::isInstance)
                .map(Instruction.class::cast)
                .toList();
    }

    private static boolean containsBoxingDescriptor(String descriptor) {
        return BOXING_TYPES.stream().anyMatch(type -> descriptor.contains("L" + type + ";"));
    }

    private static boolean isBoxingInvocation(InvokeInstruction invocation) {
        String owner = invocation.owner().name().stringValue();
        if (!BOXING_TYPES.contains(owner)) {
            return false;
        }
        String name = invocation.name().stringValue();
        return name.equals("valueOf") || name.equals("<init>") || name.endsWith("Value");
    }

    private record Fixture(String name, String signature, String descriptor,
                           Set<Opcode> requiredOpcodes, String source) {
        private Fixture {
            assertTrue(!name.isBlank(), "fixture name must not be blank");
            assertTrue(!signature.isBlank(), "fixture signature must not be blank");
            requiredOpcodes = Set.copyOf(requiredOpcodes);
        }
    }
}
