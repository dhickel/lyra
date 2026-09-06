package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Closed wire-schema model for the version-one REPL protocol.
 *
 * <p>There is deliberately no generic call/reflective operation in this
 * hierarchy. Every permitted operation has a fixed, bounded schema.</p>
 */
public sealed interface ProtocolMessage
        permits ProtocolMessage.ClientHello,
        ProtocolMessage.ServerHello,
        ProtocolMessage.Authenticate,
        ProtocolMessage.Authenticated,
        ProtocolMessage.EvaluateRequest,
        ProtocolMessage.Accepted,
        ProtocolMessage.Status,
        ProtocolMessage.Result,
        ProtocolMessage.CancelRequest,
        ProtocolMessage.CancelResult,
        ProtocolMessage.ResetRequest,
        ProtocolMessage.ResetResult,
        ProtocolMessage.QueryRequest,
        ProtocolMessage.QueryResult,
        ProtocolMessage.Error {
    int version();

    record ClientHello(
            int version,
            UUID clientId,
            Optional<UUID> sessionId) implements ProtocolMessage {
        public ClientHello {
            requireVersion(version);
            clientId = Objects.requireNonNull(clientId, "clientId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
        }

        public ClientHello(UUID clientId) {
            this(RemoteProtocol.VERSION, clientId, Optional.empty());
        }
    }

    record ServerHello(
            int version,
            UUID sessionId,
            byte[] challenge) implements ProtocolMessage {
        public ServerHello {
            requireVersion(version);
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            challenge = Objects.requireNonNull(challenge, "challenge").clone();
            if (challenge.length != RemoteProtocol.CHALLENGE_BYTES) {
                throw new IllegalArgumentException("challenge has an invalid length");
            }
        }

        @Override
        public byte[] challenge() {
            return challenge.clone();
        }
    }

    record Authenticate(
            int version,
            UUID sessionId,
            byte[] challenge,
            String token) implements ProtocolMessage {
        public Authenticate {
            requireVersion(version);
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            challenge = Objects.requireNonNull(challenge, "challenge").clone();
            if (challenge.length != RemoteProtocol.CHALLENGE_BYTES) {
                throw new IllegalArgumentException("challenge has an invalid length");
            }
            token = ProtocolValues.token(token, "token", 256);
        }

        public Authenticate(UUID sessionId, byte[] challenge, String token) {
            this(RemoteProtocol.VERSION, sessionId, challenge, token);
        }

        @Override
        public byte[] challenge() {
            return challenge.clone();
        }

        @Override
        public String toString() {
            return "Authenticate[version=" + version + ",sessionId=" + sessionId
                    + ",challenge=<redacted>,token=<redacted>]";
        }
    }

    record Authenticated(
            int version,
            UUID sessionId,
            long revision,
            long lastSequence,
            Optional<UUID> activeRequestId) implements ProtocolMessage {
        public Authenticated {
            requireVersion(version);
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            lastSequence = ProtocolValues.nonNegativeLong(lastSequence, "lastSequence");
            activeRequestId = Objects.requireNonNull(activeRequestId, "activeRequestId");
        }
    }

    record EvaluateRequest(
            int version,
            UUID requestId,
            long sequence,
            SessionRevision revision,
            EvaluationSource source) implements ProtocolMessage {
        public EvaluateRequest {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            revision = Objects.requireNonNull(revision, "revision");
            source = Objects.requireNonNull(source, "source");
            if (source.utf16Length() > RemoteProtocol.MAX_SOURCE_CHARACTERS) {
                throw new IllegalArgumentException("source exceeds protocol bound");
            }
            if (source.origin().label().length() > RemoteProtocol.MAX_LABEL_CHARACTERS) {
                throw new IllegalArgumentException("source label exceeds protocol bound");
            }
        }

        public EvaluateRequest(UUID requestId, long sequence,
                               SessionRevision revision, EvaluationSource source) {
            this(RemoteProtocol.VERSION, requestId, sequence, revision, source);
        }
    }

    record Accepted(
            int version,
            UUID requestId,
            long sequence,
            RemoteStatus status,
            long revision) implements ProtocolMessage {
        public Accepted {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            status = Objects.requireNonNull(status, "status");
            if (status != RemoteStatus.QUEUED && status != RemoteStatus.RUNNING) {
                throw new IllegalArgumentException("accepted status must be queued or running");
            }
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
        }
    }

    record Status(
            int version,
            UUID requestId,
            long sequence,
            RemoteStatus status,
            long revision,
            Optional<String> detail) implements ProtocolMessage {
        public Status {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            status = Objects.requireNonNull(status, "status");
            if (status.isTerminal()) {
                throw new IllegalArgumentException("status messages must be nonterminal: " + status);
            }
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record Result(
            int version,
            UUID requestId,
            long sequence,
            RemoteStatus status,
            long revision,
            List<Diagnostic> diagnostics,
            Optional<ValueSnapshot> value,
            Optional<String> failureSummary,
            Optional<UUID> activeRequestId) implements ProtocolMessage {
        public Result {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            status = Objects.requireNonNull(status, "status");
            if (!status.isTerminal()) {
                throw new IllegalArgumentException("result status must be terminal: " + status);
            }
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            diagnostics = copyDiagnostics(diagnostics);
            value = Objects.requireNonNull(value, "value");
            failureSummary = Objects.requireNonNull(failureSummary, "failureSummary")
                    .map(summary -> ProtocolValues.text(summary, "failureSummary", 4096));
            activeRequestId = Objects.requireNonNull(activeRequestId, "activeRequestId");
            if (status != RemoteStatus.BUSY && activeRequestId.isPresent()) {
                throw new IllegalArgumentException(
                        "active request identity is only valid for a busy result");
            }
        }

        public Result(UUID requestId, long sequence, RemoteStatus status, long revision,
                      List<Diagnostic> diagnostics, Optional<ValueSnapshot> value,
                      Optional<String> failureSummary, Optional<UUID> activeRequestId) {
            this(RemoteProtocol.VERSION, requestId, sequence, status, revision,
                    diagnostics, value, failureSummary, activeRequestId);
        }
    }

    record CancelRequest(
            int version,
            UUID operationId,
            UUID requestId) implements ProtocolMessage {
        public CancelRequest {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            requestId = Objects.requireNonNull(requestId, "requestId");
        }

        public CancelRequest(UUID operationId, UUID requestId) {
            this(RemoteProtocol.VERSION, operationId, requestId);
        }
    }

    record CancelResult(
            int version,
            UUID operationId,
            UUID requestId,
            ControlStatus status,
            long revision,
            Optional<String> detail) implements ProtocolMessage {
        public CancelResult {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            requestId = Objects.requireNonNull(requestId, "requestId");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record ResetRequest(
            int version,
            UUID operationId,
            long expectedRevision) implements ProtocolMessage {
        public ResetRequest {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            expectedRevision = ProtocolValues.nonNegativeLong(expectedRevision, "expectedRevision");
        }

        public ResetRequest(UUID operationId, long expectedRevision) {
            this(RemoteProtocol.VERSION, operationId, expectedRevision);
        }
    }

    record ResetResult(
            int version,
            UUID operationId,
            ControlStatus status,
            long revision,
            Optional<String> detail) implements ProtocolMessage {
        public ResetResult {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record QueryRequest(
            int version,
            UUID queryId,
            QueryKind queryKind,
            Optional<UUID> requestId,
            Optional<EvaluationSource> typeSource) implements ProtocolMessage {
        public QueryRequest {
            requireVersion(version);
            queryId = Objects.requireNonNull(queryId, "queryId");
            queryKind = Objects.requireNonNull(queryKind, "queryKind");
            requestId = Objects.requireNonNull(requestId, "requestId");
            typeSource = Objects.requireNonNull(typeSource, "typeSource");
            if (queryKind == QueryKind.REQUEST && requestId.isEmpty()) {
                throw new IllegalArgumentException("request queries need a request identity");
            }
            if (queryKind != QueryKind.REQUEST && requestId.isPresent()) {
                throw new IllegalArgumentException("request identity is only valid for request queries");
            }
            if (queryKind == QueryKind.TYPE && typeSource.isEmpty()) {
                throw new IllegalArgumentException("type queries need source text");
            }
            if (queryKind != QueryKind.TYPE && typeSource.isPresent()) {
                throw new IllegalArgumentException("source text is only valid for type queries");
            }
            typeSource.ifPresent(source -> {
                if (source.utf16Length() > RemoteProtocol.MAX_SOURCE_CHARACTERS) {
                    throw new IllegalArgumentException("source exceeds protocol bound");
                }
                if (source.origin().label().length() > RemoteProtocol.MAX_LABEL_CHARACTERS) {
                    throw new IllegalArgumentException("source label exceeds protocol bound");
                }
            });
        }

        public static QueryRequest session(UUID queryId) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.SESSION,
                    Optional.empty(), Optional.empty());
        }

        public static QueryRequest request(UUID queryId, UUID requestId) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.REQUEST,
                    Optional.of(requestId), Optional.empty());
        }

        public static QueryRequest bindings(UUID queryId) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.BINDINGS,
                    Optional.empty(), Optional.empty());
        }

        public static QueryRequest type(UUID queryId, EvaluationSource source) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.TYPE,
                    Optional.empty(), Optional.of(source));
        }
    }

    record QueryResult(
            int version,
            UUID queryId,
            QueryKind queryKind,
            QueryStatus status,
            long revision,
            Optional<RequestSnapshot> request,
            Optional<Result> terminalResult,
            List<RemoteBinding> bindings,
            Optional<String> inferredType,
            Optional<String> detail) implements ProtocolMessage {
        public QueryResult {
            requireVersion(version);
            queryId = Objects.requireNonNull(queryId, "queryId");
            queryKind = Objects.requireNonNull(queryKind, "queryKind");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            request = Objects.requireNonNull(request, "request");
            terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
            bindings = copyBindings(bindings);
            inferredType = Objects.requireNonNull(inferredType, "inferredType")
                    .map(value -> ProtocolValues.text(value, "inferredType", 4096));
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record Error(
            int version,
            ErrorCode code,
            String detail,
            Optional<UUID> requestId,
            Optional<UUID> correlationId,
            boolean retryable) implements ProtocolMessage {
        public Error {
            requireVersion(version);
            code = Objects.requireNonNull(code, "code");
            detail = ProtocolValues.text(detail, "detail", 4096);
            requestId = Objects.requireNonNull(requestId, "requestId");
            correlationId = Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    record RequestSnapshot(
            UUID requestId,
            long sequence,
            RemoteStatus status,
            long revision,
            Optional<String> detail) {
        public RequestSnapshot {
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record Diagnostic(
            String code,
            String severity,
            String summary,
            Span primarySpan,
            List<RelatedSpan> relatedSpans) {
        public Diagnostic {
            code = ProtocolValues.token(code, "diagnostic code", 128);
            severity = ProtocolValues.token(severity, "diagnostic severity", 32);
            summary = ProtocolValues.text(summary, "diagnostic summary",
                    RemoteProtocol.MAX_DIAGNOSTIC_CHARACTERS);
            primarySpan = Objects.requireNonNull(primarySpan, "primarySpan");
            relatedSpans = copyRelatedSpans(relatedSpans);
        }
    }

    record RelatedSpan(Span span, String label) {
        public RelatedSpan {
            span = Objects.requireNonNull(span, "span");
            label = ProtocolValues.text(label, "related span label", 1024);
        }
    }

    record Span(String sourceId, int startOffset, int endOffset) {
        public Span {
            sourceId = ProtocolValues.text(sourceId, "source id", 4096);
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("invalid diagnostic span");
            }
        }
    }

    /** Data-only value snapshot; it cannot contain a live Java value or callback. */
    record ValueSnapshot(String canonicalType, ValueData data) {
        public ValueSnapshot {
            canonicalType = ProtocolValues.token(canonicalType, "canonicalType", 4096);
            data = Objects.requireNonNull(data, "data");
            validateValue(data, 0, new int[1]);
        }
    }

    sealed interface ValueData permits Nil, Unit, Scalar, Aggregate, Function, Reference, Truncated {
    }

    record Nil() implements ValueData {
    }

    record Unit() implements ValueData {
    }

    record Scalar(String scalarKind, String value) implements ValueData {
        public Scalar {
            scalarKind = ProtocolValues.token(scalarKind, "scalarKind", 64);
            value = ProtocolValues.scalarText(value, "scalar value", 16 * 1024);
        }
    }

    record Aggregate(
            String aggregateKind,
            String identity,
            Optional<String> alias,
            List<ValueSnapshot> elements,
            Optional<String> truncation) implements ValueData {
        public Aggregate {
            aggregateKind = ProtocolValues.token(aggregateKind, "aggregateKind", 64);
            identity = ProtocolValues.token(identity, "aggregate identity", 4096);
            alias = Objects.requireNonNull(alias, "alias")
                    .map(value -> ProtocolValues.text(value, "aggregate alias", 4096));
            elements = copyValues(elements);
            truncation = Objects.requireNonNull(truncation, "truncation")
                    .map(value -> ProtocolValues.token(value, "truncation", 64));
            if (elements.size() > RemoteProtocol.MAX_JSON_ARRAY_ITEMS) {
                throw new IllegalArgumentException("aggregate exceeds protocol bound");
            }
        }

        public Aggregate(String aggregateKind, String identity, List<ValueSnapshot> elements) {
            this(aggregateKind, identity, Optional.empty(), elements, Optional.empty());
        }
    }

    record Function(String identity, Optional<String> description) implements ValueData {
        public Function {
            identity = ProtocolValues.token(identity, "function identity", 4096);
            description = Objects.requireNonNull(description, "description")
                    .map(value -> ProtocolValues.text(value, "function description", 4096));
        }

        public Function(String identity) {
            this(identity, Optional.empty());
        }
    }

    record Reference(String description) implements ValueData {
        public Reference {
            description = ProtocolValues.text(description, "reference description", 4096);
        }
    }

    record Truncated(String reason, Optional<String> description) implements ValueData {
        public Truncated {
            reason = ProtocolValues.token(reason, "truncation reason", 64);
            description = Objects.requireNonNull(description, "description")
                    .map(value -> ProtocolValues.text(value, "truncation description", 4096));
        }
    }

    enum QueryKind {
        SESSION,
        REQUEST,
        BINDINGS,
        TYPE
    }

    enum QueryStatus {
        OK,
        NOT_FOUND,
        EXPIRED,
        BUSY,
        UNAVAILABLE,
        CLOSED
    }

    enum ControlStatus {
        OK,
        REQUESTED,
        ALREADY_TERMINAL,
        NOT_FOUND,
        BUSY,
        REVISION_CONFLICT,
        CANCELLED,
        UNAVAILABLE,
        CLOSED
    }

    enum ErrorCode {
        UNSUPPORTED_VERSION,
        MALFORMED_MESSAGE,
        INVALID_SCHEMA,
        AUTHENTICATION_FAILED,
        CONTROLLER_BUSY,
        SESSION_MISMATCH,
        HANDSHAKE_TIMEOUT,
        FRAME_TOO_LARGE,
        SEQUENCE_REJECTED,
        REQUEST_EXPIRED,
        REVISION_CONFLICT,
        BUSY,
        CLOSED,
        UNAVAILABLE,
        INVALID_OPERATION,
        INTERNAL
    }

    enum RemoteStatus {
        QUEUED(false),
        RUNNING(false),
        CANCELLATION_REQUESTED(false),
        DISCONNECTED(false),
        SUCCESS(true),
        COMPILATION_FAILURE(true),
        RUNTIME_FAILURE(true),
        CANCELLED(true),
        BUSY(true),
        CLOSED(true),
        UNAVAILABLE(true),
        REVISION_CONFLICT(true),
        REJECTED(true),
        EXPIRED(true);

        private final boolean terminal;

        RemoteStatus(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean isTerminal() {
            return terminal;
        }
    }

    private static void requireVersion(int version) {
        if (version != RemoteProtocol.VERSION) {
            throw new IllegalArgumentException("unsupported REPL protocol version: " + version);
        }
    }

    private static List<Diagnostic> copyDiagnostics(List<Diagnostic> values) {
        Objects.requireNonNull(values, "diagnostics");
        if (values.size() > RemoteProtocol.MAX_DIAGNOSTICS) {
            throw new IllegalArgumentException("too many diagnostics");
        }
        ArrayList<Diagnostic> copy = new ArrayList<>(values.size());
        for (Diagnostic value : values) {
            copy.add(Objects.requireNonNull(value, "diagnostics must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<RelatedSpan> copyRelatedSpans(List<RelatedSpan> values) {
        Objects.requireNonNull(values, "relatedSpans");
        if (values.size() > 32) {
            throw new IllegalArgumentException("too many related spans");
        }
        ArrayList<RelatedSpan> copy = new ArrayList<>(values.size());
        for (RelatedSpan value : values) {
            copy.add(Objects.requireNonNull(value, "related spans must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<RemoteBinding> copyBindings(List<RemoteBinding> values) {
        Objects.requireNonNull(values, "bindings");
        if (values.size() > RemoteProtocol.MAX_QUERY_BINDINGS) {
            throw new IllegalArgumentException("too many bindings");
        }
        ArrayList<RemoteBinding> copy = new ArrayList<>(values.size());
        for (RemoteBinding value : values) {
            copy.add(Objects.requireNonNull(value, "bindings must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<ValueSnapshot> copyValues(List<ValueSnapshot> values) {
        Objects.requireNonNull(values, "elements");
        ArrayList<ValueSnapshot> copy = new ArrayList<>(values.size());
        for (ValueSnapshot value : values) {
            copy.add(Objects.requireNonNull(value, "elements must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static void validateValue(ValueData value, int depth, int[] nodes) {
        if (depth > 8) {
            throw new IllegalArgumentException("wire value exceeds maximum depth");
        }
        if (++nodes[0] > 4096) {
            throw new IllegalArgumentException("wire value exceeds maximum node count");
        }
        if (value instanceof Aggregate aggregate) {
            for (ValueSnapshot child : aggregate.elements()) {
                validateValue(child.data(), depth + 1, nodes);
            }
        }
    }
}
