package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionAggregateLinkTest {
    @Test
    void exactTupleClassesAndArrayObjectsAreSharedOnlyWithinTheAuthenticatedSession() throws Exception {
        var first = compile("first.lyra", "let @mut items :Array<Tuple<I32,String>> = Array<Tuple<I32,String>>[Tuple[1 \"a\"]] items", SessionSnapshot.empty());
        var next = compile("next.lyra", "items[0] := Tuple[42 \"b\"] items", first.stagedSnapshot());
        var required = requirement(first, "items");
        var type = LyraType.parse(required.type());
        try (var left = new SessionStorageDomain(); var right = new SessionStorageDomain();
             var a = load(left, first, 0, List.of(), List.of());
             var b = load(right, first, 0, List.of(), List.of());
             var ma = a.instantiate(); var mb = b.instantiate()) {
            var own = left.register(ma, required);
            var foreign = right.register(mb, required);
            left.commit(0, List.of(own)); right.commit(0, List.of(foreign));
            assertThrows(LyraLinkException.class, () -> left.register(mb, required));
            assertThrows(LyraLinkException.class, () -> left.link(next.artifact(), 1, List.of(required), List.of(foreign)));
            Object[] original = (Object[]) LyraRuntime.readSubmissionResult(ma, type);
            Object[] independent = (Object[]) LyraRuntime.readSubmissionResult(mb, type);
            assertNotSame(original.getClass(), independent.getClass(), "structural classes are not global");
            Class<?> tupleClass = original[0].getClass();
            try (var generation = load(left, next, 1, List.of(required), List.of(own));
                 var module = generation.instantiate()) {
                Object[] result = (Object[]) LyraRuntime.readSubmissionResult(module, type);
                assertSame(original, result, "the binding is its original live array, not a copy");
                assertSame(tupleClass, result[0].getClass(), "a new tuple uses the original structural JVM type");
                assertEquals(42, tupleClass.getMethod("$lyra$get$0").invoke(original[0]));
                var wrongThread = new AtomicReference<Throwable>();
                Thread thread = new Thread(() -> {
                    try { LyraRuntime.readSubmissionResult(module, type); }
                    catch (Throwable failure) { wrongThread.set(failure); }
                });
                thread.start(); thread.join();
                assertInstanceOf(LyraThreadException.class, wrongThread.get());
            }
            // Closing the mutating generation does not retire data now held by
            // the original storage generation and shared structural domain.
            assertEquals(42, tupleClass.getMethod("$lyra$get$0").invoke(original[0]));
            ma.close();
            assertThrows(LyraClosedException.class, () -> left.link(next.artifact(), 1, List.of(required), List.of(own)));
        }
        try (var domain = new SessionStorageDomain(); var ordinary = LyraRuntime.load(first.artifact());
             var module = ordinary.instantiate()) {
            assertThrows(LyraLinkException.class, () -> domain.register(module, required),
                    "matching generated metadata from an unrelated loader is not aggregate authority");
        }
    }

    @Test
    void physicalTypeMismatchAndChangedStructuralBytesFailBeforeAnyNewSourceEffect() {
        var first = compile("initial.lyra", "let @mut count :I32 = 1 let @mut pair :Tuple<I32> = Tuple[1] pair", SessionSnapshot.empty());
        var next = compile("next.lyra", "count := 9 pair", first.stagedSnapshot());
        var wrongPackage = assertInstanceOf(SessionCompileResult.Success.class, LyraCompiler.compileSession(
                SessionCompileRequest.builder().source("different.lyra", "count := 9 pair")
                        .snapshot(first.stagedSnapshot()).javaBasePackage("different.session").build()));
        var requirements = List.of(requirement(first, "count"), requirement(first, "pair"));
        try (var domain = new SessionStorageDomain(); var loaded = load(domain, first, 0, List.of(), List.of());
             var module = loaded.instantiate()) {
            var bindings = requirements.stream().map(value -> domain.register(module, value)).toList();
            domain.commit(0, bindings);
            try (var mismatch = load(domain, wrongPackage, 1, requirements, bindings)) {
                assertThrows(LyraLinkException.class, mismatch::instantiate);
            }
            String tuple = next.artifact().classes().keySet().stream().filter(name -> name.contains("$lyra$tuple$")).findFirst().orElseThrow();
            assertArrayEquals(first.artifact().classes().get(tuple), next.artifact().classes().get(tuple),
                    "structural bytes cannot depend on a submission source label or line");
            var changed = new HashMap<>(next.artifact().classes());
            var model = ClassFile.of().parse(changed.get(tuple));
            changed.put(tuple, ClassFile.of().transformClass(model, (builder, element) -> {
                if (element instanceof SourceFileAttribute) builder.with(SourceFileAttribute.of("changed"));
                else builder.with(element);
            }));
            var tampered = ArtifactSource.inMemory(next.artifact().metadata(), changed,
                    next.artifact().entries().get(LyraRuntimeConstants.DEBUG_MAP_PATH));
            assertThrows(LyraLinkException.class, () -> LyraRuntime.loadSubmission(tampered, LoadOptions.defaults(),
                    domain.link(tampered, 1, requirements, bindings)));
            var read = compile("read.lyra", "count", first.stagedSnapshot());
            try (var reader = load(domain, read, 1, requirements, bindings); var result = reader.instantiate()) {
                assertEquals(1, LyraRuntime.readSubmissionResult(result, PrimitiveType.I32));
            }
            try (var intact = load(domain, next, 1, requirements, bindings); var result = intact.instantiate()) {
                assertNotNull(LyraRuntime.readSubmissionResult(result, LyraType.parse("Tuple<I32>")));
            }
        }
    }

    @Test
    void firstStructuralAdmissionRejectsNonSessionDefinitionsAndResetRetiresEvenEmptyLinkTables() {
        var first = compile("first.lyra", "let pair :Tuple<I32> = Tuple[1] pair", SessionSnapshot.empty());
        String tuple = first.artifact().classes().keySet().stream().filter(name -> name.contains("$lyra$tuple$")).findFirst().orElseThrow();
        var altered = new HashMap<>(first.artifact().classes());
        var model = ClassFile.of().parse(altered.get(tuple));
        altered.put(tuple, ClassFile.of().transformClass(model, (builder, element) -> {
            if (element instanceof SourceFileAttribute) builder.with(SourceFileAttribute.of("not-a-session-type"));
            else builder.with(element);
        }));
        var tampered = ArtifactSource.inMemory(first.artifact().metadata(), altered,
                first.artifact().entries().get(LyraRuntimeConstants.DEBUG_MAP_PATH));
        try (var domain = new SessionStorageDomain()) {
            assertThrows(LyraLinkException.class, () -> LyraRuntime.loadSubmission(tampered, LoadOptions.defaults(),
                    domain.link(tampered, 0, List.of(), List.of())));
            // Rejection happens before registering any shared definition.
            var retired = domain.link(first.artifact(), 0, List.of(), List.of());
            try (var loaded = load(domain, first, 0, List.of(), List.of()); var module = loaded.instantiate()) {
                assertNotNull(LyraRuntime.readSubmissionResult(module, LyraType.parse("Tuple<I32>")));
                var staged = domain.register(module, requirement(first, "pair"));
                domain.reset();
                assertThrows(LyraLinkException.class, () -> domain.commit(0, List.of(staged)),
                        "reset also retires storage capabilities staged before publication");
                assertThrows(LyraLinkException.class, () -> domain.register(module, requirement(first, "pair")));
            }
            assertThrows(LyraLinkException.class, () -> LyraRuntime.loadSubmission(first.artifact(), LoadOptions.defaults(), retired));
        }
    }

    @Test
    void externalArrayProvenanceIsAnExplicitContractOriginNotAFabricatedAllocation() {
        var first = compile("first.lyra", "let @mut items :Array<Tuple<Array<I32>,String>> = Array<Tuple<Array<I32>,String>>[Tuple[Array<I32>[1] \"a\"]]", SessionSnapshot.empty());
        var next = compile("next.lyra", "let alias = items alias", first.stagedSnapshot());
        var provenance = next.typedIr().flowMetadata().aggregateProvenance();
        assertFalse(provenance.isEmpty());
        assertTrue(next.typedIr().aggregateAllocations().isEmpty());
        for (var origin : provenance) {
            var session = assertInstanceOf(io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.SessionOrigin.class, origin.identity());
            assertEquals(first.stagedSnapshot().binding("items").orElseThrow().declarationId(), session.originDeclaration());
            assertTrue(origin.originSite().isEmpty());
            assertTrue(origin.witness().originSite().isEmpty());
            assertFalse(origin.identity().isImported());
            assertThrows(IllegalArgumentException.class, () -> new io.mindspice.lyra.compiler.ir.IrAggregateProvenance(
                    io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.localAllocation(session.ownerModule(),
                            session.originDeclaration(), session.arrayType()), origin.route(), origin.witness(), java.util.Optional.empty()),
                    "ordinary allocation provenance still requires a producer flow site");
        }
        var original = first.stagedSnapshot().binding("items").orElseThrow();
        var imported = new io.mindspice.lyra.compiler.session.ExternalBinding(original.name(), original.declarationId(),
                io.mindspice.lyra.compiler.types.BindingContract.immutable(original.type()),
                io.mindspice.lyra.compiler.session.ExternalBinding.Visibility.IMPORTED,
                io.mindspice.lyra.compiler.session.ExternalBinding.AssignmentAuthority.NONE,
                java.util.Optional.empty(), original.origin());
        assertInstanceOf(SessionCompileResult.Failure.class, LyraCompiler.compileSession(
                new SessionCompileRequest("imported.lyra", "items", first.stagedSnapshot().withBinding(imported))));
        for (var authority : List.of(io.mindspice.lyra.compiler.session.ExternalBinding.AssignmentAuthority.REBINDING,
                io.mindspice.lyra.compiler.session.ExternalBinding.AssignmentAuthority.AGGREGATE_ELEMENT)) {
            var partial = new io.mindspice.lyra.compiler.session.ExternalBinding(original.name(), original.declarationId(),
                    original.contract(), original.visibility(), authority, original.storageIdentity(), original.origin());
            var rejected = LyraCompiler.compileSession(new SessionCompileRequest("partial.lyra", "items", first.stagedSnapshot().withBinding(partial)));
            assertInstanceOf(SessionCompileResult.Failure.class, rejected);
            assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                    rejected.diagnostics().getFirst().code());
        }
    }

    @Test
    void externalMayAliasesAndCallableReadsDoNotLaunderImportedOwnership() {
        var first = compile("first.lyra", "let @mut a :Array<Array<I32>> = Array<Array<I32>>[Array<I32>[1]] let @mut b = a let @mut items = Array<I32>[1]", SessionSnapshot.empty());
        var resolver = io.mindspice.lyra.compiler.source.SourceResolver.single(
                io.mindspice.lyra.compiler.source.ResolvedSource.memory("dependency", java.net.URI.create("memory:///dependency.lyra"),
                        "let @pub values :Array<I32> = Array<I32>[9]"));
        for (String source : List.of(
                "a[0] := values b[0][0] := 1",
                "let read :Fn<;Array<I32>> = (=> || items) items := values let @mut result :Array<I32> = (read) result[0] := 1")) {
            var result = LyraCompiler.compileSession(SessionCompileRequest.builder()
                    .source("ownership.lyra", "import dependency->{values}\n" + source)
                    .snapshot(first.stagedSnapshot()).resolver(resolver).build());
            assertInstanceOf(SessionCompileResult.Failure.class, result, source);
            assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                    result.diagnostics().getFirst().code(), result.diagnostics().toString());
        }
    }

    @Test
    void importedGraphsCannotPolluteCertifiedSessionDataAndResetRetiresItsCapabilities() {
        var first = compile("data.lyra", "let @mut items :Array<I32> = Array<I32>[1]", SessionSnapshot.empty());
        var imported = compile("imported.lyra", "import std->io\nio->::println[\"must-not-run\"] items", first.stagedSnapshot());
        var required = requirement(first, "items");
        try (var domain = new SessionStorageDomain(); var loaded = load(domain, first, 0, List.of(), List.of());
             var module = loaded.instantiate()) {
            var binding = domain.register(module, required);
            domain.commit(0, List.of(binding));
            var output = new java.io.ByteArrayOutputStream();
            var io = new RuntimeIoEnvironment(java.io.InputStream.nullInputStream(), output, output, java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(LyraLinkException.class, () -> LyraRuntime.loadSubmission(imported.artifact(),
                    LoadOptions.defaults().withIoEnvironment(io), domain.link(imported.artifact(), 1, List.of(required), List.of(binding))));
            assertEquals(0, output.size());
            domain.reset();
            assertThrows(LyraLinkException.class, () -> domain.link(first.artifact(), 1, List.of(required), List.of(binding)));
        }
    }

    private static SessionCompileResult.Success compile(String name, String source, SessionSnapshot snapshot) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(name, source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }

    private static SessionStorageDomain.Requirement requirement(SessionCompileResult.Success result, String name) {
        var binding = result.stagedSnapshot().binding(name).orElseThrow();
        return new SessionStorageDomain.Requirement(binding.declarationId().ordinal(),
                binding.storageIdentity().map(value -> value.ordinal()).orElse(-1L), name,
                binding.type().canonicalSpelling(), binding.allowsRebinding());
    }

    private static LoadedArtifact load(SessionStorageDomain domain, SessionCompileResult.Success result, long revision,
                                       List<SessionStorageDomain.Requirement> requirements, List<SessionStorageDomain.Binding> bindings) {
        return LyraRuntime.loadSubmission(result.artifact(), LoadOptions.defaults(),
                domain.link(result.artifact(), revision, requirements, bindings));
    }
}
