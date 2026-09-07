package io.mindspice.lyra.runtime;

import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused runtime proof for immutable link entries and explicit root retention. */
final class SessionRetentionTest {
    @Test
    void constructedLinkTableRetainsTheRealProducerAfterWorkspaceUnregister() {
        var first = compile("retention-first.lyra", "let @mut count :I32 = 7");
        var next = compile("retention-next.lyra", "count", first.stagedSnapshot());
        var requirement = requirement(first, "count");

        try (var domain = new SessionStorageDomain();
             var firstArtifact = load(domain, first);
             var producer = firstArtifact.instantiate()) {
            var binding = domain.register(producer, requirement);
            domain.commit(0, List.of(binding));
            var linkage = domain.link(next.artifact(), 1, List.of(requirement), List.of(binding));

            assertSame(producer, binding.producer());
            assertEquals(MethodType.methodType(int.class), binding.readerType());
            assertEquals(MethodType.methodType(void.class, int.class),
                    binding.writerType().orElseThrow());
            assertFalse(binding.functionType().isPresent());
            var entry = linkage.entries().get(requirement.id());
            assertSame(producer, entry.producer());
            assertEquals(requirement.id(), entry.declarationIdentity());
            assertEquals(requirement.storageIdentity(), entry.storageIdentity());
            assertEquals(requirement.logicalType(), entry.logicalType());

            // Removing the workspace name must not mutate the already built
            // link table or retarget it to a later registration.
            domain.unregister(requirement.id());
            try (var nextArtifact = LyraRuntime.loadSubmission(
                    next.artifact(), LoadOptions.defaults(), linkage);
                 var consumer = nextArtifact.instantiate()) {
                assertEquals(7, LyraRuntime.readSubmissionResult(consumer, PrimitiveType.I32));
            }

            producer.close();
            assertThrows(LyraClosedException.class,
                    () -> linkage.validate(next.artifact().metadata()));
        }
    }

    @Test
    void standaloneResetClosesOwnedProducersButNotRootBorrowedProducers() {
        var compiled = compile("retention-owned.lyra", "let @mut count :I32 = 1");
        var requirement = requirement(compiled, "count");
        try (var domain = new SessionStorageDomain();
             var loaded = load(domain, compiled);
             var producer = loaded.instantiate()) {
            var binding = domain.register(producer, requirement);
            domain.commit(0, List.of(binding));
            domain.reset();
            assertTrue(producer.isClosed());
        }

        try (var root = new SessionStorageDomain.RootLifetime()) {
            var domain = new SessionStorageDomain(root);
            var loaded = load(domain, compiled);
            var producer = loaded.instantiate();
            var binding = domain.register(producer, requirement,
                    SessionStorageDomain.RetentionKind.BORROWED);
            domain.commit(0, List.of(binding));
            domain.reset();
            assertFalse(producer.isClosed());
            root.close();
            assertFalse(producer.isClosed());
            producer.close();
            loaded.close();
        }
    }

    @Test
    void functionLinksCarryExactReaderWriterAndInvokeMethodTypes() {
        var first = compile("retention-function.lyra",
                "let @mut apply :Fn<I32;I32> = (=> |n| (+ n 1))");
        var requirement = requirement(first, "apply");

        try (var domain = new SessionStorageDomain();
             var loaded = load(domain, first);
             var producer = loaded.instantiate()) {
            var binding = domain.register(producer, requirement);
            assertEquals(MethodType.methodType(binding.readerType().returnType()), binding.readerType());
            assertEquals(MethodType.methodType(void.class, binding.readerType().returnType()),
                    binding.writerType().orElseThrow());
            assertEquals(MethodType.methodType(int.class, int.class),
                    binding.functionType().orElseThrow());
            domain.commit(0, List.of(binding));
        }
    }

    @Test
    void failedAttemptDoesNotExposeAnUninitializedStorageLocation() {
        var failed = compile("retention-failed.lyra",
                "let @mut ready :I32 = 1 let zero :I32 = 0 (% 1 zero) let @mut later :I32 = 2");
        var requirement = requirement(failed, "later");
        try (var domain = new SessionStorageDomain();
             var loaded = load(domain, failed);
             var module = LyraRuntime.prepareSubmission(loaded)) {
            assertThrows(LyraArithmeticException.class, () -> LyraRuntime.executeSubmission(module));
            assertThrows(LyraInitializationException.class,
                    () -> domain.register(module, requirement));
            assertThrows(LyraInitializationException.class,
                    () -> domain.register(module, requirement(failed, "ready")),
                    "an initialized binding from a failed submission must not become publication authority");
        }
    }

    @Test
    void failedSubmissionRetainsCompletedWritesAndEscapedClosureButCannotPublish() throws Throwable {
        var first = compile("retention-escape-owner.lyra", """
                let @pub @mut selected :Fn<;I32> = (=> || 0)
                let @mut count :I32 = 0
                let @pub read :Fn<;I32> = (=> || count)
                """);
        var failed = compile("retention-escape-failed.lyra", """
                let @mut ready :I32 = 41
                selected := (=> :I32 || { ready := (+ ready 1) ready })
                count := 7
                let zero :I32 = 0
                (% 1 zero)
                let @mut later :I32 = 99
                """, first.stagedSnapshot());
        var selected = requirement(first, "selected");
        var count = requirement(first, "count");
        var read = requirement(first, "read");
        var signature = LyraSignature.parse("Fn<;I32>");
        try (var domain = new SessionStorageDomain();
             var firstArtifact = load(domain, first);
             var original = firstArtifact.instantiate()) {
            var selectedBinding = domain.register(original, selected);
            var countBinding = domain.register(original, count);
            var readBinding = domain.register(original, read);
            domain.commit(0, List.of(selectedBinding, countBinding, readBinding));
            try (var failedArtifact = LyraRuntime.loadSubmission(failed.artifact(), LoadOptions.defaults(),
                    domain.link(failed.artifact(), 1, List.of(selected, count, read),
                            List.of(selectedBinding, countBinding, readBinding)));
                 var attempt = LyraRuntime.prepareSubmission(failedArtifact)) {
                var retained = domain.retainAttempted(attempt);
                assertThrows(LyraArithmeticException.class, () -> LyraRuntime.executeSubmission(attempt));
                assertFalse(attempt.isClosed());
                assertTrue(retained.isActive());
                assertFalse(retained.isUsable());
                assertEquals(7, (int) original.export("read", signature).handle().invokeExact());
                var escaped = (LyraClosure) original.export("selected", signature).functionValue();
                assertTrue(escaped.isValid());
                assertEquals(42, (int) original.export("selected", signature).handle().invokeExact());
                for (var kind : SessionStorageDomain.RetentionKind.values()) {
                    assertThrows(LyraInitializationException.class,
                            () -> domain.register(attempt, requirement(failed, "ready"), kind));
                }
                assertThrows(LyraInitializationException.class,
                        () -> domain.register(attempt, requirement(failed, "later")));
                assertThrows(LyraLifecycleException.class, () -> LyraRuntime.executeSubmission(attempt));
                assertThrows(LyraLifecycleException.class,
                        () -> LyraRuntime.readSubmissionResult(attempt, PrimitiveType.UNIT));
                assertEquals(43, (int) original.export("selected", signature).handle().invokeExact(),
                        "rejected registration and retry must not retire escaped values");
                domain.reset();
                assertFalse(escaped.isValid());
            }
        }
    }

    @Test
    void cancellationAfterInitializationCannotPublishButRetainsTheProducer() throws Throwable {
        var cancelled = compile("retention-cancelled.lyra", """
                let @mut ready :I32 = 42
                let @pub read :Fn<;I32> = (=> || ready)
                let spin :Fn<;I32> = (=> || (spin))
                (spin)
                let @mut later :I32 = 99
                """);
        try (var domain = new SessionStorageDomain();
             var loaded = load(domain, cancelled);
             var module = LyraRuntime.prepareSubmission(loaded)) {
            Thread owner = Thread.currentThread();
            var controlFailure = new AtomicReference<Throwable>();
            try (var lease = domain.beginEvaluation()) {
                var cancel = new Thread(() -> {
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (System.nanoTime() < deadline) {
                            if (Arrays.stream(owner.getStackTrace()).anyMatch(frame ->
                                    frame.getClassName().startsWith(cancelled.artifact().metadata().javaPackage() + ".")
                                            && frame.getMethodName().equals("invoke"))) {
                                return;
                            }
                            Thread.sleep(1);
                        }
                        throw new AssertionError("submission did not enter its generated loop");
                    } catch (Throwable failure) {
                        controlFailure.set(failure);
                    } finally {
                        lease.requestCancellation();
                    }
                }, "retention-cancel");
                cancel.start();
                try {
                    assertThrows(LyraCancellationException.class, () -> LyraRuntime.executeSubmission(module));
                } finally {
                    cancel.join(11000);
                }
                assertFalse(cancel.isAlive());
                assertNull(controlFailure.get());
            }
            assertFalse(module.isClosed());
            assertEquals(42, (int) module.export("read", LyraSignature.parse("Fn<;I32>")).handle().invokeExact());
            for (String name : List.of("ready", "read", "later")) {
                assertThrows(LyraInitializationException.class,
                        () -> domain.register(module, requirement(cancelled, name)));
            }
            assertThrows(LyraLifecycleException.class, () -> LyraRuntime.executeSubmission(module));
            assertThrows(LyraLifecycleException.class,
                    () -> LyraRuntime.readSubmissionResult(module, PrimitiveType.UNIT));
        }
    }

    @Test
    void errorsEscapeUnchangedWithoutAuthorizingEarlierInitializedStorage() throws Throwable {
        var first = compile("retention-error-owner.lyra",
                "let @pub @mut trigger :Fn<;I32> = (=> || 0)");
        var failed = compile("retention-error-failed.lyra", """
                let @mut ready :I32 = 42
                let @pub read :Fn<;I32> = (=> || ready)
                (trigger)
                let @mut later :I32 = 99
                """, first.stagedSnapshot());
        var required = requirement(first, "trigger");
        var signature = LyraSignature.parse("Fn<;I32>");
        try (var domain = new SessionStorageDomain();
             var originalArtifact = load(domain, first);
             var original = originalArtifact.instantiate()) {
            var binding = domain.register(original, required);
            domain.commit(0, List.of(binding));
            var originalClosure = (LyraClosure) original.export("trigger", signature).functionValue();
            var writer = LyraRuntime.submissionStorageAccessor(original, required, true)
                    .asType(MethodType.methodType(void.class, Object.class));
            // Synthetic instances exercise native Error propagation without
            // exhausting memory, stopping a thread, or breaking artifact integrity.
            for (Error error : List.of(new AssertionError("injected"),
                    new OutOfMemoryError("injected"), new LinkageError("injected"))) {
                Object throwing = errorClosure(binding.readerType().returnType(), originalClosure.authority(), error);
                writer.invokeExact(throwing);
                try (var loaded = LyraRuntime.loadSubmission(failed.artifact(), LoadOptions.defaults(),
                        domain.link(failed.artifact(), 1, List.of(required), List.of(binding)));
                     var module = LyraRuntime.prepareSubmission(loaded)) {
                    assertSame(error, assertThrows(Error.class, () -> LyraRuntime.executeSubmission(module)));
                    assertFalse(module.isClosed());
                    assertEquals(42, (int) module.export("read", signature).handle().invokeExact());
                    assertThrows(LyraInitializationException.class,
                            () -> domain.register(module, requirement(failed, "ready")));
                    assertThrows(LyraInitializationException.class,
                            () -> domain.register(module, requirement(failed, "later")));
                    assertThrows(LyraLifecycleException.class, () -> LyraRuntime.executeSubmission(module));
                }
            }
        }
    }

    /** Test-only authenticated, exactly typed closure for deterministic host Error injection. */
    private static LyraClosure errorClosure(Class<?> functionType, LyraClosureAuthority authority,
                                           Error failure) throws Throwable {
        String name = "lyra.test.RetentionErrorClosure";
        var type = ClassDesc.of(name);
        var base = ClassDesc.of(LyraClosure.class.getName());
        var authorityType = ClassDesc.of(LyraClosureAuthority.class.getName());
        var signatureType = ClassDesc.of(LyraSignature.class.getName());
        var errorType = ClassDesc.of(Error.class.getName());
        byte[] bytes = ClassFile.of().build(type, builder -> builder
                .withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(base)
                .withInterfaceSymbols(ClassDesc.of(functionType.getName()))
                .withField("failure", errorType, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
                .withMethodBody("<init>", MethodTypeDesc.of(CD_void, authorityType, errorType),
                        ClassFile.ACC_PUBLIC, code -> code.aload(0).aload(1).ldc("Fn<;I32>")
                                .invokestatic(signatureType, "parse", MethodTypeDesc.of(signatureType,
                                        ClassDesc.of(String.class.getName())))
                                .invokespecial(base, "<init>", MethodTypeDesc.of(CD_void, authorityType, signatureType))
                                .aload(0).aload(2).putfield(type, "failure", errorType).return_())
                .withMethodBody("invoke", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).invokevirtual(base, "checkInvocation", MethodTypeDesc.of(CD_void))
                                .aload(0).getfield(type, "failure", errorType).athrow()));
        Class<?> throwing = new ClassLoader(functionType.getClassLoader()) {
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        var constructor = MethodHandles.publicLookup().findConstructor(throwing,
                        MethodType.methodType(void.class, LyraClosureAuthority.class, Error.class))
                .asType(MethodType.methodType(LyraClosure.class, LyraClosureAuthority.class, Error.class));
        return (LyraClosure) constructor.invokeExact(authority, failure);
    }

    @Test
    void rootRetainsFailedProducerAcrossWorkspaceReopenWithoutPublicationAuthority() throws Throwable {
        var failed = compile("retention-root-failed.lyra", """
                let ready :I32 = 42
                let @pub read :Fn<;I32> = (=> || ready)
                let zero :I32 = 0
                (% 1 zero)
                """);
        try (var root = new SessionStorageDomain.RootLifetime();
             var domain = new SessionStorageDomain(root);
             var loaded = load(domain, failed);
             var module = LyraRuntime.prepareSubmission(loaded)) {
            var retained = domain.retainAttempted(module);
            assertThrows(LyraArithmeticException.class, () -> LyraRuntime.executeSubmission(module));
            var read = module.export("read", LyraSignature.parse("Fn<;I32>"));
            var escaped = (LyraClosure) read.functionValue();
            domain.reset();
            domain.close();
            assertTrue(retained.isActive());
            assertFalse(retained.isUsable());
            assertTrue(escaped.isValid());
            try (var reopened = new SessionStorageDomain(root)) {
                assertEquals(42, (int) read.handle().invokeExact());
                assertThrows(LyraInitializationException.class,
                        () -> reopened.register(module, requirement(failed, "ready")));
                assertTrue(escaped.isValid());
                root.close();
                assertFalse(escaped.isValid());
                assertThrows(LyraClosedException.class, () -> {
                    int ignored = (int) read.handle().invokeExact();
                });
            }
        }
    }

    @Test
    void rootLifetimeReusesStructuralDomainAcrossWorkspaceCloseAndReopen() {
        var first = compile("root-first.lyra", "let @mut pair :Tuple<I32> = Tuple[11] pair");
        var next = compile("root-next.lyra", "pair", first.stagedSnapshot());
        var requirement = requirement(first, "pair");
        var type = LyraType.parse("Tuple<I32>");

        try (var root = new SessionStorageDomain.RootLifetime()) {
            Object sourceHolder = new Object();
            Object summaryHolder = new Object();
            root.anchorSource(sourceHolder);
            root.anchorSummary(summaryHolder);
            assertTrue(root.anchoredHolderCount() >= 3);
            var domain = new SessionStorageDomain(root);
            var firstArtifact = load(domain, first);
            var producer = firstArtifact.instantiate();
            var binding = domain.register(producer, requirement,
                    SessionStorageDomain.RetentionKind.BORROWED);
            domain.commit(0, List.of(binding));
            var linkage = domain.link(next.artifact(), 1, List.of(requirement), List.of(binding));
            Object original = LyraRuntime.readSubmissionResult(producer, type);
            Class<?> tupleClass = original.getClass();

            domain.close();
            assertTrue(binding.retention().isActive());
            try (var reopened = new SessionStorageDomain(root);
                 var nextArtifact = LyraRuntime.loadSubmission(next.artifact(),
                         LoadOptions.defaults(), linkage);
                 var consumer = nextArtifact.instantiate()) {
                reopened.reset();
                Object retained = LyraRuntime.readSubmissionResult(consumer, type);
                assertSame(original, retained);
                assertSame(tupleClass, retained.getClass());

                root.close();
                assertEquals(0, root.anchoredHolderCount());
                assertThrows(LyraClosedException.class,
                        () -> LyraRuntime.readSubmissionResult(consumer, type));
            } finally {
                producer.close();
                firstArtifact.close();
            }
        }
    }

    @Test
    void rootLifetimeKeepsRegisteredLyraClosureAuthorityAcrossWorkspaceReopen() throws Throwable {
        var producerSource = compile("root-callable.lyra",
                "let @mut add :Fn<I32;I32> = (=> |n| (+ n 1)) add");
        var consumerSource = compile("root-consumer.lyra",
                "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f n| (f n))");
        var requirement = requirement(producerSource, "add");
        var signature = LyraSignature.parse("Fn<I32;I32>");
        var applySignature = LyraSignature.parse("Fn<Fn<I32;I32>,I32;I32>");

        try (var root = new SessionStorageDomain.RootLifetime()) {
            var domain = new SessionStorageDomain(root);
            var producerArtifact = load(domain, producerSource);
            var consumerArtifact = load(domain, consumerSource);
            var producer = producerArtifact.instantiate();
            var consumer = consumerArtifact.instantiate();
            Object add = LyraRuntime.readSubmissionResult(producer, signature.asFunctionType());
            var binding = domain.register(producer, requirement,
                    SessionStorageDomain.RetentionKind.BORROWED);
            domain.commit(0, List.of(binding));
            var apply = consumer.export("apply", applySignature).handle()
                    .asType(MethodType.methodType(int.class, Object.class, int.class));

            domain.close();
            try (var reopened = new SessionStorageDomain(root)) {
                assertEquals(42, (int) apply.invokeExact(add, 41));
                root.close();
                assertThrows(LyraClosedException.class, () -> {
                    int ignored = (int) apply.invokeExact(add, 41);
                });
            } finally {
                producer.close();
                consumer.close();
                producerArtifact.close();
                consumerArtifact.close();
            }
        }
    }

    @Test
    void attemptedRetentionAndUninitializedStorageNeverBecomeLinkAuthority() {
        var first = compile("retention-uninitialized.lyra", "let @mut ready :I32 = 42");
        var requirement = requirement(first, "ready");
        try (var domain = new SessionStorageDomain();
             var loaded = load(domain, first);
             var module = LyraRuntime.prepareSubmission(loaded)) {
            var attempted = domain.retainAttempted(module);
            assertFalse(attempted.isUsable());
            assertThrows(LyraInitializationException.class,
                    () -> domain.register(module, requirement));
            assertFalse(attempted.isUsable());

            LyraRuntime.executeSubmission(module);
            assertThrows(LyraLifecycleException.class, () -> LyraRuntime.executeSubmission(module),
                    "a rejected duplicate execution must not revoke successful publication eligibility");
            var initialized = domain.register(module, requirement);
            assertTrue(initialized.retention().isUsable());
            domain.commit(0, List.of(initialized));
        }
    }

    private static SessionStorageDomain.Requirement requirement(
            SessionCompileResult.Success result, String name) {
        var binding = result.stagedSnapshot().binding(name).orElseThrow();
        return new SessionStorageDomain.Requirement(
                binding.declarationId().ordinal(),
                binding.storageIdentity().map(value -> value.ordinal()).orElse(-1L),
                binding.name(), binding.type().canonicalSpelling(), binding.allowsRebinding());
    }

    private static SessionCompileResult.Success compile(String label, String source) {
        return compile(label, source, SessionSnapshot.empty());
    }

    private static SessionCompileResult.Success compile(
            String label, String source, SessionSnapshot snapshot) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(label, source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }

    private static LoadedArtifact load(
            SessionStorageDomain domain, SessionCompileResult.Success result) {
        return LyraRuntime.loadSubmission(result.artifact(), LoadOptions.defaults(),
                domain.link(result.artifact(), 0, List.of(), List.of()));
    }

}
