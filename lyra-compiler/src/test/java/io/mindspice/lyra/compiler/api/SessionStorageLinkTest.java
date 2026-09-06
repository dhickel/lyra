package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionStorageLinkTest {
    @Test
    void identicalNamesTypesAndOrdinalsDoNotAuthorizeForeignStorage() throws Exception {
        var first = compile("first.lyra", "let @mut count :I32 = 1", SessionSnapshot.empty());
        var next = compile("next.lyra", "count := 2 count", first.stagedSnapshot());
        var requirement = requirement(first);
        try (var left = new SessionStorageDomain(); var right = new SessionStorageDomain();
             var a = LyraRuntime.load(first.artifact()); var b = LyraRuntime.load(first.artifact());
             var ma = a.instantiate(); var mb = b.instantiate()) {
            var own = left.register(ma, requirement);
            var foreign = right.register(mb, requirement);
            left.commit(0, List.of(own)); right.commit(0, List.of(foreign));
            assertThrows(LyraLinkException.class, () -> left.link(next.artifact(), 1, List.of(requirement), List.of(foreign)));
            assertThrows(LyraLinkException.class, () -> left.link(next.artifact(), 0, List.of(requirement), List.of(own)));
            var wrongStorage = new SessionStorageDomain.Requirement(requirement.id(), requirement.storageIdentity() + 1, "count", "I32", true);
            assertThrows(LyraLinkException.class, () -> left.link(next.artifact(), 1, List.of(wrongStorage), List.of(own)));
            var wrongType = new SessionStorageDomain.Requirement(requirement.id(), requirement.storageIdentity(), "count", "U32", true);
            assertThrows(LyraLinkException.class, () -> left.link(next.artifact(), 1, List.of(wrongType), List.of(own)));
            assertThrows(LyraLinkException.class, () -> left.register(ma, wrongType));
            var metadata = first.stagedSnapshot().binding("count").orElseThrow();
            var forged = new io.mindspice.lyra.compiler.session.ExternalBinding(metadata.name(), metadata.declarationId(), metadata.contract(),
                    metadata.visibility(), metadata.assignmentAuthority(), java.util.Optional.of(new io.mindspice.lyra.compiler.session.StorageIdentity(999)), metadata.origin());
            var altered = compile("altered.lyra", "import std->io\nio->::println[\"must-not-run\"]\ncount := 8 count", first.stagedSnapshot().withBinding(forged));
            var output = new java.io.ByteArrayOutputStream();
            var io = new RuntimeIoEnvironment(java.io.InputStream.nullInputStream(), output, output, java.nio.charset.StandardCharsets.UTF_8);
            try (var loaded = LyraRuntime.loadSubmission(altered.artifact(), LoadOptions.defaults().withIoEnvironment(io),
                    left.link(altered.artifact(), 1, List.of(requirement), List.of(own)))) {
                assertThrows(LyraLinkException.class, loaded::instantiate,
                        "generated requirements must also match before source forms execute");
                assertEquals("", output.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
            var linkage = left.link(next.artifact(), 1, List.of(requirement), List.of(own));
            assertThrows(LyraLinkException.class, () -> LyraRuntime.loadSubmission(first.artifact(), LoadOptions.defaults(), linkage));
            var wrongThread = new AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try { left.link(next.artifact(), 1, List.of(requirement), List.of(own)); }
                catch (Throwable failure) { wrongThread.set(failure); }
            });
            thread.start(); thread.join();
            assertInstanceOf(LyraThreadException.class, wrongThread.get());
            try (var loaded = LyraRuntime.loadSubmission(next.artifact(), LoadOptions.defaults(), linkage);
                 var module = loaded.instantiate()) {
                assertEquals(2, LyraRuntime.readSubmissionResult(module, PrimitiveType.I32));
                assertThrows(LyraLinkException.class, () -> LyraRuntime.readSubmissionResult(module, PrimitiveType.U32));
                left.commit(1, List.of());
                assertThrows(LyraLinkException.class, loaded::instantiate, "a previously loaded entry point cannot execute against a stale revision");
            }
            var read = compile("read.lyra", "count", next.stagedSnapshot());
            try (var loaded = LyraRuntime.loadSubmission(read.artifact(), LoadOptions.defaults(),
                    left.link(read.artifact(), 2, List.of(requirement), List.of(own)));
                 var module = loaded.instantiate()) {
                assertEquals(2, LyraRuntime.readSubmissionResult(module, PrimitiveType.I32));
            }
            ma.close();
            assertThrows(LyraClosedException.class, () -> left.link(read.artifact(), 2, List.of(requirement), List.of(own)));
        }
    }

    @Test
    void laterScalarSubmissionsDoNotReplayTheOriginalInitializerOrItsOutput() {
        var first = compile("effects.lyra", "import std->io\nlet @mut count :I32 = 1\nio->::println[\"initialized\"]", SessionSnapshot.empty());
        var next = compile("assign.lyra", "count := 2 count", first.stagedSnapshot());
        var read = compile("read.lyra", "count", next.stagedSnapshot());
        var output = new java.io.ByteArrayOutputStream();
        var io = new RuntimeIoEnvironment(java.io.InputStream.nullInputStream(), output, output, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        try (var domain = new SessionStorageDomain();
             var loaded = LyraRuntime.load(first.artifact(), LoadOptions.defaults().withIoEnvironment(io));
             var original = loaded.instantiate()) {
            var required = requirement(first);
            var binding = domain.register(original, required);
            domain.commit(0, List.of(binding));
            long revision = 1;
            for (var submission : List.of(next, read)) {
                try (var generation = LyraRuntime.loadSubmission(submission.artifact(), LoadOptions.defaults(),
                        domain.link(submission.artifact(), revision, List.of(required), List.of(binding)));
                     var module = generation.instantiate()) {
                    assertEquals(2, LyraRuntime.readSubmissionResult(module, PrimitiveType.I32));
                    domain.commit(revision++, List.of());
                }
            }
            assertEquals("initialized\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void resetRetiresCapabilitiesAndUninitializedMetadataNeverGrantsAccess() {
        var first = compile("first.lyra", "let @mut count :I32 = 1", SessionSnapshot.empty());
        var next = compile("next.lyra", "count", first.stagedSnapshot());
        try (var domain = new SessionStorageDomain(); var loaded = LyraRuntime.load(first.artifact()); var module = loaded.instantiate()) {
            var binding = domain.register(module, requirement(first));
            assertThrows(LyraLinkException.class, () -> domain.link(next.artifact(), 0, List.of(requirement(first)), List.of(binding)),
                    "registration is staged until namespace publication");
            domain.commit(0, List.of(binding));
            domain.reset();
            assertThrows(LyraLinkException.class, () -> domain.link(next.artifact(), 1, List.of(requirement(first)), List.of(binding)));
        }
        try (var loaded = LyraRuntime.load(next.artifact())) {
            assertThrows(LyraLinkException.class, loaded::instantiate);
        }
    }

    private static SessionStorageDomain.Requirement requirement(SessionCompileResult.Success first) {
        var binding = first.stagedSnapshot().binding("count").orElseThrow();
        return new SessionStorageDomain.Requirement(binding.declarationId().ordinal(), binding.storageIdentity().orElseThrow().ordinal(),
                binding.name(), binding.type().canonicalSpelling(), true);
    }
    private static SessionCompileResult.Success compile(String label, String source, SessionSnapshot snapshot) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(label, source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }
}
