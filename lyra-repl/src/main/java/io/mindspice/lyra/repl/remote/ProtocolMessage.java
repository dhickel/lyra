package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Closed wire-schema model for the version-two credential-free REPL protocol.
 *
 * <p>There is deliberately no generic call/reflective operation in this
 * hierarchy. Every permitted operation has a fixed, bounded schema. The
 * handshake carries session identity, revision and mutation sequence; no
 * credential, token or challenge exists anywhere in the wire model.</p>
 */
public sealed interface ProtocolMessage
        permits ProtocolMessage.ClientHello,
        ProtocolMessage.ServerHello,
        ProtocolMessage.EvaluateRequest,
        ProtocolMessage.Accepted,
        ProtocolMessage.Status,
        ProtocolMessage.Result,
        ProtocolMessage.LoadRequest,
        ProtocolMessage.ReloadRequest,
        ProtocolMessage.CancelRequest,
        ProtocolMessage.CancelResult,
        ProtocolMessage.ResetRequest,
        ProtocolMessage.ResetResult,
        ProtocolMessage.QueryRequest,
        ProtocolMessage.QueryResult,
        ProtocolMessage.CompletionRequest,
        ProtocolMessage.CompletionResult,
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

    /**
     * The single ready message. It carries the authoritative session
     * identity, revision, mutation sequence, request-sequence watermark and
     * currently active request so a reconnecting client can reconcile
     * without resubmitting source.
     */
    record ServerHello(
            int version,
            UUID sessionId,
            long revision,
            long mutationSequence,
            long lastSequence,
            Optional<UUID> activeRequestId) implements ProtocolMessage {
        public ServerHello {
            requireVersion(version);
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            lastSequence = ProtocolValues.nonNegativeLong(lastSequence, "lastSequence");
            activeRequestId = Objects.requireNonNull(activeRequestId, "activeRequestId");
        }
    }

    record EvaluateRequest(
            int version,
            UUID requestId,
            long sequence,
            SessionRevision revision,
            long mutationSequence,
            EvaluationSource source) implements ProtocolMessage {
        public EvaluateRequest {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            revision = Objects.requireNonNull(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            source = Objects.requireNonNull(source, "source");
            if (source.utf16Length() > RemoteProtocol.MAX_SOURCE_CHARACTERS) {
                throw new IllegalArgumentException("source exceeds protocol bound");
            }
            if (source.origin().label().length() > RemoteProtocol.MAX_LABEL_CHARACTERS) {
                throw new IllegalArgumentException("source label exceeds protocol bound");
            }
        }

        public EvaluateRequest(UUID requestId, long sequence,
                               SessionRevision revision, long mutationSequence,
                               EvaluationSource source) {
            this(RemoteProtocol.VERSION, requestId, sequence, revision, mutationSequence, source);
        }
    }

    record LoadRequest(
            int version,
            UUID requestId,
            long sequence,
            SessionRevision revision,
            long mutationSequence,
            String path) implements ProtocolMessage {
        public LoadRequest {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            revision = Objects.requireNonNull(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            path = ProtocolValues.text(path, "path", RemoteProtocol.MAX_LOAD_PATH_CHARACTERS);
        }

        public LoadRequest(UUID requestId, long sequence, SessionRevision revision,
                           long mutationSequence, String path) {
            this(RemoteProtocol.VERSION, requestId, sequence, revision, mutationSequence, path);
        }
    }

    record ReloadRequest(
            int version,
            UUID requestId,
            long sequence,
            SessionRevision revision,
            long mutationSequence,
            String target) implements ProtocolMessage {
        public ReloadRequest {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            revision = Objects.requireNonNull(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            target = ProtocolValues.token(target, "target", RemoteProtocol.MAX_RELOAD_TARGET_CHARACTERS);
        }

        public ReloadRequest(UUID requestId, long sequence, SessionRevision revision,
                             long mutationSequence, String target) {
            this(RemoteProtocol.VERSION, requestId, sequence, revision, mutationSequence, target);
        }
    }

    record Accepted(
            int version,
            UUID requestId,
            long sequence,
            RemoteStatus status,
            long revision,
            long mutationSequence) implements ProtocolMessage {
        public Accepted {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            status = Objects.requireNonNull(status, "status");
            if (status != RemoteStatus.QUEUED && status != RemoteStatus.RUNNING) {
                throw new IllegalArgumentException("accepted status must be queued or running");
            }
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
        }
    }

    record Status(
            int version,
            UUID requestId,
            long sequence,
            RemoteStatus status,
            long revision,
            long mutationSequence,
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
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
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
            long mutationSequence,
            List<Diagnostic> diagnostics,
            Optional<ValueSnapshot> value,
            Optional<String> failureSummary,
            Optional<UUID> activeRequestId,
            List<Initializer> initializers) implements ProtocolMessage {
        public Result {
            requireVersion(version);
            requestId = Objects.requireNonNull(requestId, "requestId");
            sequence = ProtocolValues.positiveLong(sequence, "sequence");
            status = Objects.requireNonNull(status, "status");
            if (!status.isTerminal()) {
                throw new IllegalArgumentException("result status must be terminal: " + status);
            }
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            diagnostics = copyDiagnostics(diagnostics);
            value = Objects.requireNonNull(value, "value");
            failureSummary = Objects.requireNonNull(failureSummary, "failureSummary")
                    .map(summary -> ProtocolValues.text(summary, "failureSummary", 4096));
            activeRequestId = Objects.requireNonNull(activeRequestId, "activeRequestId");
            if (status != RemoteStatus.BUSY && activeRequestId.isPresent()) {
                throw new IllegalArgumentException(
                        "active request identity is only valid for a busy result");
            }
            initializers = copyInitializers(initializers);
        }

        public Result(UUID requestId, long sequence, RemoteStatus status, long revision,
                      long mutationSequence, List<Diagnostic> diagnostics,
                      Optional<ValueSnapshot> value, Optional<String> failureSummary,
                      Optional<UUID> activeRequestId) {
            this(RemoteProtocol.VERSION, requestId, sequence, status, revision, mutationSequence,
                    diagnostics, value, failureSummary, activeRequestId, List.of());
        }
    }

    /** One initializer module entry of a submission's real execution progress. */
    record Initializer(String moduleId, InitializerState state) {
        public Initializer {
            moduleId = ProtocolValues.token(moduleId, "initializer module id", 4096);
            state = Objects.requireNonNull(state, "state");
        }
    }

    enum InitializerState {
        SCHEDULED,
        ATTEMPTED,
        COMPLETED
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
            long mutationSequence,
            Optional<String> detail) implements ProtocolMessage {
        public CancelResult {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            requestId = Objects.requireNonNull(requestId, "requestId");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record ResetRequest(
            int version,
            UUID operationId,
            long expectedRevision,
            long expectedMutationSequence) implements ProtocolMessage {
        public ResetRequest {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            expectedRevision = ProtocolValues.nonNegativeLong(expectedRevision, "expectedRevision");
            expectedMutationSequence = ProtocolValues.nonNegativeLong(
                    expectedMutationSequence, "expectedMutationSequence");
        }

        public ResetRequest(UUID operationId, long expectedRevision) {
            this(RemoteProtocol.VERSION, operationId, expectedRevision, 0);
        }

        public ResetRequest(UUID operationId, long expectedRevision,
                            long expectedMutationSequence) {
            this(RemoteProtocol.VERSION, operationId, expectedRevision,
                    expectedMutationSequence);
        }
    }

    record ResetResult(
            int version,
            UUID operationId,
            ControlStatus status,
            long revision,
            long mutationSequence,
            Optional<String> detail) implements ProtocolMessage {
        public ResetResult {
            requireVersion(version);
            operationId = Objects.requireNonNull(operationId, "operationId");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record QueryRequest(
            int version,
            UUID queryId,
            QueryKind queryKind,
            long expectedRevision,
            long expectedMutationSequence,
            Optional<UUID> requestId,
            Optional<EvaluationSource> typeSource) implements ProtocolMessage {
        public QueryRequest {
            requireVersion(version);
            queryId = Objects.requireNonNull(queryId, "queryId");
            queryKind = Objects.requireNonNull(queryKind, "queryKind");
            expectedRevision = ProtocolValues.nonNegativeLong(expectedRevision, "expectedRevision");
            expectedMutationSequence = ProtocolValues.nonNegativeLong(
                    expectedMutationSequence, "expectedMutationSequence");
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

        public static QueryRequest session(UUID queryId, long revision, long mutationSequence) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.SESSION,
                    revision, mutationSequence, Optional.empty(), Optional.empty());
        }

        public static QueryRequest request(UUID queryId, long revision, long mutationSequence,
                                           UUID requestId) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.REQUEST,
                    revision, mutationSequence, Optional.of(requestId), Optional.empty());
        }

        public static QueryRequest bindings(UUID queryId, long revision, long mutationSequence) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.BINDINGS,
                    revision, mutationSequence, Optional.empty(), Optional.empty());
        }

        public static QueryRequest type(UUID queryId, long revision, long mutationSequence,
                                        EvaluationSource source) {
            return new QueryRequest(RemoteProtocol.VERSION, queryId, QueryKind.TYPE,
                    revision, mutationSequence, Optional.empty(), Optional.of(source));
        }
    }

    record QueryResult(
            int version,
            UUID queryId,
            QueryKind queryKind,
            QueryStatus status,
            long revision,
            long mutationSequence,
            Optional<RequestSnapshot> request,
            Optional<Result> terminalResult,
            List<RemoteBinding> bindings,
            Optional<String> inferredType,
            Optional<String> detail,
            Optional<RemoteStatus> terminalStatus) implements ProtocolMessage {
        public QueryResult {
            requireVersion(version);
            queryId = Objects.requireNonNull(queryId, "queryId");
            queryKind = Objects.requireNonNull(queryKind, "queryKind");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            request = Objects.requireNonNull(request, "request");
            terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
            bindings = copyBindings(bindings);
            inferredType = Objects.requireNonNull(inferredType, "inferredType")
                    .map(value -> ProtocolValues.text(value, "inferredType", 4096));
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
            terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
            terminalStatus.ifPresent(value -> {
                if (!value.isTerminal()) {
                    throw new IllegalArgumentException(
                            "query terminal status must be terminal: " + value);
                }
            });
            if (terminalResult.isPresent()) {
                RemoteStatus resultStatus = terminalResult.orElseThrow().status();
                if (terminalStatus.isPresent() && terminalStatus.orElseThrow() != resultStatus) {
                    throw new IllegalArgumentException(
                            "terminal status does not match the nested terminal result");
                }
                terminalStatus = Optional.of(resultStatus);
            }
        }

        /** Compatibility constructor for the original v2 query shape. */
        public QueryResult(
                int version,
                UUID queryId,
                QueryKind queryKind,
                QueryStatus status,
                long revision,
                long mutationSequence,
                Optional<RequestSnapshot> request,
                Optional<Result> terminalResult,
                List<RemoteBinding> bindings,
                Optional<String> inferredType,
                Optional<String> detail) {
            this(version, queryId, queryKind, status, revision, mutationSequence, request,
                    terminalResult, bindings, inferredType, detail, Optional.empty());
        }
    }

    /** Bounded metadata-only completion request; it never compiles or pins. */
    record CompletionRequest(
            int version,
            UUID completionId,
            CompletionKind kind,
            long expectedRevision,
            long expectedMutationSequence,
            Optional<String> prefix,
            Optional<String> binding) implements ProtocolMessage {
        public CompletionRequest {
            requireVersion(version);
            completionId = Objects.requireNonNull(completionId, "completionId");
            kind = Objects.requireNonNull(kind, "kind");
            expectedRevision = ProtocolValues.nonNegativeLong(expectedRevision, "expectedRevision");
            expectedMutationSequence = ProtocolValues.nonNegativeLong(
                    expectedMutationSequence, "expectedMutationSequence");
            prefix = Objects.requireNonNull(prefix, "prefix").map(value ->
                    ProtocolValues.text(value, "prefix", RemoteProtocol.MAX_COMPLETION_PREFIX_CHARACTERS));
            binding = Objects.requireNonNull(binding, "binding").map(value ->
                    ProtocolValues.token(value, "binding", RemoteProtocol.MAX_COMPLETION_ITEM_CHARACTERS));
            if (kind == CompletionKind.BINDING_MEMBERS && binding.isEmpty()) {
                throw new IllegalArgumentException("member completion needs a binding name");
            }
            if (kind != CompletionKind.BINDING_MEMBERS && binding.isPresent()) {
                throw new IllegalArgumentException("binding name is only valid for member completion");
            }
        }
    }

    record CompletionResult(
            int version,
            UUID completionId,
            QueryStatus status,
            long revision,
            long mutationSequence,
            List<CompletionItem> items,
            Optional<String> detail) implements ProtocolMessage {
        public CompletionResult {
            requireVersion(version);
            completionId = Objects.requireNonNull(completionId, "completionId");
            status = Objects.requireNonNull(status, "status");
            revision = ProtocolValues.nonNegativeLong(revision, "revision");
            mutationSequence = ProtocolValues.nonNegativeLong(mutationSequence, "mutationSequence");
            items = copyCompletionItems(items);
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }
    }

    record CompletionItem(String name, CompletionItemKind kind, Optional<String> typeSpelling) {
        public CompletionItem {
            name = ProtocolValues.token(name, "completion item name",
                    RemoteProtocol.MAX_COMPLETION_ITEM_CHARACTERS);
            kind = Objects.requireNonNull(kind, "kind");
            typeSpelling = Objects.requireNonNull(typeSpelling, "typeSpelling").map(value ->
                    ProtocolValues.token(value, "completion item type", 4096));
        }

        public CompletionItem(String name, CompletionItemKind kind) {
            this(name, kind, Optional.empty());
        }
    }

    enum CompletionItemKind {
        FILE,
        DIRECTORY,
        MODULE,
        MEMBER
    }

    enum CompletionKind {
        MODULE_FILES,
        BINDING_MEMBERS
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
        STALE,
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

    private static List<Initializer> copyInitializers(List<Initializer> values) {
        Objects.requireNonNull(values, "initializers");
        if (values.size() > RemoteProtocol.MAX_INITIALIZERS) {
            throw new IllegalArgumentException("too many initializer entries");
        }
        ArrayList<Initializer> copy = new ArrayList<>(values.size());
        for (Initializer value : values) {
            copy.add(Objects.requireNonNull(value, "initializers must not contain null"));
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

    private static List<CompletionItem> copyCompletionItems(List<CompletionItem> values) {
        Objects.requireNonNull(values, "items");
        if (values.size() > RemoteProtocol.MAX_COMPLETION_ITEMS) {
            throw new IllegalArgumentException("too many completion items");
        }
        ArrayList<CompletionItem> copy = new ArrayList<>(values.size());
        for (CompletionItem value : values) {
            copy.add(Objects.requireNonNull(value, "items must not contain null"));
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
