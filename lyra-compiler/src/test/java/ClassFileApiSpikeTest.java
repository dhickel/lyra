import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.ir.IrCheckKind;
import io.mindspice.lyra.compiler.ir.IrConstantValue;
import io.mindspice.lyra.compiler.ir.IrModule;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.ir.IrValidator;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LineNumberInfo;
import java.lang.classfile.attribute.LineNumberTableAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain 10's private, test-only Class-File API spike.
 *
 * <p>This is intentionally not a compiler backend: it accepts one final-form
 * {@link TypedIr} fixture and emits exactly one scalar method.  The fixture is
 * the checked form {@code Fn<I32;I32>} for {@code (+ value 1)}.  The observed
 * Java 25 API constraints are kept here with the evidence: the class version
 * must be selected explicitly with {@code ClassBuilder.withVersion}, source
 * and line metadata are separate class/code elements, and
 * {@code ClassFile.verify(byte[])} validates without defining a class.  The
 * JVM definition boundary is therefore tested separately in the isolated
 * loader and the {@code -Xverify:all} subprocess below.</p>
 */
public final class ClassFileApiSpikeTest {
    private static final String SOURCE_FILE = "domain10-scalar.lyra";
    private static final String SOURCE =
            "let increment :Fn<I32;I32> =\n"
                    + "    (=> |value| (+ value 1))\n";
    private static final String GENERATED_CLASS_NAME =
            "io.mindspice.lyra.spike.GeneratedScalar";
    private static final String METHOD_NAME = "increment";
    private static final int SOURCE_LINE = 2;
    private static final MethodTypeDesc I32_TO_I32 =
            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);

    @Test
    public void testFinalFormIrIsTheBoundedScalarFixture() {
        Fixture fixture = fixture();
        FunctionIr function = inspectFinalForm(fixture);

        assertEquals("Fn<I32;I32>", function.signature().canonicalSpelling(),
                "the spike fixture must retain the Lyra function contract");
        assertEquals(I32_TO_I32.descriptorString(), descriptor(function.signature()),
                "the Lyra I32 function must specialize to the primitive JVM descriptor");
        assertEquals(SOURCE_LINE, function.sourceLine(),
                "the emitted line must come from the IR span and source snapshot");
    }

    @Test
    public void testEmissionIsDeterministicAndCarriesJava25Metadata() {
        Fixture fixture = fixture();
        GeneratedClass first = emitFromTypedIr(fixture);
        GeneratedClass second = emitFromTypedIr(fixture);

        assertArrayEquals(first.bytes(), second.bytes(),
                "repeated emission of the same final-form IR must be byte-identical");
        assertEquals(I32_TO_I32.descriptorString(), first.methodType().descriptorString(),
                "I32 -> I32 must use (I)I");
        assertEquals(List.of(ConstantDescs.CD_int), first.methodType().parameterList(),
                "the only parameter descriptor must be primitive int");
        assertEquals(ConstantDescs.CD_int, first.methodType().returnType(),
                "the return descriptor must be primitive int");
        assertFalse(first.methodType().descriptorString().contains("Object"),
                "the scalar ABI must not use Object or generic boxing");
        assertFalse(first.previewRequired(),
                "this non-preview fixture must not claim preview artifact metadata");

        ClassModel model = parse(first);
        assertEquals(ClassFile.JAVA_25_VERSION, model.majorVersion(),
                "the spike class must target Java 25");
        assertEquals(0, model.minorVersion(),
                "the spike intentionally emits the ordinary, non-preview minor version");
        assertFalse(model.minorVersion() == ClassFile.PREVIEW_MINOR_VERSION,
                "the generated class must not have the preview minor version");
        assertEquals(GENERATED_CLASS_NAME.replace('.', '/'),
                model.thisClass().name().stringValue(), "deterministic generated class name");
        assertTrue(model.flags().has(AccessFlag.PUBLIC), "generated class is public");
        assertTrue(model.flags().has(AccessFlag.SUPER), "generated class has ACC_SUPER");

        SourceFileAttribute sourceFile = model.findAttribute(Attributes.sourceFile()).orElseThrow(
                () -> new AssertionError("SourceFile attribute is missing"));
        assertEquals(SOURCE_FILE, sourceFile.sourceFile().stringValue(),
                "source metadata must retain the stable Lyra source label");

        MethodModel method = soleMethod(model);
        assertEquals(METHOD_NAME, method.methodName().stringValue(), "generated method name");
        assertEquals(I32_TO_I32.descriptorString(), method.methodType().stringValue(),
                "method metadata must retain the exact primitive descriptor");
        assertEquals(I32_TO_I32, method.methodTypeSymbol(),
                "Class-File API method symbol must match the specialized descriptor");
        assertTrue(method.flags().has(AccessFlag.PUBLIC), "generated method is public");
        assertTrue(method.flags().has(AccessFlag.STATIC), "generated method is static");

        CodeAttribute code = codeAttribute(method);
        LineNumberTableAttribute lineTable = code.findAttribute(Attributes.lineNumberTable())
                .orElseThrow(() -> new AssertionError("LineNumberTable attribute is missing"));
        assertEquals(1, lineTable.lineNumbers().size(), "one source line entry is expected");
        LineNumberInfo line = lineTable.lineNumbers().getFirst();
        assertEquals(0, line.startPc(), "the source line starts at the first instruction");
        assertEquals(SOURCE_LINE, line.lineNumber(), "source line metadata is exact");

        List<Instruction> instructions = instructions(code);
        assertEquals(List.of(Opcode.ILOAD_0, Opcode.ICONST_1, Opcode.IADD, Opcode.IRETURN),
                instructions.stream().map(Instruction::opcode).toList(),
                "the selected disassembly shape must be load, constant, add, return");
        assertArrayEquals(new byte[] {0x1a, 0x04, 0x60, (byte) 0xac}, code.codeArray(),
                "the scalar body must contain only the expected primitive bytecodes");
        assertEquals(4, code.codeLength(), "the bounded scalar body has four bytecodes");
        assertEquals(1, code.maxLocals(), "one int parameter occupies one local slot");
        assertEquals(2, code.maxStack(), "the addition reaches two int stack values");

        assertTrue(instructions.get(0) instanceof LoadInstruction,
                "the first instruction must be a typed local load");
        LoadInstruction load = (LoadInstruction) instructions.get(0);
        assertEquals(TypeKind.INT, load.typeKind(), "I32 maps to an int local");
        assertEquals(0, load.slot(), "the function parameter is in primitive slot zero");
        assertTrue(instructions.get(1) instanceof ConstantInstruction,
                "the second instruction must be a constant instruction");
        assertEquals(TypeKind.INT, ((ConstantInstruction) instructions.get(1)).typeKind(),
                "the literal one is loaded as an int");
        assertTrue(instructions.get(2) instanceof OperatorInstruction,
                "the third instruction must be a typed operator");
        assertEquals(TypeKind.INT, ((OperatorInstruction) instructions.get(2)).typeKind(),
                "the addition is an int operation");
        assertTrue(instructions.get(3) instanceof ReturnInstruction,
                "the final instruction must be a typed return");
        assertEquals(TypeKind.INT, ((ReturnInstruction) instructions.get(3)).typeKind(),
                "the return slot is primitive int");
        assertTrue(instructions.stream().noneMatch(value -> value.opcode().name().startsWith("INVOKE")),
                "the scalar path must not box through a helper invocation");
    }

    @Test
    public void testLoadsInvokesAndPassesExternalVerification() throws Exception {
        Fixture fixture = fixture();
        GeneratedClass generated = emitFromTypedIr(fixture);

        assertEquals(List.of(), ClassFile.of().verify(generated.bytes()),
                "the Java 25 Class-File API verifier must accept the bytes");
        Class<?> generatedClass = new IsolatedClassLoader().defineGenerated(generated.bytes());
        assertEquals(GENERATED_CLASS_NAME, generatedClass.getName(),
                "the isolated loader must define the generated class");
        assertTrue(generatedClass.getClassLoader() instanceof IsolatedClassLoader,
                "the generated class must not be loaded by the test/application loader");
        Method method = generatedClass.getDeclaredMethod(METHOD_NAME, int.class);
        assertEquals(int.class, method.getReturnType(), "reflection must expose primitive int return");
        assertEquals(42, method.invoke(null, 41), "generated I32 function invocation result");

        verifyInExternalJvm(generated);
    }

    private static Fixture fixture() {
        ModuleId moduleId = ModuleId.path(SOURCE_FILE);
        SourceSnapshot snapshot = phaseValue(SourceSnapshot.capture(
                moduleId.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + SOURCE_FILE)),
                SOURCE.getBytes(StandardCharsets.UTF_8)));
        LexedSource lexed = phaseValue(Lexer.lex(snapshot));
        GrammarProgram grammar = phaseValue(GrammarMatcher.match(lexed));
        SyntaxProgram syntax = phaseValue(Parser.parse(lexed, grammar));
        ModuleGraph.Node node = new ModuleGraph.Node(
                moduleId,
                Optional.empty(),
                snapshot,
                syntax,
                ModuleRevision.compute(snapshot));
        ModuleGraph graph = new ModuleGraph(moduleId, List.of(node), List.of(), java.util.Map.of());
        ResolvedSemanticGraph resolved = phaseValue(SemanticResolver.resolve(graph));
        TypedSemanticGraph typed = phaseValue(io.mindspice.lyra.compiler.semantic.TypeChecker.check(resolved));
        TypedIr ir = phaseValue(TypedIrBuilder.lower(typed));
        assertTrue(IrValidator.isValid(ir), "the existing closed typed IR validator must accept the fixture");
        return new Fixture(snapshot, ir);
    }

    private static FunctionIr inspectFinalForm(Fixture fixture) {
        IrModule module = fixture.ir().rootModule();
        assertEquals(1, module.body().forms().size(), "the fixture has one top-level function declaration");
        assertEquals(PrimitiveType.UNIT, module.body().type(), "top-level declaration sequence is Unit");

        assertTrue(module.body().forms().getFirst() instanceof IrNode.Declaration,
                "the final form must contain a declaration node");
        IrNode.Declaration declaration = (IrNode.Declaration) module.body().forms().getFirst();
        String name = fixture.ir().semanticGraph()
                .declaration(declaration.declarationId().orElseThrow())
                .orElseThrow(() -> new AssertionError("declaration identity has no typed name"))
                .name();
        assertEquals(METHOD_NAME, name, "the generated method name must come from the IR declaration");
        FunctionType signature = declaration.contract()
                .map(contract -> contract.valueType())
                .filter(FunctionType.class::isInstance)
                .map(FunctionType.class::cast)
                .orElseThrow(() -> new AssertionError("declaration is not Fn<I32;I32>"));
        assertEquals(1, signature.arity(), "the scalar fixture has one parameter");
        assertEquals(PrimitiveType.I32, signature.parameterType(0), "parameter type is I32");
        assertEquals(PrimitiveType.I32, signature.returnType(), "return type is I32");

        assertTrue(declaration.initializer() instanceof IrNode.Lambda,
                "the function declaration initializer must be a lambda");
        IrNode.Lambda lambda = (IrNode.Lambda) declaration.initializer();
        assertEquals(signature.signature(), lambda.signature().orElseThrow(),
                "lambda signature is explicit in final IR");
        assertEquals(signature, lambda.type(), "lambda value type matches its contract");
        assertTrue(lambda.captures().isEmpty(), "the bounded function has no captures");
        assertTrue(lambda.body() instanceof IrNode.RuntimeCheck,
                "checked scalar arithmetic remains represented in final IR");
        IrNode.RuntimeCheck runtimeCheck = (IrNode.RuntimeCheck) lambda.body();
        assertEquals(IrCheckKind.ARITHMETIC, runtimeCheck.checkKind(), "the body check is arithmetic");
        assertEquals("LYR-ARITH", runtimeCheck.failureCode(), "the body retains its failure category");
        assertTrue(runtimeCheck.operand() instanceof IrNode.Operator,
                "the checked body wraps the scalar operator");
        IrNode.Operator operator = (IrNode.Operator) runtimeCheck.operand();
        assertEquals(TokenKind.PLUS, operator.operator(), "the scalar operator is plus");
        assertEquals(PrimitiveType.I32, operator.type(), "the operator result is I32");
        assertEquals(2, operator.operands().size(), "the plus operator has two operands");
        assertTrue(operator.operands().getFirst() instanceof IrNode.Reference,
                "the first operand is the function parameter reference");
        IrNode.Reference parameter = (IrNode.Reference) operator.operands().getFirst();
        assertEquals(PrimitiveType.I32, parameter.type(), "the parameter reference remains I32");
        DeclarationId parameterId = parameter.targetDeclaration().orElseThrow(
                () -> new AssertionError("parameter reference has no declaration identity"));
        assertEquals(ReferenceKind.VALUE, parameter.referenceKind(), "parameter is a value reference");
        assertTrue(operator.operands().get(1) instanceof IrNode.Constant,
                "the second operand is a scalar constant");
        IrNode.Constant constant = (IrNode.Constant) operator.operands().get(1);
        assertEquals(PrimitiveType.I32, constant.type(), "the literal is contextually typed as I32");
        assertTrue(constant.value() instanceof IrConstantValue.IntegerValue,
                "the scalar constant retains exact integer data");
        assertEquals(java.math.BigInteger.ONE,
                ((IrConstantValue.IntegerValue) constant.value()).exactValue().integerValue(),
                "the final IR constant is exactly one");

        int sourceLine = fixture.source().positionAt(lambda.body().span().startOffset()).line();
        return new FunctionIr(name, signature, lambda.body(), parameterId, sourceLine);
    }

    private static GeneratedClass emitFromTypedIr(Fixture fixture) {
        FunctionIr function = inspectFinalForm(fixture);
        MethodTypeDesc methodType = methodType(function.signature());
        ClassDesc generatedClass = ClassDesc.of(GENERATED_CLASS_NAME);
        byte[] bytes = ClassFile.of().build(generatedClass, classBuilder -> {
            classBuilder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            classBuilder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            classBuilder.with(SourceFileAttribute.of(SOURCE_FILE));
            classBuilder.withMethodBody(
                    function.name(),
                    methodType,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> {
                        code.lineNumber(function.sourceLine());
                        emitExpression(code, function.body(), function.parameterId());
                        code.ireturn();
                    });
        });
        return new GeneratedClass(bytes, methodType, false);
    }

    private static void emitExpression(CodeBuilder code, IrNode node, DeclarationId parameterId) {
        if (node instanceof IrNode.RuntimeCheck check) {
            assertEquals(IrCheckKind.ARITHMETIC, check.checkKind(), "only arithmetic check is supported by the spike");
            emitExpression(code, check.operand(), parameterId);
            return;
        }
        if (node instanceof IrNode.Operator operator) {
            assertEquals(TokenKind.PLUS, operator.operator(), "only scalar plus is supported by the spike");
            assertEquals(2, operator.operands().size(), "scalar plus arity");
            emitExpression(code, operator.operands().getFirst(), parameterId);
            emitExpression(code, operator.operands().get(1), parameterId);
            code.iadd();
            return;
        }
        if (node instanceof IrNode.Reference reference) {
            assertEquals(Optional.of(parameterId), reference.targetDeclaration(),
                    "the scalar reference must target the function parameter");
            assertEquals(PrimitiveType.I32, reference.type(), "the scalar reference must be I32");
            code.iload(code.parameterSlot(0));
            return;
        }
        if (node instanceof IrNode.Constant constant) {
            assertEquals(PrimitiveType.I32, constant.type(), "the scalar constant must be I32");
            assertTrue(constant.value() instanceof IrConstantValue.IntegerValue,
                    "the scalar emitter accepts only an integer constant");
            assertEquals(java.math.BigInteger.ONE,
                    ((IrConstantValue.IntegerValue) constant.value()).exactValue().integerValue(),
                    "the scalar emitter accepts only the literal one");
            code.iconst_1();
            return;
        }
        throw new AssertionError("unsupported node in bounded Class-File API spike: "
                + node.getClass().getName());
    }

    private static String descriptor(FunctionType function) {
        return methodType(function).descriptorString();
    }

    private static MethodTypeDesc methodType(FunctionType function) {
        ClassDesc[] parameters = function.parameterTypes().stream()
                .map(ClassFileApiSpikeTest::primitiveDescriptor)
                .toArray(ClassDesc[]::new);
        return MethodTypeDesc.of(primitiveDescriptor(function.returnType()), parameters);
    }

    private static ClassDesc primitiveDescriptor(LyraType type) {
        if (type == PrimitiveType.I32) {
            return ConstantDescs.CD_int;
        }
        throw new AssertionError("bounded spike only supports I32, got " + type);
    }

    private static ClassModel parse(GeneratedClass generated) {
        return ClassFile.of().parse(generated.bytes());
    }

    private static MethodModel soleMethod(ClassModel model) {
        assertEquals(1, model.methods().size(), "the generated class must have one method");
        return model.methods().getFirst();
    }

    private static CodeAttribute codeAttribute(MethodModel method) {
        CodeModel code = method.code().orElseThrow(
                () -> new AssertionError("generated method has no Code attribute"));
        assertTrue(code instanceof CodeAttribute, "the parsed Code model must expose CodeAttribute");
        return (CodeAttribute) code;
    }

    private static List<Instruction> instructions(CodeAttribute code) {
        return code.elementStream()
                .filter(Instruction.class::isInstance)
                .map(Instruction.class::cast)
                .toList();
    }

    private static void verifyInExternalJvm(GeneratedClass generated) throws Exception {
        Path directory = Files.createTempDirectory("lyra-domain10-spike-");
        Path classFile = directory.resolve(GENERATED_CLASS_NAME.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, generated.bytes());
        try {
            Path javaLauncher = javaLauncher();
            String classPath = System.getProperty("java.class.path");
            assertTrue(classPath != null && !classPath.isEmpty(),
                    "the external verifier needs the Maven test class path");
            Process process = new ProcessBuilder(
                    javaLauncher.toString(),
                    "-Xverify:all",
                    "-cp",
                    classPath,
                    VerificationProbe.class.getName(),
                    classFile.toString())
                    .redirectErrorStream(false)
                    .start();
            boolean completed = process.waitFor(Duration.ofSeconds(10));
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(Duration.ofSeconds(2));
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(completed,
                    "external -Xverify:all process timed out; stdout=" + stdout + "; stderr=" + stderr);
            assertEquals(0, process.exitValue(),
                    "external -Xverify:all failed; stdout=" + stdout + "; stderr=" + stderr);
        } finally {
            deleteRecursively(directory);
        }
    }

    private static Path javaLauncher() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        assertTrue(Files.isRegularFile(candidate) && Files.isExecutable(candidate),
                "cannot reliably launch external Java verifier: java.home="
                        + System.getProperty("java.home") + "; candidate=" + candidate
                        + "; java.version=" + System.getProperty("java.version"));
        return candidate;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static <T extends ImmutablePhaseArtifact> T phaseValue(PhaseResult<T> result) {
        Objects.requireNonNull(result, "result");
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("phase failed: " + result.diagnostics());
        }
        @SuppressWarnings("unchecked")
        T value = (T) success.value();
        return value;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + "; expected=" + expected + "; actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + "; expected=" + expected + "; actual=" + actual);
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + "; expected=" + Arrays.toString(expected)
                    + "; actual=" + Arrays.toString(actual));
        }
    }

    private record Fixture(SourceSnapshot source, TypedIr ir) {
    }

    private record FunctionIr(
            String name,
            FunctionType signature,
            IrNode body,
            DeclarationId parameterId,
            int sourceLine) {
    }

    private record GeneratedClass(byte[] bytes, MethodTypeDesc methodType, boolean previewRequired) {
        private GeneratedClass {
            bytes = bytes.clone();
            Objects.requireNonNull(methodType, "methodType");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class IsolatedClassLoader extends ClassLoader {
        private IsolatedClassLoader() {
            super(null);
        }

        private Class<?> defineGenerated(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    /** Entry point used only by the parent test's -Xverify:all subprocess. */
    public static final class VerificationProbe {
        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                throw new IllegalArgumentException("expected one generated class path");
            }
            byte[] bytes = Files.readAllBytes(Path.of(args[0]));
            Class<?> generatedClass = new IsolatedClassLoader().defineGenerated(bytes);
            Method method = generatedClass.getDeclaredMethod(METHOD_NAME, int.class);
            Object result = method.invoke(null, 41);
            if (!Integer.valueOf(42).equals(result)) {
                throw new AssertionError("generated method returned " + result);
            }
        }
    }
}
