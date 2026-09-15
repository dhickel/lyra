package io.mindspice.lyra.compiler.benchmark;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.ExportHandle;
import io.mindspice.lyra.runtime.LyraArithmeticException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.ModuleHandle;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase 23's explicit generated-Lyra versus direct-Java workload matrix.
 *
 * <p>This is a test-fixture benchmark, not a runtime API.  All generated
 * calls in the hot methods use exact primitive or reference {@link MethodHandle}
 * call sites.  The few {@code Object} adapters are created during trial setup
 * only because generated tuple and closure classes are intentionally not part
 * of the benchmark's compile-time class path.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"--enable-preview"})
@Threads(1)
public class Phase23Benchmark {
    static final String SOURCE_FILE = "phase23-performance.lyra";
    private static final String ADD_SIGNATURE = "Fn<I32,I32;I32>";
    private static final String ARITHMETIC_SIGNATURE = "Fn<I32,I32;I32>";
    private static final String BRANCH_SIGNATURE = "Fn<I32;I32>";
    private static final String TAIL_SIGNATURE = "Fn<I32,I32;I32>";
    private static final String COUNTER_SIGNATURE = "Fn<I32;Fn<;I32>>";
    private static final String ARRAY_AT_SIGNATURE = "Fn<Array<I32>,I32;I32>";
    private static final String ARRAY_MAKER_SIGNATURE = "Fn<I32;Array<I32>>";
    private static final String TUPLE_FIRST_SIGNATURE = "Fn<Tuple<I32,String>;I32>";
    private static final String TUPLE_MAKER_SIGNATURE = "Fn<I32;Tuple<I32,String>>";
    private static final String STRING_LENGTH_SIGNATURE = "Fn<String;I32>";
    private static final String CONCAT_SIGNATURE = "Fn<String,String;String>";
    private static final String FIB_SIGNATURE = "Fn<I32;I32>";
    private static final String NAMED_SIGNATURE = "Fn<I32,I32;I32>";
    private static final String DIVIDE_SIGNATURE = "Fn<I32;F64>";

    static final String SOURCE = """
            let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))
            let @pub arithmetic :Fn<I32,I32;I32> = (=> |left right| (+ (* left right) 3))
            let @pub branch :Fn<I32;I32> = (=> |value| ((<= value 0) -> 1 : 2))
            let @pub tailSum :Fn<I32,I32;I32> = (=> |n acc| ((<= n 0) -> acc : ::tailSum[(- n 1) (+ acc n)]))
            let @pub makeCounter :Fn<I32;Fn<;I32>> = (=> |value| { let @mut current :I32 = value (=> | | { current := (+ current 1) current }) })
            let @pub arrayAt :Fn<Array<I32>,I32;I32> = (=> |values index| values[index])
            let @pub makeArray :Fn<I32;Array<I32>> = (=> |value| Array[value (+ value 1) (+ value 2)])
            let @pub tupleFirst :Fn<Tuple<I32,String>;I32> = (=> |value| value:.0)
            let @pub makeTuple :Fn<I32;Tuple<I32,String>> = (=> |value| Tuple[value "tuple"])
            let @pub stringLength :Fn<String;I32> = (=> |value| value:.length)
            let @pub concat :Fn<String,String;String> = (=> |left right| (+ left right))
            let @pub fibS :Fn<I32;I32> = (=> |n| (match n 0 -> 0 1 -> 1 _ -> (+ (fibS (- n 1)) (fibS (- n 2)))))
            let @pub fibF :Fn<I32;I32> = (=> |n| (match n 0 -> 0 1 -> 1 _ -> (+ ::fibF[(- n 1)], ::fibF[(- n 2)])))
            let @pub namedS :Fn<I32,I32;I32> = (=> |left right| (add left right))
            let @pub namedF :Fn<I32,I32;I32> = (=> |left right| ::add[left right])
            let zero :I32 = 0
            let @pub divide :Fn<I32;F64> = (=> |value| (/ value zero))
            """;

    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();
    private static final MethodType OBJECT_FROM_INT = MethodType.methodType(Object.class, int.class);
    private static final MethodType INT_FROM_OBJECT = MethodType.methodType(int.class, Object.class);
    private static final MethodType OBJECT_FROM_VOID = MethodType.methodType(Object.class);

    private CompiledArtifact artifact;
    private LoadedArtifact loaded;
    private ModuleHandle module;
    private FacadeFixture facade;

    private MethodHandle exactAdd;
    private MethodHandle exactArithmetic;
    private MethodHandle exactBranch;
    private MethodHandle exactTail;
    private MethodHandle exactCounterFactory;
    private MethodHandle exactArrayAt;
    private MethodHandle exactArrayFactory;
    private MethodHandle exactTupleFirst;
    private MethodHandle exactTupleFactory;
    private MethodHandle exactStringLength;
    private MethodHandle exactConcat;
    private MethodHandle exactDivide;
    private MethodHandle exactFibS;
    private MethodHandle exactFibF;
    private MethodHandle exactNamedS;
    private MethodHandle exactNamedF;

    private MethodHandle cachedAdd;
    private MethodHandle facadeAdd;
    private MethodHandle javaAdd;
    private MethodHandle javaArithmetic;
    private MethodHandle javaBranch;
    private MethodHandle javaTail;
    private MethodHandle javaCounterInvoke;
    private MethodHandle javaArrayAt;
    private MethodHandle javaArrayFactory;
    private MethodHandle javaTupleFirst;
    private MethodHandle javaTupleFactory;
    private MethodHandle javaStringLength;
    private MethodHandle javaConcat;
    private MethodHandle javaDivide;
    private MethodHandle javaFacadeAdd;
    private MethodHandle counterInvoke;
    private MethodHandle counterFactoryAsObject;
    private MethodHandle tupleFirstAsObject;
    private MethodHandle tupleFactoryAsObject;
    private Object generatedCounter;
    private Object generatedTuple;
    private String generatedLeftString;
    private String generatedRightString;
    private int[] generatedArray;
    private DirectJava direct;
    private DirectTuple directTuple;

    @Setup(Level.Trial)
    public void setup() throws Throwable {
        CompileResult result = LyraCompiler.compile(
                CompileRequest.source(SOURCE_FILE, SOURCE));
        if (!(result instanceof CompileResult.Success success)) {
            throw new IllegalStateException("Phase 23 benchmark fixture failed to compile: " + result);
        }
        artifact = success.artifact();
        loaded = LyraRuntime.load(artifact);
        module = loaded.instantiate();

        exactAdd = export("add", ADD_SIGNATURE).methodHandle();
        exactArithmetic = export("arithmetic", ARITHMETIC_SIGNATURE).methodHandle();
        exactBranch = export("branch", BRANCH_SIGNATURE).methodHandle();
        exactTail = export("tailSum", TAIL_SIGNATURE).methodHandle();
        exactCounterFactory = export("makeCounter", COUNTER_SIGNATURE).methodHandle();
        exactArrayAt = export("arrayAt", ARRAY_AT_SIGNATURE).methodHandle();
        exactArrayFactory = export("makeArray", ARRAY_MAKER_SIGNATURE).methodHandle();
        exactTupleFirst = export("tupleFirst", TUPLE_FIRST_SIGNATURE).methodHandle();
        exactTupleFactory = export("makeTuple", TUPLE_MAKER_SIGNATURE).methodHandle();
        exactStringLength = export("stringLength", STRING_LENGTH_SIGNATURE).methodHandle();
        exactConcat = export("concat", CONCAT_SIGNATURE).methodHandle();
        exactDivide = export("divide", DIVIDE_SIGNATURE).methodHandle();
        exactFibS = export("fibS", FIB_SIGNATURE).methodHandle();
        exactFibF = export("fibF", FIB_SIGNATURE).methodHandle();
        exactNamedS = export("namedS", NAMED_SIGNATURE).methodHandle();
        exactNamedF = export("namedF", NAMED_SIGNATURE).methodHandle();
        cachedAdd = module.export("add", ADD_SIGNATURE).methodHandle();

        counterFactoryAsObject = exactCounterFactory.asType(OBJECT_FROM_INT);
        generatedCounter = (Object) counterFactoryAsObject.invokeExact(3);
        Class<?> counterInterface = generatedCounter.getClass().getInterfaces()[0];
        counterInvoke = PUBLIC_LOOKUP.findVirtual(
                counterInterface, "invoke", MethodType.methodType(int.class)).bindTo(generatedCounter);

        tupleFactoryAsObject = exactTupleFactory.asType(OBJECT_FROM_INT);
        generatedTuple = (Object) tupleFactoryAsObject.invokeExact(5);
        tupleFirstAsObject = exactTupleFirst.asType(INT_FROM_OBJECT);
        generatedLeftString = new String("phase");
        generatedRightString = new String("23");

        generatedArray = (int[]) exactArrayFactory.invokeExact(5);
        direct = new DirectJava();
        directTuple = new DirectTuple(5, "tuple");
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        DirectJavaFacade javaFacade = new DirectJavaFacade(direct, directTuple);
        javaAdd = lookup.findVirtual(DirectJavaFacade.class, "add",
                MethodType.methodType(int.class, int.class, int.class)).bindTo(javaFacade);
        javaArithmetic = lookup.findVirtual(DirectJavaFacade.class, "arithmetic",
                MethodType.methodType(int.class, int.class, int.class)).bindTo(javaFacade);
        javaBranch = lookup.findVirtual(DirectJavaFacade.class, "branch",
                MethodType.methodType(int.class, int.class)).bindTo(javaFacade);
        javaTail = lookup.findVirtual(DirectJavaFacade.class, "tailSum",
                MethodType.methodType(int.class, int.class, int.class)).bindTo(javaFacade);
        javaCounterInvoke = lookup.findVirtual(DirectJavaFacade.class, "incrementCounter",
                MethodType.methodType(int.class)).bindTo(javaFacade);
        javaArrayAt = lookup.findVirtual(DirectJavaFacade.class, "arrayAt",
                MethodType.methodType(int.class, int[].class, int.class)).bindTo(javaFacade);
        javaArrayFactory = lookup.findVirtual(DirectJavaFacade.class, "makeArray",
                MethodType.methodType(int[].class, int.class)).bindTo(javaFacade);
        javaTupleFirst = lookup.findVirtual(DirectJavaFacade.class, "tupleFirst",
                MethodType.methodType(int.class)).bindTo(javaFacade);
        javaTupleFactory = lookup.findVirtual(DirectJavaFacade.class, "makeTuple",
                MethodType.methodType(DirectTuple.class, int.class)).bindTo(javaFacade);
        javaStringLength = lookup.findVirtual(DirectJavaFacade.class, "stringLength",
                MethodType.methodType(int.class)).bindTo(javaFacade);
        javaConcat = lookup.findVirtual(DirectJavaFacade.class, "concat",
                MethodType.methodType(String.class)).bindTo(javaFacade);
        javaDivide = lookup.findVirtual(DirectJavaFacade.class, "divideByZero",
                MethodType.methodType(void.class, int.class)).bindTo(javaFacade);

        facade = FacadeFixture.open(artifact);
        facadeAdd = facade.addHandle();
        javaFacadeAdd = javaAdd;
    }

    private ExportHandle export(String name, String signature) {
        return module.export(name, signature);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Throwable {
        Throwable failure = null;
        if (facade != null) {
            try {
                facade.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
        }
        if (module != null) {
            try {
                module.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
            }
        }
        if (loaded != null) {
            try {
                loaded.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Benchmark
    public void generatedWorkload(WorkloadState state, InputState input, Blackhole blackhole) throws Throwable {
        switch (state.scenario) {
            case "typed-direct-call" -> blackhole.consume((int) exactAdd.invokeExact(input.left, input.right));
            case "arithmetic" -> blackhole.consume((int) exactArithmetic.invokeExact(input.left, input.right));
            case "branch" -> blackhole.consume((int) exactBranch.invokeExact(input.branchValue));
            case "tail-recursion" -> blackhole.consume((int) exactTail.invokeExact(input.tailCount, input.accumulator));
            case "closure-cell" -> blackhole.consume((int) counterInvoke.invokeExact());
            case "array-read" -> blackhole.consume((int) exactArrayAt.invokeExact(generatedArray, input.index));
            case "tuple-read" -> blackhole.consume((int) tupleFirstAsObject.invokeExact(generatedTuple));
            case "string" -> blackhole.consume((int) exactStringLength.invokeExact(generatedLeftString));
            case "array-allocation" -> blackhole.consume((int[]) exactArrayFactory.invokeExact(input.allocationValue));
            case "tuple-allocation" -> blackhole.consume((Object) tupleFactoryAsObject.invokeExact(input.allocationValue));
            case "closure-allocation" -> blackhole.consume((Object) counterFactoryAsObject.invokeExact(input.allocationValue));
            case "string-allocation" -> blackhole.consume(
                    (String) exactConcat.invokeExact(generatedLeftString, generatedRightString));
            case "failure" -> generatedFailure(input.failureNumerator, blackhole);
            default -> throw new AssertionError("unknown Phase 23 workload: " + state.scenario);
        }
    }

    /** Raw direct Java is retained as an optimization-floor observation, not a ratio denominator. */
    @Benchmark
    public void javaWorkload(WorkloadState state, InputState input, Blackhole blackhole) {
        switch (state.scenario) {
            case "typed-direct-call" -> blackhole.consume(direct.add(input.left, input.right));
            case "arithmetic" -> blackhole.consume(direct.arithmetic(input.left, input.right));
            case "branch" -> blackhole.consume(direct.branch(input.branchValue));
            case "tail-recursion" -> blackhole.consume(direct.tailSum(input.tailCount, input.accumulator));
            case "closure-cell" -> blackhole.consume(direct.counter.increment());
            case "array-read" -> blackhole.consume(direct.arrayAt(direct.array, input.index));
            case "tuple-read" -> blackhole.consume(directTuple.first());
            case "string" -> blackhole.consume(direct.stringLength());
            case "array-allocation" -> blackhole.consume(DirectJava.makeArray(input.allocationValue));
            case "tuple-allocation" -> blackhole.consume(makeDirectTuple(input.allocationValue));
            case "closure-allocation" -> blackhole.consume(new DirectCounter(input.allocationValue));
            case "string-allocation" -> blackhole.consume(direct.concat());
            case "failure" -> javaFailure(input.failureNumerator, blackhole);
            default -> throw new AssertionError("unknown Phase 23 workload: " + state.scenario);
        }
    }

    /**
     * Gate denominator: the same inputs and exact MethodHandle invocation
     * boundary as generated runtime lookup. This prevents raw Java inlining or
     * constant folding from being mislabeled as an equivalent API boundary.
     */
    @Benchmark
    public void javaEquivalentWorkload(WorkloadState state, InputState input, Blackhole blackhole) throws Throwable {
        switch (state.scenario) {
            case "typed-direct-call" -> blackhole.consume((int) javaAdd.invokeExact(input.left, input.right));
            case "arithmetic" -> blackhole.consume((int) javaArithmetic.invokeExact(input.left, input.right));
            case "branch" -> blackhole.consume((int) javaBranch.invokeExact(input.branchValue));
            case "tail-recursion" -> blackhole.consume((int) javaTail.invokeExact(input.tailCount, input.accumulator));
            case "closure-cell" -> blackhole.consume((int) javaCounterInvoke.invokeExact());
            case "array-read" -> blackhole.consume((int) javaArrayAt.invokeExact(direct.array, input.index));
            case "tuple-read" -> blackhole.consume((int) javaTupleFirst.invokeExact());
            case "string" -> blackhole.consume((int) javaStringLength.invokeExact());
            case "array-allocation" -> blackhole.consume((int[]) javaArrayFactory.invokeExact(input.allocationValue));
            case "tuple-allocation" -> blackhole.consume((DirectTuple) javaTupleFactory.invokeExact(input.allocationValue));
            case "closure-allocation" -> blackhole.consume(new DirectCounter(input.allocationValue));
            case "string-allocation" -> blackhole.consume((String) javaConcat.invokeExact());
            case "failure" -> javaEquivalentFailure(input.failureNumerator, blackhole);
            default -> throw new AssertionError("unknown Phase 23 workload: " + state.scenario);
        }
    }

    @Benchmark
    public int generatedWarmExactHandleCall(InputState input) throws Throwable {
        return (int) exactAdd.invokeExact(input.left, input.right);
    }

    @Benchmark
    public int generatedFacadeCall(InputState input) throws Throwable {
        return (int) facadeAdd.invokeExact(input.left, input.right);
    }

    @Benchmark
    public int javaWarmDirectCall(InputState input) {
        return direct.add(input.left, input.right);
    }

    @Benchmark
    public int javaWarmExactHandleCall(InputState input) throws Throwable {
        return (int) javaAdd.invokeExact(input.left, input.right);
    }

    @Benchmark
    public int javaFacadeCall(InputState input) throws Throwable {
        return (int) javaFacadeAdd.invokeExact(input.left, input.right);
    }

    @Benchmark
    public int generatedCachedExportHandleCall(InputState input) throws Throwable {
        return (int) cachedAdd.invokeExact(input.left, input.right);
    }

    /**
     * Issue #6 call-homogeneity pair: the S-expression self-recursive fib
     * and the direct-name spelling share the canonical proven-route lowering.
     */
    @Benchmark
    public int fibSExpressionCall(InputState input) throws Throwable {
        return (int) exactFibS.invokeExact(input.fibDepth);
    }

    @Benchmark
    public int fibDirectNameCall(InputState input) throws Throwable {
        return (int) exactFibF.invokeExact(input.fibDepth);
    }

    /** Issue #6 call-homogeneity pair for an ordinary named call. */
    @Benchmark
    public int namedSExpressionCall(InputState input) throws Throwable {
        return (int) exactNamedS.invokeExact(input.left, input.right);
    }

    @Benchmark
    public int namedDirectNameCall(InputState input) throws Throwable {
        return (int) exactNamedF.invokeExact(input.left, input.right);
    }

    @Benchmark
    public int generatedColdLoadAndCall() throws Throwable {
        LoadedArtifact coldLoaded = LyraRuntime.load(artifact);
        try {
            ModuleHandle coldModule = coldLoaded.instantiate();
            try {
                MethodHandle coldAdd = coldModule.export("add", ADD_SIGNATURE).methodHandle();
                return (int) coldAdd.invokeExact(19, 23);
            } finally {
                coldModule.close();
            }
        } finally {
            coldLoaded.close();
        }
    }

    @Benchmark
    public int javaColdConstructionAndCall() {
        return new DirectJava().add(19, 23);
    }

    private void generatedFailure(int numerator, Blackhole blackhole) throws Throwable {
        try {
            double ignored = (double) exactDivide.invokeExact(numerator);
            throw new AssertionError("division fixture unexpectedly returned " + ignored);
        } catch (LyraArithmeticException expected) {
            blackhole.consume(expected.code());
        }
    }

    private void javaFailure(int numerator, Blackhole blackhole) {
        try {
            direct.divideByZero(numerator);
            throw new AssertionError("division fixture unexpectedly returned");
        } catch (ArithmeticException expected) {
            blackhole.consume(expected.getClass().getName());
        }
    }

    private void javaEquivalentFailure(int numerator, Blackhole blackhole) throws Throwable {
        try {
            javaDivide.invokeExact(numerator);
            throw new AssertionError("division fixture unexpectedly returned");
        } catch (ArithmeticException expected) {
            blackhole.consume(expected.getClass().getName());
        }
    }

    private static DirectTuple makeDirectTuple(int value) {
        return new DirectTuple(value, "tuple");
    }

    @State(Scope.Thread)
    public static class InputState {
        int left = 19;
        int right = 23;
        int branchValue = -1;
        int tailCount = 64;
        int accumulator;
        int index = 1;
        int allocationValue = 7;
        int failureNumerator = 1;
        int fibDepth = 24;
    }

    @State(Scope.Thread)
    public static class WorkloadState {
        @Param({
                "typed-direct-call",
                "arithmetic",
                "branch",
                "tail-recursion",
                "closure-cell",
                "array-read",
                "tuple-read",
                "string",
                "array-allocation",
                "tuple-allocation",
                "closure-allocation",
                "string-allocation",
                "failure"
        })
        public String scenario;
    }

    private static final class DirectJava {
        private static volatile int constructions;
        private final int[] array = makeArray(5);
        private final DirectCounter counter = new DirectCounter(3);
        private final String leftString = new String("phase");
        private final String rightString = new String("23");

        private DirectJava() {
            // Keep the cold construction path observable while retaining the
            // same direct-Java fixture state used by the paired workloads.
            constructions += array.length + counter.value
                    + leftString.length() + rightString.length();
        }

        int add(int left, int right) {
            return left + right;
        }

        int arithmetic(int left, int right) {
            return left * right + 3;
        }

        int branch(int value) {
            return value <= 0 ? 1 : 2;
        }

        int tailSum(int n, int accumulator) {
            while (n > 0) {
                accumulator += n;
                n--;
            }
            return accumulator;
        }

        int arrayAt(int[] values, int index) {
            return values[index];
        }

        int stringLength() {
            return leftString.length();
        }

        String concat() {
            return leftString + rightString;
        }

        void divideByZero(int value) {
            int denominator = 0;
            if (denominator == 0) {
                throw new ArithmeticException("division by zero");
            }
            double ignored = (double) value / denominator;
        }

        static int[] makeArray(int value) {
            return new int[] {value, value + 1, value + 2};
        }
    }

    /** Java-side analogue of the generated typed facade's owner/open boundary. */
    private static final class DirectJavaFacade {
        private final Thread owner = Thread.currentThread();
        private final DirectJava implementation;
        private final DirectTuple tuple;
        private boolean open = true;

        private DirectJavaFacade(DirectJava implementation, DirectTuple tuple) {
            this.implementation = implementation;
            this.tuple = tuple;
        }

        private void checkOpen() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("wrong owner thread");
            }
            if (!open) {
                throw new IllegalStateException("closed");
            }
        }

        private int add(int left, int right) {
            checkOpen();
            return implementation.add(left, right);
        }

        private int arithmetic(int left, int right) {
            checkOpen();
            return implementation.arithmetic(left, right);
        }

        private int branch(int value) {
            checkOpen();
            return implementation.branch(value);
        }

        private int tailSum(int count, int accumulator) {
            checkOpen();
            return implementation.tailSum(count, accumulator);
        }

        private int incrementCounter() {
            checkOpen();
            return implementation.counter.increment();
        }

        private int arrayAt(int[] values, int index) {
            checkOpen();
            return implementation.arrayAt(values, index);
        }

        private int[] makeArray(int value) {
            checkOpen();
            return DirectJava.makeArray(value);
        }

        private int tupleFirst() {
            checkOpen();
            return tuple.first();
        }

        private DirectTuple makeTuple(int value) {
            checkOpen();
            return makeDirectTuple(value);
        }

        private int stringLength() {
            checkOpen();
            return implementation.stringLength();
        }

        private String concat() {
            checkOpen();
            return implementation.concat();
        }

        private void divideByZero(int value) {
            checkOpen();
            implementation.divideByZero(value);
        }
    }

    private static final class DirectCounter {
        private int value;

        private DirectCounter(int value) {
            this.value = value;
        }

        private int increment() {
            return ++value;
        }
    }

    private record DirectTuple(int first, String second) {
    }

    private static final class FacadeFixture {
        private final GeneratedClassLoader loader;
        private final URLClassLoader consumerLoader;
        private final Path temporaryRoot;
        private final MethodHandle close;
        private final MethodHandle add;

        private FacadeFixture(GeneratedClassLoader loader, URLClassLoader consumerLoader,
                              Path temporaryRoot, MethodHandle close, MethodHandle add) {
            this.loader = loader;
            this.consumerLoader = consumerLoader;
            this.temporaryRoot = temporaryRoot;
            this.close = close;
            this.add = add;
        }

        static FacadeFixture open(CompiledArtifact artifact) throws Throwable {
            Path temporaryRoot = Files.createTempDirectory("lyra-phase23-facade-");
            GeneratedClassLoader loader = null;
            URLClassLoader consumerLoader = null;
            try {
                Path generated = temporaryRoot.resolve("generated");
                Path consumer = temporaryRoot.resolve("consumer");
                Files.createDirectories(consumer);
                artifact.writeClasses(generated);
                loader = new GeneratedClassLoader(artifact.classes());
                String facadeName = artifact.classes().keySet().stream()
                        .filter(name -> name.contains(".$lyra$facade$"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("generated facade is missing"));
                Class<?> facadeClass = Class.forName(facadeName, true, loader);
                MethodHandle factory = PUBLIC_LOOKUP.findStatic(
                        facadeClass, "$lyra$create", MethodType.methodType(facadeClass));
                Object instance = (Object) factory.asType(OBJECT_FROM_VOID).invokeExact();
                MethodHandle close = PUBLIC_LOOKUP.findVirtual(
                        facadeClass, "close", MethodType.methodType(void.class)).bindTo(instance);

                var compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    throw new IllegalStateException("the benchmark JVM must provide javac");
                }
                String consumerName = "Phase23FacadeConsumer";
                Path consumerSource = temporaryRoot.resolve(consumerName + ".java");
                Files.writeString(consumerSource,
                        "public final class " + consumerName + " {"
                                + " public static int call(" + facadeName + " module, int left, int right) {"
                                + " return module.add(left, right);"
                                + " }"
                                + "}");
                String classPath = generated + java.io.File.pathSeparator
                        + System.getProperty("java.class.path");
                int exit = compiler.run(null, null, null, "--release", "25",
                        "-classpath", classPath, "-d", consumer.toString(),
                        consumerSource.toString());
                if (exit != 0) {
                    throw new IllegalStateException("generated facade Java consumer compilation failed: " + exit);
                }
                consumerLoader = new URLClassLoader(
                        new URL[]{consumer.toUri().toURL()}, loader);
                Class<?> consumerClass = Class.forName(consumerName, true, consumerLoader);
                MethodHandle add = PUBLIC_LOOKUP.findStatic(
                        consumerClass, "call",
                        MethodType.methodType(int.class, facadeClass, int.class, int.class))
                        .bindTo(instance);
                return new FacadeFixture(loader, consumerLoader, temporaryRoot, close, add);
            } catch (Throwable failure) {
                if (consumerLoader != null) {
                    try {
                        consumerLoader.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (loader != null) {
                    loader.clear();
                }
                deleteTree(temporaryRoot, failure);
                throw failure;
            }
        }

        MethodHandle addHandle() {
            return add;
        }

        void close() throws Throwable {
            Throwable failure = null;
            try {
                close.invokeExact();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            } finally {
                try {
                    consumerLoader.close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
                loader.clear();
                deleteTree(temporaryRoot, failure);
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static void deleteTree(Path root, Throwable failure) throws IOException {
            if (!Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                var ordered = paths.sorted(java.util.Comparator.reverseOrder()).toList();
                IOException first = null;
                for (Path path : ordered) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException deleteFailure) {
                        if (first == null) {
                            first = deleteFailure;
                        } else {
                            first.addSuppressed(deleteFailure);
                        }
                    }
                }
                if (first != null) {
                    if (failure != null) {
                        failure.addSuppressed(first);
                    } else {
                        throw first;
                    }
                }
            }
        }
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private Map<String, byte[]> definitions;

        private GeneratedClassLoader(Map<String, byte[]> classes) {
            super(Phase23Benchmark.class.getClassLoader());
            this.definitions = Map.copyOf(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        private void clear() {
            definitions = Map.of();
        }
    }
}
