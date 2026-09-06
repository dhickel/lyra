package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SourceOrigin;
import io.mindspice.lyra.repl.SessionRevision;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Explicit schema codec for {@link ProtocolMessage}; no reflection is used. */
public final class ProtocolCodec {
    private ProtocolCodec() {
    }

    public static byte[] encode(ProtocolMessage message) {
        Objects.requireNonNull(message, "message");
        String json = StrictJson.write(toJson(message));
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(json));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("protocol message contains invalid UTF-16", exception);
        }
        if (bytes.length == 0 || bytes.length > RemoteProtocol.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("encoded protocol message exceeds frame bound");
        }
        return bytes;
    }

    public static ProtocolMessage decode(byte[] payload) throws ProtocolException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > RemoteProtocol.MAX_FRAME_BYTES) {
            throw new ProtocolException(ProtocolException.Reason.FRAME_TOO_LARGE,
                    "protocol payload exceeds frame bound");
        }
        String json = FrameCodec.decodeUtf8(payload);
        StrictJson.Value parsed = StrictJson.parse(json);
        if (!(parsed instanceof StrictJson.ObjectValue object)) {
            throw schema("protocol message must be a JSON object");
        }
        try {
            return fromJson(object);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(ProtocolException.Reason.INVALID_SCHEMA,
                    "protocol message has an invalid schema", exception);
        }
    }

    public static void write(FrameCodec frames, OutputStream output, ProtocolMessage message)
            throws IOException {
        Objects.requireNonNull(frames, "frames");
        frames.writeFrame(output, encode(message));
    }

    public static Optional<ProtocolMessage> read(FrameCodec frames, InputStream input)
            throws IOException, ProtocolException {
        Objects.requireNonNull(frames, "frames");
        Optional<byte[]> payload = frames.readFrame(input);
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(decode(payload.orElseThrow()));
    }

    private static StrictJson.Value toJson(ProtocolMessage message) {
        return switch (message) {
            case ProtocolMessage.ClientHello value -> object(
                    "kind", string("clientHello"), "version", number(value.version()),
                    "clientId", string(value.clientId().toString()),
                    optional("sessionId", value.sessionId().map(UUID::toString)));
            case ProtocolMessage.ServerHello value -> object(
                    "kind", string("serverHello"), "version", number(value.version()),
                    "sessionId", string(value.sessionId().toString()),
                    "challenge", string(Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(value.challenge())));
            case ProtocolMessage.Authenticate value -> object(
                    "kind", string("authenticate"), "version", number(value.version()),
                    "sessionId", string(value.sessionId().toString()),
                    "challenge", string(Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(value.challenge())),
                    "token", string(value.token()));
            case ProtocolMessage.Authenticated value -> object(
                    "kind", string("authenticated"), "version", number(value.version()),
                    "sessionId", string(value.sessionId().toString()),
                    "revision", number(value.revision()),
                    "lastSequence", number(value.lastSequence()),
                    optional("activeRequest", value.activeRequestId().map(UUID::toString)));
            case ProtocolMessage.EvaluateRequest value -> object(
                    "kind", string("evaluate"), "version", number(value.version()),
                    "requestId", string(value.requestId().toString()),
                    "sequence", number(value.sequence()),
                    "revision", number(value.revision().value()),
                    "source", sourceToJson(value.source()));
            case ProtocolMessage.Accepted value -> object(
                    "kind", string("accepted"), "version", number(value.version()),
                    "requestId", string(value.requestId().toString()),
                    "sequence", number(value.sequence()),
                    "status", string(value.status().name()),
                    "revision", number(value.revision()));
            case ProtocolMessage.Status value -> object(
                    "kind", string("status"), "version", number(value.version()),
                    "requestId", string(value.requestId().toString()),
                    "sequence", number(value.sequence()),
                    "status", string(value.status().name()),
                    "revision", number(value.revision()),
                    optional("detail", value.detail()));
            case ProtocolMessage.Result value -> resultToJson(value);
            case ProtocolMessage.CancelRequest value -> object(
                    "kind", string("cancel"), "version", number(value.version()),
                    "operationId", string(value.operationId().toString()),
                    "requestId", string(value.requestId().toString()));
            case ProtocolMessage.CancelResult value -> object(
                    "kind", string("cancelResult"), "version", number(value.version()),
                    "operationId", string(value.operationId().toString()),
                    "requestId", string(value.requestId().toString()),
                    "status", string(value.status().name()),
                    "revision", number(value.revision()),
                    optional("detail", value.detail()));
            case ProtocolMessage.ResetRequest value -> object(
                    "kind", string("reset"), "version", number(value.version()),
                    "operationId", string(value.operationId().toString()),
                    "expectedRevision", number(value.expectedRevision()));
            case ProtocolMessage.ResetResult value -> object(
                    "kind", string("resetResult"), "version", number(value.version()),
                    "operationId", string(value.operationId().toString()),
                    "status", string(value.status().name()),
                    "revision", number(value.revision()),
                    optional("detail", value.detail()));
            case ProtocolMessage.QueryRequest value -> object(
                    "kind", string("query"), "version", number(value.version()),
                    "queryId", string(value.queryId().toString()),
                    "queryKind", string(value.queryKind().name()),
                    optional("requestId", value.requestId().map(UUID::toString)),
                    optional("source", value.typeSource().map(ProtocolCodec::sourceToJson)));
            case ProtocolMessage.QueryResult value -> queryResultToJson(value);
            case ProtocolMessage.Error value -> object(
                    "kind", string("error"), "version", number(value.version()),
                    "code", string(value.code().name()), "detail", string(value.detail()),
                    optional("requestId", value.requestId().map(UUID::toString)),
                    optional("correlationId", value.correlationId().map(UUID::toString)),
                    "retryable", bool(value.retryable()));
        };
    }

    private static StrictJson.ObjectValue resultToJson(ProtocolMessage.Result value) {
        List<StrictJson.Value> diagnostics = value.diagnostics().stream()
                .map(diagnostic -> (StrictJson.Value) diagnosticToJson(diagnostic)).toList();
        return object(
                "kind", string("result"), "version", number(value.version()),
                "requestId", string(value.requestId().toString()),
                "sequence", number(value.sequence()),
                "status", string(value.status().name()),
                "revision", number(value.revision()),
                "diagnostics", array(diagnostics),
                optional("value", value.value().map(ProtocolCodec::valueToJson)),
                optional("failureSummary", value.failureSummary()),
                optional("activeRequest", value.activeRequestId().map(UUID::toString)));
    }

    private static StrictJson.ObjectValue queryResultToJson(ProtocolMessage.QueryResult value) {
        return object(
                "kind", string("queryResult"), "version", number(value.version()),
                "queryId", string(value.queryId().toString()),
                "queryKind", string(value.queryKind().name()),
                "status", string(value.status().name()),
                "revision", number(value.revision()),
                optional("request", value.request().map(ProtocolCodec::requestSnapshotToJson)),
                optional("terminalResult", value.terminalResult().map(ProtocolCodec::resultToJson)),
                "bindings", array(value.bindings().stream()
                        .map(item -> (StrictJson.Value) bindingToJson(item)).toList()),
                optional("inferredType", value.inferredType()),
                optional("detail", value.detail()));
    }

    private static StrictJson.ObjectValue sourceToJson(EvaluationSource source) {
        SourceOrigin origin = source.origin();
        return object(
                "label", string(origin.label()),
                "text", string(source.text()),
                optional("uri", origin.uri().map(URI::toString)),
                optional("documentVersion", origin.documentVersion()),
                "originStart", number(origin.originStartOffset()),
                "originEnd", number(origin.originEndOffset()));
    }

    private static StrictJson.ObjectValue diagnosticToJson(ProtocolMessage.Diagnostic value) {
        return object(
                "code", string(value.code()), "severity", string(value.severity()),
                "summary", string(value.summary()), "primary", spanToJson(value.primarySpan()),
                "related", array(value.relatedSpans().stream()
                        .map(related -> (StrictJson.Value) object("span", spanToJson(related.span()),
                                "label", string(related.label())))
                        .toList()));
    }

    private static StrictJson.ObjectValue spanToJson(ProtocolMessage.Span value) {
        return object("sourceId", string(value.sourceId()), "start", number(value.startOffset()),
                "end", number(value.endOffset()));
    }

    private static StrictJson.ObjectValue requestSnapshotToJson(ProtocolMessage.RequestSnapshot value) {
        return object("requestId", string(value.requestId().toString()),
                "sequence", number(value.sequence()), "status", string(value.status().name()),
                "revision", number(value.revision()), optional("detail", value.detail()));
    }

    private static StrictJson.ObjectValue bindingToJson(RemoteBinding value) {
        return object("name", string(value.name()), "type", string(value.canonicalType()),
                "visibility", string(value.visibility()), "mutable", bool(value.mutable()));
    }

    private static StrictJson.ObjectValue valueToJson(ProtocolMessage.ValueSnapshot value) {
        return object("type", string(value.canonicalType()), "data", valueDataToJson(value.data()));
    }

    private static StrictJson.ObjectValue valueDataToJson(ProtocolMessage.ValueData value) {
        return switch (value) {
            case ProtocolMessage.Nil ignored -> object("kind", string("nil"));
            case ProtocolMessage.Unit ignored -> object("kind", string("unit"));
            case ProtocolMessage.Scalar scalar -> object(
                    "kind", string("scalar"), "scalarKind", string(scalar.scalarKind()),
                    "value", string(scalar.value()));
            case ProtocolMessage.Aggregate aggregate -> object(
                    "kind", string("aggregate"), "aggregateKind", string(aggregate.aggregateKind()),
                    "identity", string(aggregate.identity()),
                    optional("alias", aggregate.alias()),
                    "elements", array(aggregate.elements().stream()
                            .map(item -> (StrictJson.Value) valueToJson(item)).toList()),
                    optional("truncation", aggregate.truncation()));
            case ProtocolMessage.Function function -> object(
                    "kind", string("function"), "identity", string(function.identity()),
                    optional("description", function.description()));
            case ProtocolMessage.Reference reference -> object(
                    "kind", string("reference"), "description", string(reference.description()));
            case ProtocolMessage.Truncated truncated -> object(
                    "kind", string("truncated"), "reason", string(truncated.reason()),
                    optional("description", truncated.description()));
        };
    }

    private static ProtocolMessage fromJson(StrictJson.ObjectValue object)
            throws ProtocolException {
        String kind = string(object, "kind");
        int version = version(object);
        if (version != RemoteProtocol.VERSION) {
            throw new ProtocolException(ProtocolException.Reason.UNSUPPORTED_VERSION,
                    "unsupported REPL protocol version");
        }
        return switch (kind) {
            case "clientHello" -> clientHello(object, version);
            case "serverHello" -> serverHello(object, version);
            case "authenticate" -> authenticate(object, version);
            case "authenticated" -> authenticated(object, version);
            case "evaluate" -> evaluate(object, version);
            case "accepted" -> accepted(object, version);
            case "status" -> status(object, version);
            case "result" -> result(object, version);
            case "cancel" -> cancel(object, version);
            case "cancelResult" -> cancelResult(object, version);
            case "reset" -> reset(object, version);
            case "resetResult" -> resetResult(object, version);
            case "query" -> query(object, version);
            case "queryResult" -> queryResult(object, version);
            case "error" -> error(object, version);
            default -> throw schema("unknown protocol message kind: " + kind);
        };
    }

    private static ProtocolMessage.ClientHello clientHello(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "clientId", "sessionId");
        return new ProtocolMessage.ClientHello(version, uuid(object, "clientId"),
                optionalUuid(object, "sessionId"));
    }

    private static ProtocolMessage.ServerHello serverHello(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "sessionId", "challenge");
        return new ProtocolMessage.ServerHello(version, uuid(object, "sessionId"),
                ProtocolValues.decodeBase64(string(object, "challenge"), "challenge",
                        RemoteProtocol.CHALLENGE_BYTES));
    }

    private static ProtocolMessage.Authenticate authenticate(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "sessionId", "challenge", "token");
        return new ProtocolMessage.Authenticate(version, uuid(object, "sessionId"),
                ProtocolValues.decodeBase64(string(object, "challenge"), "challenge",
                        RemoteProtocol.CHALLENGE_BYTES), string(object, "token"));
    }

    private static ProtocolMessage.Authenticated authenticated(
            StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "sessionId", "revision", "lastSequence", "activeRequest");
        return new ProtocolMessage.Authenticated(version, uuid(object, "sessionId"),
                nonNegativeLong(object, "revision"), nonNegativeLong(object, "lastSequence"),
                optionalUuid(object, "activeRequest"));
    }

    private static ProtocolMessage.EvaluateRequest evaluate(
            StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "requestId", "sequence", "revision", "source");
        return new ProtocolMessage.EvaluateRequest(version, uuid(object, "requestId"),
                positiveLong(object, "sequence"), new SessionRevision(nonNegativeLong(object, "revision")),
                source(object, "source"));
    }

    private static ProtocolMessage.Accepted accepted(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "requestId", "sequence", "status", "revision");
        return new ProtocolMessage.Accepted(version, uuid(object, "requestId"),
                positiveLong(object, "sequence"), enumValue(ProtocolMessage.RemoteStatus.class,
                        string(object, "status"), "status"), nonNegativeLong(object, "revision"));
    }

    private static ProtocolMessage.Status status(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "requestId", "sequence", "status", "revision", "detail");
        return new ProtocolMessage.Status(version, uuid(object, "requestId"),
                positiveLong(object, "sequence"), enumValue(ProtocolMessage.RemoteStatus.class,
                        string(object, "status"), "status"), nonNegativeLong(object, "revision"),
                optionalString(object, "detail"));
    }

    private static ProtocolMessage.Result result(StrictJson.ObjectValue object, int version)
            throws ProtocolException {
        keys(object, "kind", "version", "requestId", "sequence", "status", "revision",
                "diagnostics", "value", "failureSummary", "activeRequest");
        ProtocolMessage.RemoteStatus status = enumValue(ProtocolMessage.RemoteStatus.class,
                string(object, "status"), "status");
        if (!status.isTerminal()) {
            throw schema("result status is not terminal");
        }
        return new ProtocolMessage.Result(version, uuid(object, "requestId"),
                positiveLong(object, "sequence"), status, nonNegativeLong(object, "revision"),
                diagnostics(object, "diagnostics"), optionalValue(object, "value"),
                optionalString(object, "failureSummary"), optionalUuid(object, "activeRequest"));
    }

    private static ProtocolMessage.CancelRequest cancel(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "operationId", "requestId");
        return new ProtocolMessage.CancelRequest(version, uuid(object, "operationId"),
                uuid(object, "requestId"));
    }

    private static ProtocolMessage.CancelResult cancelResult(
            StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "operationId", "requestId", "status", "revision", "detail");
        return new ProtocolMessage.CancelResult(version, uuid(object, "operationId"),
                uuid(object, "requestId"), enumValue(ProtocolMessage.ControlStatus.class,
                        string(object, "status"), "status"), nonNegativeLong(object, "revision"),
                optionalString(object, "detail"));
    }

    private static ProtocolMessage.ResetRequest reset(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "operationId", "expectedRevision");
        return new ProtocolMessage.ResetRequest(version, uuid(object, "operationId"),
                nonNegativeLong(object, "expectedRevision"));
    }

    private static ProtocolMessage.ResetResult resetResult(
            StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "operationId", "status", "revision", "detail");
        return new ProtocolMessage.ResetResult(version, uuid(object, "operationId"),
                enumValue(ProtocolMessage.ControlStatus.class, string(object, "status"), "status"),
                nonNegativeLong(object, "revision"), optionalString(object, "detail"));
    }

    private static ProtocolMessage.QueryRequest query(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "queryId", "queryKind", "requestId", "source");
        ProtocolMessage.QueryKind kind = enumValue(ProtocolMessage.QueryKind.class,
                string(object, "queryKind"), "queryKind");
        return new ProtocolMessage.QueryRequest(version, uuid(object, "queryId"), kind,
                optionalUuid(object, "requestId"), optionalSource(object, "source"));
    }

    private static ProtocolMessage.QueryResult queryResult(
            StrictJson.ObjectValue object, int version) throws ProtocolException {
        keys(object, "kind", "version", "queryId", "queryKind", "status", "revision",
                "request", "terminalResult", "bindings", "inferredType", "detail");
        return new ProtocolMessage.QueryResult(version, uuid(object, "queryId"),
                enumValue(ProtocolMessage.QueryKind.class, string(object, "queryKind"), "queryKind"),
                enumValue(ProtocolMessage.QueryStatus.class, string(object, "status"), "status"),
                nonNegativeLong(object, "revision"), optionalRequestSnapshot(object, "request"),
                optionalResult(object, "terminalResult"), bindings(object, "bindings"),
                optionalString(object, "inferredType"), optionalString(object, "detail"));
    }

    private static ProtocolMessage.Error error(StrictJson.ObjectValue object, int version) {
        keys(object, "kind", "version", "code", "detail", "requestId", "correlationId", "retryable");
        return new ProtocolMessage.Error(version,
                enumValue(ProtocolMessage.ErrorCode.class, string(object, "code"), "code"),
                string(object, "detail"), optionalUuid(object, "requestId"),
                optionalUuid(object, "correlationId"), bool(object, "retryable"));
    }

    private static EvaluationSource source(StrictJson.ObjectValue parent, String field) {
        StrictJson.ObjectValue object = object(parent, field);
        keys(object, "label", "text", "uri", "documentVersion", "originStart", "originEnd");
        String text = string(object, "text");
        String label = string(object, "label");
        Optional<URI> uri = optionalString(object, "uri").map(ProtocolValues::uri);
        Optional<Long> documentVersion = optionalLong(object, "documentVersion");
        long start = nonNegativeLong(object, "originStart");
        long end = nonNegativeLong(object, "originEnd");
        if (start > Integer.MAX_VALUE || end > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("source origin offset is too large");
        }
        return new EvaluationSource(new SourceOrigin(label, uri, documentVersion,
                (int) start, (int) end), text);
    }

    private static Optional<EvaluationSource> optionalSource(
            StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(sourceValue(value));
    }

    private static EvaluationSource sourceValue(StrictJson.Value value) {
        return source(new StrictJson.ObjectValue(Map.of("source", value)), "source");
    }

    private static List<ProtocolMessage.Diagnostic> diagnostics(
            StrictJson.ObjectValue object, String field) {
        List<StrictJson.Value> values = array(object, field);
        if (values.size() > RemoteProtocol.MAX_DIAGNOSTICS) {
            throw new IllegalArgumentException("too many diagnostics");
        }
        return values.stream().map(ProtocolCodec::diagnostic).toList();
    }

    private static ProtocolMessage.Diagnostic diagnostic(StrictJson.Value value) {
        StrictJson.ObjectValue object = requireObject(value, "diagnostic");
        keys(object, "code", "severity", "summary", "primary", "related");
        List<ProtocolMessage.RelatedSpan> related = array(object, "related").stream()
                .map(ProtocolCodec::relatedSpan).toList();
        return new ProtocolMessage.Diagnostic(string(object, "code"), string(object, "severity"),
                string(object, "summary"), span(object, "primary"), related);
    }

    private static ProtocolMessage.RelatedSpan relatedSpan(StrictJson.Value value) {
        StrictJson.ObjectValue object = requireObject(value, "related span");
        keys(object, "span", "label");
        return new ProtocolMessage.RelatedSpan(span(object, "span"), string(object, "label"));
    }

    private static ProtocolMessage.Span span(StrictJson.ObjectValue parent, String field) {
        StrictJson.ObjectValue object = object(parent, field);
        keys(object, "sourceId", "start", "end");
        long start = nonNegativeLong(object, "start");
        long end = nonNegativeLong(object, "end");
        if (start > Integer.MAX_VALUE || end > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("diagnostic span offset is too large");
        }
        return new ProtocolMessage.Span(string(object, "sourceId"), (int) start, (int) end);
    }

    private static Optional<ProtocolMessage.ValueSnapshot> optionalValue(
            StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        return value == null ? Optional.empty() : Optional.of(valueSnapshot(value));
    }

    private static ProtocolMessage.ValueSnapshot valueSnapshot(StrictJson.Value value) {
        StrictJson.ObjectValue object = requireObject(value, "value snapshot");
        keys(object, "type", "data");
        return new ProtocolMessage.ValueSnapshot(string(object, "type"), valueData(object, "data"));
    }

    private static ProtocolMessage.ValueData valueData(
            StrictJson.ObjectValue parent, String field) {
        StrictJson.ObjectValue object = object(parent, field);
        String kind = string(object, "kind");
        return switch (kind) {
            case "nil" -> {
                keys(object, "kind");
                yield new ProtocolMessage.Nil();
            }
            case "unit" -> {
                keys(object, "kind");
                yield new ProtocolMessage.Unit();
            }
            case "scalar" -> {
                keys(object, "kind", "scalarKind", "value");
                yield new ProtocolMessage.Scalar(string(object, "scalarKind"),
                        string(object, "value"));
            }
            case "aggregate" -> {
                keys(object, "kind", "aggregateKind", "identity", "alias", "elements", "truncation");
                yield new ProtocolMessage.Aggregate(string(object, "aggregateKind"),
                        string(object, "identity"), optionalString(object, "alias"),
                        array(object, "elements").stream().map(ProtocolCodec::valueSnapshot).toList(),
                        optionalString(object, "truncation"));
            }
            case "function" -> {
                keys(object, "kind", "identity", "description");
                yield new ProtocolMessage.Function(string(object, "identity"),
                        optionalString(object, "description"));
            }
            case "reference" -> {
                keys(object, "kind", "description");
                yield new ProtocolMessage.Reference(string(object, "description"));
            }
            case "truncated" -> {
                keys(object, "kind", "reason", "description");
                yield new ProtocolMessage.Truncated(string(object, "reason"),
                        optionalString(object, "description"));
            }
            default -> throw new IllegalArgumentException("unknown value data kind");
        };
    }

    private static Optional<ProtocolMessage.RequestSnapshot> optionalRequestSnapshot(
            StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (value == null) {
            return Optional.empty();
        }
        StrictJson.ObjectValue nested = requireObject(value, field);
        keys(nested, "requestId", "sequence", "status", "revision", "detail");
        return Optional.of(new ProtocolMessage.RequestSnapshot(uuid(nested, "requestId"),
                positiveLong(nested, "sequence"), enumValue(ProtocolMessage.RemoteStatus.class,
                        string(nested, "status"), "status"), nonNegativeLong(nested, "revision"),
                optionalString(nested, "detail")));
    }

    private static Optional<ProtocolMessage.Result> optionalResult(
            StrictJson.ObjectValue object, String field) throws ProtocolException {
        StrictJson.Value value = object.members().get(field);
        if (value == null) {
            return Optional.empty();
        }
        StrictJson.ObjectValue nested = requireObject(value, field);
        int nestedVersion = version(nested);
        if (nestedVersion != RemoteProtocol.VERSION
                || !"result".equals(string(nested, "kind"))) {
            throw new IllegalArgumentException("nested result has an invalid kind or version");
        }
        return Optional.of(result(nested, nestedVersion));
    }

    private static List<RemoteBinding> bindings(StrictJson.ObjectValue object, String field) {
        List<StrictJson.Value> values = array(object, field);
        if (values.size() > RemoteProtocol.MAX_QUERY_BINDINGS) {
            throw new IllegalArgumentException("too many bindings");
        }
        return values.stream().map(ProtocolCodec::binding).toList();
    }

    private static RemoteBinding binding(StrictJson.Value value) {
        StrictJson.ObjectValue object = requireObject(value, "binding");
        keys(object, "name", "type", "visibility", "mutable");
        return new RemoteBinding(string(object, "name"), string(object, "type"),
                string(object, "visibility"), bool(object, "mutable"));
    }

    private static int version(StrictJson.ObjectValue object) {
        long value = longValue(object, "version");
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("protocol version is out of range");
        }
        return (int) value;
    }

    private static UUID uuid(StrictJson.ObjectValue object, String field) {
        return ProtocolValues.uuid(string(object, field), field);
    }

    private static Optional<UUID> optionalUuid(StrictJson.ObjectValue object, String field) {
        return optionalString(object, field).map(value -> ProtocolValues.uuid(value, field));
    }

    private static long positiveLong(StrictJson.ObjectValue object, String field) {
        return ProtocolValues.positiveLong(longValue(object, field), field);
    }

    private static long nonNegativeLong(StrictJson.ObjectValue object, String field) {
        return ProtocolValues.nonNegativeLong(longValue(object, field), field);
    }

    private static long longValue(StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (!(value instanceof StrictJson.NumberValue number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return Long.parseLong(number.value());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is outside the integer range", exception);
        }
    }

    private static boolean bool(StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (!(value instanceof StrictJson.BooleanValue bool)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return bool.value();
    }

    private static String string(StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (!(value instanceof StrictJson.StringValue string)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return string.value();
    }

    private static Optional<String> optionalString(StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof StrictJson.StringValue string)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return Optional.of(string.value());
    }

    private static Optional<Long> optionalLong(StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof StrictJson.NumberValue)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return Optional.of(longValue(object, field));
    }

    private static StrictJson.ObjectValue object(StrictJson.ObjectValue parent, String field) {
        return requireObject(parent.members().get(field), field);
    }

    private static StrictJson.ObjectValue requireObject(StrictJson.Value value, String field) {
        if (!(value instanceof StrictJson.ObjectValue object)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return object;
    }

    private static List<StrictJson.Value> array(StrictJson.ObjectValue object, String field) {
        StrictJson.Value value = object.members().get(field);
        if (!(value instanceof StrictJson.ArrayValue array)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return array.values();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " has an unsupported value", exception);
        }
    }

    private static void keys(StrictJson.ObjectValue object, String... allowed) {
        Set<String> allowedKeys = Set.of(allowed);
        for (String key : object.members().keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("unknown protocol field: " + key);
            }
        }
        // Requiredness is checked at the typed accessors. This keeps optional
        // fields explicit while still rejecting all unknown fields.
    }

    private static StrictJson.ObjectValue object(Object... entries) {
        LinkedHashMap<String, StrictJson.Value> values = new LinkedHashMap<>();
        int index = 0;
        while (index < entries.length) {
            if (entries[index] instanceof OptionalField optional) {
                if (optional.value != null) {
                    values.put(optional.key, optional.value);
                }
                index++;
                continue;
            }
            if (index + 1 >= entries.length) {
                throw new IllegalArgumentException("JSON object entries must be paired");
            }
            String key = Objects.requireNonNull((String) entries[index], "JSON field name");
            StrictJson.Value value = (StrictJson.Value) entries[index + 1];
            if (value == null) {
                throw new IllegalArgumentException("JSON field value must not be null");
            }
            values.put(key, value);
            index += 2;
        }
        return new StrictJson.ObjectValue(values);
    }

    private static OptionalField optional(String key, Optional<?> value) {
        Objects.requireNonNull(value, key);
        if (value.isEmpty()) {
            return new OptionalField(key, null);
        }
        Object item = value.orElseThrow();
        StrictJson.Value converted;
        if (item instanceof String string) {
            converted = string(string);
        } else if (item instanceof UUID uuid) {
            converted = string(uuid.toString());
        } else if (item instanceof Long number) {
            converted = number(number);
        } else if (item instanceof Integer number) {
            converted = number(number.longValue());
        } else if (item instanceof StrictJson.Value json) {
            converted = json;
        } else {
            throw new IllegalArgumentException("unsupported optional JSON value: " + item.getClass());
        }
        return new OptionalField(key, converted);
    }

    private record OptionalField(String key, StrictJson.Value value) {
        private OptionalField {
            Objects.requireNonNull(key, "key");
        }
    }

    private static StrictJson.Value string(String value) {
        return new StrictJson.StringValue(Objects.requireNonNull(value, "value"));
    }

    private static StrictJson.Value number(long value) {
        return new StrictJson.NumberValue(Long.toString(value));
    }

    private static StrictJson.Value bool(boolean value) {
        return new StrictJson.BooleanValue(value);
    }

    private static StrictJson.ArrayValue array(List<StrictJson.Value> values) {
        return new StrictJson.ArrayValue(values);
    }

    private static ProtocolException schema(String message) {
        return new ProtocolException(ProtocolException.Reason.INVALID_SCHEMA, message);
    }
}
