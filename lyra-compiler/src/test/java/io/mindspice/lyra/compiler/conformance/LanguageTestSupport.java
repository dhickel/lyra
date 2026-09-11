package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.ModuleHandle;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared assertions for JUnit and the disposable fuzz worker; never a production evaluator. */
public final class LanguageTestSupport {
    private LanguageTestSupport() { }

    public static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    public static void equal(Object expected, Object actual, String detail) {
        require(Objects.deepEquals(expected, actual), detail + "\nexpected: " + expected + "\nactual: " + actual);
    }

    public static CompiledArtifact compile(String source) {
        return compile(CompileRequest.source("case.lyra", source));
    }

    public static CompiledArtifact compile(CompileRequest request) {
        CompileResult result = LyraCompiler.compile(request);
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("Expected successful compilation: " + result);
        }
        require(success.diagnostics().stream().noneMatch(d -> d.severity().isError()),
                "Successful compilation carried errors: " + success.diagnostics());
        require(!success.artifact().classes().isEmpty(), "Successful compilation emitted no classes");
        return success.artifact();
    }

    public static void diagnostics(List<Diagnostic> diagnostics, int sourceLength) {
        require(!diagnostics.isEmpty(), "Failure without diagnostics");
        require(diagnostics.stream().anyMatch(d -> d.severity().isError()), "Failure without an error");
        for (Diagnostic diagnostic : diagnostics) {
            require(diagnostic.code().value().matches("LYC-[A-Z]+-[0-9]{3}"), diagnostic.toString());
            require(!Set.of(Phase.IR, Phase.EMIT).contains(diagnostic.phase()),
                    "Source reached a broken compiler invariant: " + diagnostic);
            require(!diagnostic.summary().isBlank(), diagnostic.toString());
            span(diagnostic.primarySpan(), sourceLength);
            diagnostic.relatedSpans().forEach(related -> span(related.span(), sourceLength));
            require(diagnostic.render().contains(diagnostic.code().value()), "Diagnostic is not renderable");
        }
    }

    private static void span(SourceSpan span, int length) {
        require(span.sourceId() != null && span.startOffset() >= 0
                        && span.startOffset() <= span.endOffset() && span.endOffset() <= length,
                "Invalid UTF-16 source span: " + span + ", source length=" + length);
    }

    /** Mutation inputs may be valid, but must compile deterministically or return source diagnostics. */
    public static void checkUntrustedSyntax(String source) {
        CompileRequest request = CompileRequest.source("case.lyra", source);
        CompileResult first = LyraCompiler.compile(request);
        CompileResult second = LyraCompiler.compile(request);
        if (first instanceof CompileResult.Failure failure) {
            diagnostics(failure.diagnostics(), source.length());
            require(second instanceof CompileResult.Failure, "Compilation outcome was nondeterministic");
            equal(failure.diagnostics(), ((CompileResult.Failure) second).diagnostics(),
                    "Nondeterministic diagnostics");
        } else {
            require(second instanceof CompileResult.Success, "Compilation outcome was nondeterministic");
            sameArtifact(((CompileResult.Success) first).artifact(), ((CompileResult.Success) second).artifact());
            // Mutations can introduce unbounded recursion or effects. Define/verify only; never initialize.
            try (LoadedArtifact ignored = LyraRuntime.load(((CompileResult.Success) first).artifact())) { }
        }
    }

    public static void sameArtifact(CompiledArtifact first, CompiledArtifact second) {
        equal(first.entries().keySet(), second.entries().keySet(), "Artifact inventory changed");
        first.entries().forEach((name, bytes) -> require(Arrays.equals(bytes, second.entries().get(name)),
                "Nondeterministic artifact entry: " + name));
    }

    public static void runtimeFailure(Throwable failure, String code) {
        require(failure instanceof LyraRuntimeException, "Unexpected runtime failure: " + failure);
        LyraRuntimeException lyra = (LyraRuntimeException) failure;
        equal(code, lyra.code(), "Wrong runtime failure category");
        require(!lyra.frames().isEmpty(), "Runtime failure has no Lyra source frames: " + lyra);
    }

    public static final class Fixture implements AutoCloseable {
        public final LoadedArtifact loaded;
        public final ModuleHandle module;

        public Fixture(CompiledArtifact artifact) { this(artifact, LoadOptions.defaults()); }

        public Fixture(CompiledArtifact artifact, LoadOptions options) {
            loaded = LyraRuntime.load(artifact, options);
            try {
                module = loaded.instantiate();
            } catch (Throwable failure) {
                loaded.close();
                throw failure;
            }
        }

        public Object call(String name, String signature, Object... arguments) throws Throwable {
            var export = module.export(name, signature);
            equal(export.methodType(), export.methodHandle().type(), "Export descriptor mismatch");
            return export.methodHandle().invokeWithArguments(arguments);
        }

        @Override public void close() {
            try { module.close(); } finally { loaded.close(); }
        }
    }
}
