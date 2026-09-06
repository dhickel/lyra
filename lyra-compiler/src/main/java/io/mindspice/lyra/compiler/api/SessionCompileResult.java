package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.session.SessionImport;
import io.mindspice.lyra.compiler.session.SessionRevision;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.ModuleGraph;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of compiling one submission against a session snapshot.
 *
 * <p>A successful result contains a staged namespace snapshot.  Compilation
 * does not publish it; a session owner may publish that snapshot only after
 * the generated submission has executed successfully.</p>
 *
 * <p>The source-local artifact has an exact typed result contract separate from
 * its Unit-typed module initialization. Staged root lets include private bindings.
 * The compiler certificate retains semantic identities and flow, including
 * conservative execution prefixes for failed attempts, but never authenticates
 * live runtime storage or proves that an initializer actually ran.</p>
 */
public sealed interface SessionCompileResult
        permits SessionCompileResult.Success, SessionCompileResult.Failure {
    SessionRevision baseRevision();

    List<Diagnostic> diagnostics();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    record Success(
            SessionRevision baseRevision,
            SessionRevision revision,
            SessionSnapshot stagedSnapshot,
            SessionSnapshot attemptedSnapshot,
            CompiledArtifact artifact,
            ModuleGraph moduleGraph,
            ResolvedSemanticGraph resolvedGraph,
            TypedSemanticGraph typedGraph,
            TypedIr typedIr,
            List<DeclarationId> stagedDeclarations,
            List<SessionImport> stagedImports,
            List<Diagnostic> diagnostics)
            implements SessionCompileResult {
        public Success {
            baseRevision = Objects.requireNonNull(baseRevision, "baseRevision");
            revision = Objects.requireNonNull(revision, "revision");
            stagedSnapshot = Objects.requireNonNull(stagedSnapshot, "stagedSnapshot");
            attemptedSnapshot = Objects.requireNonNull(attemptedSnapshot, "attemptedSnapshot");
            artifact = Objects.requireNonNull(artifact, "artifact");
            moduleGraph = Objects.requireNonNull(moduleGraph, "moduleGraph");
            resolvedGraph = Objects.requireNonNull(resolvedGraph, "resolvedGraph");
            typedGraph = Objects.requireNonNull(typedGraph, "typedGraph");
            typedIr = Objects.requireNonNull(typedIr, "typedIr");
            stagedDeclarations = List.copyOf(Objects.requireNonNull(stagedDeclarations,
                    "stagedDeclarations"));
            stagedImports = List.copyOf(Objects.requireNonNull(stagedImports, "stagedImports"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.stream().anyMatch(value -> value.severity().isError())) {
                throw new IllegalArgumentException(
                        "successful session compilation cannot contain an error diagnostic");
            }
            if (revision.compareTo(baseRevision) <= 0) {
                throw new IllegalArgumentException("successful session compilation must advance revision");
            }
            if (!stagedSnapshot.revision().equals(revision)) {
                throw new IllegalArgumentException("staged snapshot revision disagrees with result");
            }
            if (!stagedSnapshot.allocator().equals(typedGraph.allocator())) {
                throw new IllegalArgumentException("staged snapshot allocator disagrees with typing");
            }
            if (stagedSnapshot.flowCertificate().isEmpty()
                    || attemptedSnapshot.flowCertificate().isEmpty()) {
                throw new IllegalArgumentException(
                        "successful session compilation needs staged and attempted flow proofs");
            }
            if (!attemptedSnapshot.allocator().equals(typedGraph.allocator())) {
                throw new IllegalArgumentException(
                        "attempted snapshot allocator disagrees with typing");
            }
            if (attemptedSnapshot.revision().compareTo(baseRevision) < 0
                    || attemptedSnapshot.revision().compareTo(revision) > 0) {
                throw new IllegalArgumentException(
                        "attempted snapshot revision is outside the submission boundary");
            }
        }

        /** The snapshot to publish after successful execution of the artifact. */
        public SessionSnapshot snapshot() {
            return stagedSnapshot;
        }

        /**
         * Conservative state to retain when execution fails or is cancelled.
         * It advances identities but publishes no newly staged names.
         */
        public SessionSnapshot retainAttemptedFlow() {
            return attemptedSnapshot;
        }

        /** The staged compiler proof for the successful namespace path. */
        public SessionFlowCertificate flowCertificate() {
            return stagedSnapshot.flowCertificate().orElseThrow();
        }

        public CompiledArtifact compiledArtifact() {
            return artifact;
        }
    }

    record Failure(SessionRevision baseRevision, List<Diagnostic> diagnostics)
            implements SessionCompileResult {
        public Failure {
            baseRevision = Objects.requireNonNull(baseRevision, "baseRevision");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("session compilation failure needs diagnostics");
            }
            if (diagnostics.stream().noneMatch(value -> value.severity().isError())) {
                throw new IllegalArgumentException(
                        "session compilation failure needs an error diagnostic");
            }
        }
    }
}
