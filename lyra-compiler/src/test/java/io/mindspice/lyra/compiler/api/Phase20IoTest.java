package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ExportHandle;
import io.mindspice.lyra.runtime.ExportMetadata;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LyraIoException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraThreadException;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.ModuleId;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase20IoTest {
    private static final String IO_IMPORT = "import std->io as io ";
    private static final String PRINT_SOURCE = IO_IMPORT
            + "let @pub write :Fn<String;Unit> = (=> |value| io->::print[value]) "
            + "let @pub writeLine :Fn<String;Unit> = (=> |value| io->::println[value]) "
            + "let @pub error :Fn<String;Unit> = (=> |value| io->::eprint[value]) "
            + "let @pub errorLine :Fn<String;Unit> = (=> |value| io->::eprintln[value])";
    private static final String READ_SOURCE = IO_IMPORT
            + "let @pub read :Fn<;@nil String> = (=> | | io->::readLine[])";

    @Test
    void outputUsesConfiguredCharsetOneWritePerCallAndFlushes() throws Throwable {
        RecordingOutputStream output = new RecordingOutputStream();
        RecordingOutputStream error = new RecordingOutputStream();
        try (Fixture fixture = fixture(PRINT_SOURCE,
                new ByteArrayInputStream(new byte[0]), output, error)) {
            assertNull(fixture.call("write", "Fn<String;Unit>", "é"));
            assertNull(fixture.call("writeLine", "Fn<String;Unit>", "line"));
            assertNull(fixture.call("error", "Fn<String;Unit>", "ß"));
            assertNull(fixture.call("errorLine", "Fn<String;Unit>", "error"));
        }
        assertEquals(List.of("é", "line\n"), output.texts());
        assertEquals(List.of("ß", "error\n"), error.texts());
        assertEquals(2, output.flushes);
        assertEquals(2, error.flushes);
        assertFalse(output.closed);
        assertFalse(error.closed);
    }

    @Test
    void intrinsicMetadataHasThePinnedSurfaceAndContractRevision() {
        CompileResult.Success ordinary = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("phase20.lyra", "import std->io")));
        CompileResult.Success configured = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.builder()
                        .rootSource("phase20.lyra", "import std->io")
                        .semanticOptions(Map.of("io-contract-test", "enabled"))
                        .build()));
        ArtifactMetadata metadata = ordinary.artifact().metadata();
        ModuleId intrinsicId = ModuleId.uri(URI.create("lyra:intrinsic/std/io"));
        var intrinsic = metadata.modules().stream()
                .filter(module -> module.id().equals(intrinsicId))
                .findFirst().orElseThrow();
        var configuredIntrinsic = configured.artifact().metadata().modules().stream()
                .filter(module -> module.id().equals(intrinsicId))
                .findFirst().orElseThrow();
        assertEquals(intrinsic.revision(), configuredIntrinsic.revision());
        assertEquals(Map.of(
                "print", "Fn<String;Unit>",
                "println", "Fn<String;Unit>",
                "eprint", "Fn<String;Unit>",
                "eprintln", "Fn<String;Unit>",
                "readLine", "Fn<;@nilString>"), metadata.exports().stream()
                .filter(export -> export.moduleId().equals(intrinsicId))
                .collect(java.util.stream.Collectors.toMap(
                        ExportMetadata::name, ExportMetadata::canonicalContract)));
        metadata.exports().stream()
                .filter(export -> export.moduleId().equals(intrinsicId))
                .forEach(export -> {
                    assertEquals("Fn<;@nilString>".equals(export.canonicalContract())
                                    ? "()Ljava/lang/String;"
                                    : "(Ljava/lang/String;)V", export.jvmDescriptor());
                    assertFalse(export.mutable());
                    assertTrue(export.setterName().isEmpty());
                });
    }

    @Test
    void directSelectiveIntrinsicCallsCompileAndLowerAsInitializationEffects() throws Throwable {
        RecordingOutputStream output = new RecordingOutputStream();
        try (Fixture ignored = fixture("import std->io->{print} (print \"direct\")",
                new ByteArrayInputStream(new byte[0]), output, new RecordingOutputStream())) {
            // The call runs while the root state is initialized, before a
            // facade is published.  A closure/value lookup path would not
            // satisfy this source form's direct intrinsic lowering contract.
        }
        assertEquals(List.of("direct"), output.texts());
        assertEquals(1, output.flushes);
    }

    @Test
    void readLinePreservesDecoderStateAcrossLinesForBomSelectedCharsets() throws Throwable {
        byte[] littleEndian = "one\né\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] input = new byte[littleEndian.length + 2];
        input[0] = (byte) 0xff;
        input[1] = (byte) 0xfe;
        System.arraycopy(littleEndian, 0, input, 2, littleEndian.length);
        try (Fixture fixture = fixture(READ_SOURCE,
                new ByteArrayInputStream(input), new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), StandardCharsets.UTF_16)) {
            assertEquals("one", fixture.call("read", "Fn<;@nilString>"));
            assertEquals("é", fixture.call("read", "Fn<;@nilString>"));
            assertNull(fixture.call("read", "Fn<;@nilString>"));
        }
    }

    @Test
    void readLineDecodesUtf8StripsOnlyCrLfAndUsesNilOnlyAtCleanEof() throws Throwable {
        byte[] input = "é\r\n\nlast\r".getBytes(StandardCharsets.UTF_8);
        try (Fixture fixture = fixture(READ_SOURCE, new ByteArrayInputStream(input),
                new ByteArrayOutputStream(), new ByteArrayOutputStream())) {
            assertEquals("é", fixture.call("read", "Fn<;@nilString>"));
            assertEquals("", fixture.call("read", "Fn<;@nilString>"));
            assertEquals("last\r", fixture.call("read", "Fn<;@nilString>"));
            assertNull(fixture.call("read", "Fn<;@nilString>"));
        }
    }

    @Test
    void streamFailuresIncludeFlushAndNeverCloseConfiguredStreams() throws Throwable {
        TrackingInputStream input = new TrackingInputStream("line\n".getBytes(StandardCharsets.UTF_8));
        RecordingOutputStream output = new RecordingOutputStream();
        RecordingOutputStream error = new RecordingOutputStream();
        String source = IO_IMPORT
                + "let @pub write :Fn<String;Unit> = (=> |value| io->::print[value]) "
                + "let @pub read :Fn<;@nil String> = (=> | | io->::readLine[])";
        try (Fixture fixture = fixture(source, input, output, error)) {
            assertNull(fixture.call("write", "Fn<String;Unit>", "owned"));
            assertEquals("line", fixture.call("read", "Fn<;@nilString>"));
        }
        assertFalse(input.closed);
        assertFalse(output.closed);
        assertFalse(error.closed);

        try (Fixture fixture = fixture(PRINT_SOURCE, new ByteArrayInputStream(new byte[0]),
                new FlushFailingOutputStream(), new ByteArrayOutputStream())) {
            LyraIoException failure = assertThrows(LyraIoException.class,
                    () -> fixture.call("write", "Fn<String;Unit>", "flush"));
            assertEquals("LYR-IO", failure.code());
            assertEquals("flush failed", failure.javaCause().orElseThrow().getMessage());
        }
    }

    @Test
    void concurrentOwnerInstancesSerializeEachOutputRecord() throws Throwable {
        RecordingOutputStream output = new RecordingOutputStream();
        try (Fixture fixture = fixture(PRINT_SOURCE, new ByteArrayInputStream(new byte[0]),
                output, new ByteArrayOutputStream())) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable task = () -> {
                ModuleHandle module = null;
                try {
                    ready.countDown();
                    start.await();
                    module = fixture.loaded.instantiate();
                    ExportHandle write = module.export("write", "Fn<String;Unit>");
                    for (int index = 0; index < 200; index++) {
                        write.methodHandle().invokeWithArguments(
                                Thread.currentThread().getName().equals("io-a") ? "A" : "B");
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    if (module != null) {
                        module.close();
                    }
                }
            };
            Thread first = new Thread(task, "io-a");
            Thread second = new Thread(task, "io-b");
            first.setDaemon(true);
            second.setDaemon(true);
            first.start();
            second.start();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            first.join(10_000);
            second.join(10_000);
            if (first.isAlive()) {
                first.interrupt();
            }
            if (second.isAlive()) {
                second.interrupt();
            }
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertNull(failure.get());
        }
        assertEquals(400, output.texts().size());
        assertTrue(output.texts().stream().allMatch(text -> text.equals("A") || text.equals("B")));
        assertEquals(400, output.flushes);
    }

    @Test
    void separateLoadedArtifactsKeepIntrinsicIoEnvironmentsIndependent() throws Throwable {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("phase20.lyra", "import std->io")));
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
        LoadedArtifact first = LyraRuntime.load(result.artifact(), new LoadOptions(
                new RuntimeIoEnvironment(new ByteArrayInputStream(new byte[0]), firstOutput,
                        new ByteArrayOutputStream(), StandardCharsets.UTF_8)));
        LoadedArtifact second = LyraRuntime.load(result.artifact(), new LoadOptions(
                new RuntimeIoEnvironment(new ByteArrayInputStream(new byte[0]), secondOutput,
                        new ByteArrayOutputStream(), StandardCharsets.UTF_8)));
        ModuleHandle firstIo = first.instantiate(ModuleId.uri(URI.create("lyra:intrinsic/std/io")));
        ModuleHandle secondIo = second.instantiate(ModuleId.uri(URI.create("lyra:intrinsic/std/io")));
        try {
            firstIo.export("print", "Fn<String;Unit>").methodHandle().invoke("first");
            secondIo.export("print", "Fn<String;Unit>").methodHandle().invoke("second");
        } finally {
            firstIo.close();
            secondIo.close();
            first.close();
            second.close();
        }
        assertEquals("first", firstOutput.toString(StandardCharsets.UTF_8));
        assertEquals("second", secondOutput.toString(StandardCharsets.UTF_8));
    }

    @Test
    void malformedInputAndStreamFailuresBecomeSourceMappedLyraIoFailures() throws Throwable {
        try (Fixture malformed = fixture(READ_SOURCE,
                new ByteArrayInputStream(new byte[] {(byte) 0xc3}),
                new ByteArrayOutputStream(), new ByteArrayOutputStream())) {
            LyraIoException failure = assertThrows(LyraIoException.class,
                    () -> malformed.call("read", "Fn<;@nilString>"));
            assertEquals("LYR-IO", failure.code());
            assertTrue(failure.javaCause().isPresent());
            assertFalse(failure.frames().isEmpty());
        }

        try (Fixture failingInput = fixture(READ_SOURCE, new FailingInputStream(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream())) {
            LyraIoException failure = assertThrows(LyraIoException.class,
                    () -> failingInput.call("read", "Fn<;@nilString>"));
            assertEquals("LYR-IO", failure.code());
            assertEquals("input failed", failure.javaCause().orElseThrow().getMessage());
        }

        try (Fixture failingOutput = fixture(PRINT_SOURCE, new ByteArrayInputStream(new byte[0]),
                new FailingOutputStream(), new ByteArrayOutputStream())) {
            LyraIoException failure = assertThrows(LyraIoException.class,
                    () -> failingOutput.call("write", "Fn<String;Unit>", "value"));
            assertEquals("LYR-IO", failure.code());
            assertEquals("output failed", failure.javaCause().orElseThrow().getMessage());
        }

        RecordingOutputStream malformedOutput = new RecordingOutputStream();
        try (Fixture fixture = fixture(PRINT_SOURCE, new ByteArrayInputStream(new byte[0]),
                malformedOutput, new ByteArrayOutputStream())) {
            LyraIoException failure = assertThrows(LyraIoException.class,
                    () -> fixture.call("write", "Fn<String;Unit>", new String(new char[] {(char) 0xd800})));
            assertEquals("LYR-IO", failure.code());
            assertTrue(failure.frames().stream().anyMatch(frame -> frame.functionName().equals("write")));
            assertTrue(malformedOutput.writes.isEmpty());
            assertEquals(0, malformedOutput.flushes);
        }
    }

    @Test
    void interruptedReadPreservesTheInterruptStatus() throws Throwable {
        try (Fixture fixture = fixture(READ_SOURCE,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), new ByteArrayOutputStream())) {
            Thread.currentThread().interrupt();
            try {
                LyraIoException failure = assertThrows(LyraIoException.class,
                        () -> fixture.call("read", "Fn<;@nilString>"));
                assertEquals("LYR-IO", failure.code());
                assertTrue(failure.javaCause().isPresent());
            } finally {
                assertTrue(Thread.interrupted(), "the I/O boundary must preserve interruption");
            }
        }
    }

    @Test
    void ioAuthorityChecksOwnerAndClosedLifecycleBeforeStreamAccess() throws Throwable {
        RecordingOutputStream output = new RecordingOutputStream();
        Fixture fixture = fixture(PRINT_SOURCE, new ByteArrayInputStream(new byte[0]),
                output, new RecordingOutputStream());
        try {
            ExportHandle handle = fixture.module.export("write", "Fn<String;Unit>");
            AtomicReference<Throwable> wrongThreadFailure = new AtomicReference<>();
            Thread wrongThread = new Thread(() -> {
                try {
                    handle.methodHandle().invoke("wrong-thread");
                } catch (Throwable failure) {
                    wrongThreadFailure.set(failure);
                }
            });
            wrongThread.start();
            wrongThread.join();
            assertInstanceOf(LyraThreadException.class, wrongThreadFailure.get());
            assertTrue(output.writes.isEmpty());

            fixture.module.close();
            assertThrows(LyraClosedException.class,
                    () -> handle.methodHandle().invoke("closed"));
            assertTrue(output.writes.isEmpty());
        } finally {
            if (!fixture.module.isClosed()) {
                fixture.module.close();
            }
            fixture.loaded.close();
        }
    }

    @Test
    void intrinsicFunctionValuesAreStableAuthenticatedAdapters() throws Throwable {
        RecordingOutputStream output = new RecordingOutputStream();
        try (Fixture fixture = fixture("import std->io let value = 1",
                new ByteArrayInputStream(new byte[0]), output, new ByteArrayOutputStream())) {
            ModuleHandle io = fixture.loaded.instantiate(
                    ModuleId.uri(URI.create("lyra:intrinsic/std/io")));
            try {
                ExportHandle print = io.export("print", "Fn<String;Unit>");
                Object first = print.functionValue();
                assertNotNull(first);
                assertSame(first, print.functionValue());
                invokeFunctionValue(first, "adapter");
            } finally {
                io.close();
            }
        }
        assertEquals(List.of("adapter"), output.texts());
    }

    private static void invokeFunctionValue(Object function, String value)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method invoke = function.getClass().getInterfaces()[0]
                .getMethod("invoke", String.class);
        invoke.invoke(function, value);
    }

    private static Fixture fixture(String source, InputStream input,
                                   OutputStream output, OutputStream error) {
        return fixture(source, input, output, error, StandardCharsets.UTF_8);
    }

    private static Fixture fixture(String source, InputStream input,
                                   OutputStream output, OutputStream error,
                                   Charset charset) {
        CompileResult.Success result = assertInstanceOf(CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source("phase20.lyra", source)));
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                input, output, error, charset);
        LoadedArtifact loaded = LyraRuntime.load(result.artifact(), new LoadOptions(environment));
        return new Fixture(loaded, loaded.instantiate());
    }

    private static final class Fixture implements AutoCloseable {
        private final LoadedArtifact loaded;
        private final ModuleHandle module;

        private Fixture(LoadedArtifact loaded, ModuleHandle module) {
            this.loaded = loaded;
            this.module = module;
        }

        private Object call(String name, String signature, Object... arguments)
                throws Throwable {
            return module.export(name, signature).methodHandle().invokeWithArguments(arguments);
        }

        @Override
        public void close() {
            module.close();
            loaded.close();
        }
    }

    private static class RecordingOutputStream extends OutputStream {
        private final List<byte[]> writes = new ArrayList<>();
        private int flushes;
        private boolean closed;

        @Override
        public void write(int value) {
            writes.add(new byte[] {(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writes.add(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closed = true;
        }

        private List<String> texts() {
            return writes.stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList();
        }
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("input failed");
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class FlushFailingOutputStream extends OutputStream {
        @Override
        public void write(int value) {
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("flush failed");
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("output failed");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            throw new IOException("output failed");
        }
    }
}
