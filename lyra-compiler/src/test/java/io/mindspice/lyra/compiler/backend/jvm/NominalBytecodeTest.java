package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.api.*;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end source construction and exact nominal JVM representation coverage. */
class NominalBytecodeTest {
    @Test void failedSourceFactoryInvalidatesItsTicketAndLeavesProducerUsable() throws Throwable {
        var artifact = compile("""
                class Fallible {
                    let @pub value :I32
                    Fallible = (=> |value :I32 divisor :I32| {
                        self:.value := value
                        let ignored :F64 = (/ value divisor)
                    })
                }
                let @pub make :Fn<I32,I32;Fallible> = (=> |value divisor| Fallible[value divisor])
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
                    let pair :Pair = Pair[(next) (next)]
                    (+ (* pair:.first 10) pair:.second)
                })
                """);
        try (var loaded = LyraRuntime.load(artifact); var module = loaded.instantiate()) {
            var run = module.export("run", "Fn<;I32>").methodHandle();
            assertEquals(12, (int) run.invokeExact());
            assertEquals(34, (int) run.invokeExact());
        }
    }

    @Test void qualifiedConstructionUsesTheDefiningModuleFactory() throws Throwable {
        for (String mainSource : java.util.List.of("""
                import model as m
                let @pub make :Fn<I32;m->Point> = (=> |value| m->:.Point[value])
                """, """
                import model->{Point as P}
                let @pub make :Fn<I32;P> = (=> |value| P[value])
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

    @Test void sourceFactoriesExecuteStructDefaultsAndClassConstructors() throws Throwable {
        var structs = compile("""
                struct Point {
                    let first :I32 = self:.required
                    let @mut required :I32
                    let next :I32 = (+ self:.first 1)
                }
                let @pub make :Fn<I32;Point> = (=> |value| Point[value])
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
                let @pub make :Fn<I32;Counter> = (=> |value| Counter[value])
                let @pub run :Fn<I32;I32> = (=> |value| {
                    let counter :Counter = Counter[value]
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
                    let @mut cell :Cell = Cell[]
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
                    let @mut counter :Counter = Counter[]
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
                    let first :Counter = Counter[1]
                    let @mut second :Counter = Counter[10]
                    let original :Fn<I32;I32> = first:.change
                    second:.change := original
                    second::change[2]
                    let @mut values :Array<Counter> = Array[Counter[0]]
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
                let @pub equalValues :Fn<;Bool> = (=> || (== Node[3] Node[3]))
                let @pub differentValues :Fn<;Bool> = (=> || (== Node[3] Node[4]))
                let @pub equalCycles :Fn<;Bool> = (=> || {
                    let @mut left :Node = Node[5]
                    let @mut right :Node = Node[5]
                    left:.next := left
                    right:.next := right
                    (== left right)
                })
                let @pub differentCycles :Fn<;Bool> = (=> || {
                    let @mut left :Node = Node[5]
                    let @mut right :Node = Node[6]
                    left:.next := left
                    right:.next := right
                    (== left right)
                })
                let @pub unequalCycles :Fn<;Bool> = (=> || {
                    let @mut left :Node = Node[5]
                    let @mut right :Node = Node[6]
                    left:.next := left
                    right:.next := right
                    (!= left right)
                })
                let @pub matchedCycle :Fn<;Bool> = (=> || {
                    let @mut left :Node = Node[5]
                    let @mut right :Node = Node[5]
                    left:.next := left
                    right:.next := right
                    (match left ?? right -> #T ?? _ -> #F)
                })
                let @pub freshContexts :Fn<;Bool> = (=> || {
                    let @mut left :Node = Node[5]
                    let @mut right :Node = Node[5]
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
                    let box :Box = Box[]
                    (== box box)
                })
                let @pub classDistinct :Fn<;Bool> = (=> || (== Box[] Box[]))
                let @pub classIdentity :Fn<;Bool> = (=> || {
                    let box :Box = Box[]
                    (eq? box box)
                })
                let @pub nestedClassIdentity :Fn<;Bool> = (=> || {
                    let box :Box = Box[]
                    (== Holder[box] Holder[box])
                })
                let @pub nestedStructs :Fn<;Bool> = (=> ||
                    (== Tuple[Array[Node[9]]] Tuple[Array[Node[9]]]))
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
                        + (classType ? "" : "value") + "| Sample[" + constructor + "])\n"
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
