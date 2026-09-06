package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.SourceId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal source-registry entry. The submitted request is retained even when
 * compilation or execution fails, so diagnostics keep their origin metadata.
 */
record SourceRecord(
        EvaluationRequest request,
        SourceId compilerSourceId,
        SourceRecordStatus status,
        Optional<SessionRevision> publishedRevision,
        List<Diagnostic> diagnostics) {
    SourceRecord {
        request = Objects.requireNonNull(request, "request");
        compilerSourceId = Objects.requireNonNull(compilerSourceId, "compilerSourceId");
        status = Objects.requireNonNull(status, "status");
        publishedRevision = Objects.requireNonNull(publishedRevision, "publishedRevision");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (status == SourceRecordStatus.COMMITTED && publishedRevision.isEmpty()) {
            throw new IllegalArgumentException("a committed source needs a published revision");
        }
        if (status != SourceRecordStatus.COMMITTED && publishedRevision.isPresent()) {
            throw new IllegalArgumentException(
                    "a failed source must not carry a published revision");
        }
    }

    EvaluationSource source() {
        return request.source();
    }
}

enum SourceRecordStatus {
    COMMITTED,
    COMPILATION_FAILURE,
    RUNTIME_FAILURE,
    CANCELLED
}
