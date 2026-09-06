package io.mindspice.lyra.runtime;

import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Package access lets tests distinguish authentication from a prior JVM cast failure. */
class SessionClosureAuthorityTest {
    private static final LyraSignature SIGNATURE = LyraSignature.parse("Fn<I32;I32>");

    @Test
    void sessionAuthenticationIsSeparateFromArtifactIdentityAndExactSignature() {
        var first = compile("authority-first.lyra", "");
        var second = compile("authority-second.lyra", "");
        var imported = compile("authority-imported.lyra", "import std->io\n");
        try (var domain = new SessionStorageDomain(); var other = new SessionStorageDomain();
             var a = load(domain, first); var b = load(domain, second); var c = load(other, first);
             var d = LyraRuntime.load(first.artifact()); var e = load(domain, imported);
             var ma = a.instantiate(); var mb = b.instantiate(); var mc = c.instantiate();
             var md = d.instantiate(); var me = e.instantiate()) {
            LyraClosure left = closure(ma), right = closure(mb);
            assertFalse(left.authority().sameArtifact(right.authority()), "the bridge must not merge artifact keys");
            assertSame(right, LyraClosureSupport.requireAuthenticated(right, left.authority(), SIGNATURE));
            assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                    closure(mc), left.authority(), SIGNATURE), "another session must fail even before a JVM type cast");
            assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                    closure(md), left.authority(), SIGNATURE), "ordinary artifact loading never opts in");
            assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                    closure(me), left.authority(), SIGNATURE), "imported graph authority is not certified");
            assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                    right, closure(me).authority(), SIGNATURE), "the consumer must also be source-local");
            assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                    right, left.authority(), LyraSignature.parse("Fn<U32;U32>")),
                    "equal primitive JVM descriptors do not authorize different logical signatures");
            domain.close();
            assertFalse(left.isValid());
            assertFalse(right.isValid());
            assertThrows(LyraClosedException.class, right::checkInvocation);
        }
    }

    @Test
    void linkagePinsTheWholeArtifactContractNotJustAReusableId() {
        var local = compile("inventory-local.lyra", "");
        var imported = compile("inventory-imported.lyra", "import std->io\n");
        var sameId = ArtifactMetadataReader.read(local.artifact().metadata().canonicalJson()
                .replace(local.artifact().metadata().artifactId(), imported.artifact().metadata().artifactId()));
        assertEquals(imported.artifact().metadata().artifactId(), sameId.artifactId());
        assertEquals(1, sameId.modules().size());
        assertNotEquals(imported.artifact().metadata().artifactRevision(), sameId.artifactRevision());
        try (var domain = new SessionStorageDomain()) {
            var importedLink = domain.link(imported.artifact(), 0, List.of(), List.of());
            var localLink = domain.link(local.artifact(), 0, List.of(), List.of());
            importedLink.validate(imported.artifact().metadata());
            assertFalse(importedLink.authenticates(localLink));
            assertThrows(LyraLinkException.class, () -> importedLink.validate(sameId));
            assertFalse(importedLink.authenticates(localLink),
                    "a rejected metadata substitution cannot upgrade imported authority");
            importedLink.validate(imported.artifact().metadata());
            try (var loaded = LyraRuntime.loadSubmission(imported.artifact(), LoadOptions.defaults(), importedLink);
                 var module = loaded.instantiate()) {
                assertTrue(closure(module).isValid(), "rejected substitution must not damage original linkage");
            }
        }
    }

    @Test
    void freshConsumerRejectsRetiredProducerBeforeAnyPhysicalCast() {
        var first = compile("epoch-first.lyra", "");
        var second = compile("epoch-second.lyra", "");
        try (var domain = new SessionStorageDomain(); var a = load(domain, first); var ma = a.instantiate()) {
            LyraClosure retired = closure(ma);
            domain.reset();
            try (var b = load(domain, second); var mb = b.instantiate()) {
                LyraClosure current = closure(mb);
                assertTrue(current.isValid());
                assertFalse(retired.isValid());
                assertThrows(LyraLinkException.class, () -> LyraClosureSupport.requireAuthenticated(
                        retired, current.authority(), SIGNATURE));
                assertTrue(current.isValid());
            }
        }
    }

    @Test
    void failedOrInitializingProducerCannotAcquireCrossGenerationAuthority() {
        var first = compile("initialization-first.lyra", "");
        var second = compile("initialization-second.lyra", "");
        try (var domain = new SessionStorageDomain()) {
            var a = domain.link(first.artifact(), 0, List.of(), List.of());
            var b = domain.link(second.artifact(), 0, List.of(), List.of());
            a.validate(first.artifact().metadata()); b.validate(second.artifact().metadata());
            var receiving = ModuleLifecycle.forArtifact(new ModuleId("receiving"),
                    new LyraArtifactKey(OwnerThread.capture(), RuntimeIoEnvironment.defaults(), a));
            var producing = ModuleLifecycle.forArtifact(new ModuleId("producing"),
                    new LyraArtifactKey(OwnerThread.capture(), RuntimeIoEnvironment.defaults(), b));
            LyraClosure closure = new LyraClosure(producing.closureAuthority(), SIGNATURE) {};
            // Same-generation recursive slots are still valid while initializing.
            assertSame(closure, LyraClosureSupport.requireAuthenticatedForGeneratedInvocation(
                    closure, producing.closureAuthority(), SIGNATURE));
            assertThrows(LyraLifecycleException.class, () -> LyraClosureSupport.requireAuthenticatedForGeneratedInvocation(
                    closure, receiving.closureAuthority(), SIGNATURE));
            producing.fail(new IllegalStateException("source initializer failed"));
            assertThrows(LyraInitializationException.class, () -> LyraClosureSupport.requireAuthenticatedForGeneratedInvocation(
                    closure, receiving.closureAuthority(), SIGNATURE));
            receiving.open(); receiving.close();
        }
    }

    private static LyraClosure closure(ModuleHandle module) {
        return (LyraClosure) module.export("identity", SIGNATURE).functionValue();
    }

    private static SessionCompileResult.Success compile(String label, String header) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(label,
                header + "let @pub identity :Fn<I32;I32> = (=> |n| n)", SessionSnapshot.empty()));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }

    private static LoadedArtifact load(SessionStorageDomain domain, SessionCompileResult.Success compiled) {
        return LyraRuntime.loadSubmission(compiled.artifact(), LoadOptions.defaults(),
                domain.link(compiled.artifact(), 0, List.of(), List.of()));
    }
}
