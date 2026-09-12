package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.api.*;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/** Source-produced object classes; factory invocation is driven by the host until source construction is wired. */
class NominalBytecodeTest {
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
        var result = LyraCompiler.compile(CompileRequest.source("nominal-bytecode.lyra", source));
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
                String source = (classType ? "class" : "struct") + " Sample { let @pub "
                        + (mutable ? "@mut " : "") + "value :" + (wide ? "I64" : "I32") + (classType ? " = 0" : "") + " }";
                String replay = "seed=" + seed + ", index=" + index + "\n" + source;
                var artifact = compile(source);
                var type = representation(artifact, "Sample");
                Class<?> primitive = wide ? long.class : int.class;
                assertEquals(primitive, type.getDeclaredField("$lyra$field$0").getType(), replay);
                var producer = producer(artifact);
                var ticket = begin(artifact, producer, type);
                var value = type.getConstructor(LyraNominalConstruction.class).newInstance(ticket);
                Object expected;
                if (wide) expected = random.nextLong(); else expected = random.nextInt();
                type.getMethod("$lyra$initialize$0", LyraNominalConstruction.class, primitive).invoke(value, ticket, expected);
                ticket.complete(value); producer.open();
                assertEquals(expected, type.getMethod("$lyra$public$get$0").invoke(value), replay);
                if (mutable) {
                    Object next;
                    if (wide) next = random.nextLong(); else next = random.nextInt();
                    type.getMethod("$lyra$public$set$0", primitive).invoke(value, next);
                    assertEquals(next, type.getMethod("$lyra$public$get$0").invoke(value), replay);
                } else assertThrows(NoSuchMethodException.class, () -> type.getMethod("$lyra$public$set$0", primitive), replay);
                producer.close();
            }
        }
    }
}
