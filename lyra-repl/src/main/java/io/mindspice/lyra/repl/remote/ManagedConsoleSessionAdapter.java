package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.Cancellation;
import io.mindspice.lyra.repl.ConsoleSession;
import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.ManagedConsoleSession;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-routed adapter over a managed local console. Every live operation is
 * admitted by the managed console and executed on its dedicated owner loop;
 * the adapter never runs session work itself. Full-fidelity submissions
 * preserve exact values, diagnostics and initializer progress.
 */
public final class ManagedConsoleSessionAdapter implements RemoteSessionAdapter {
    private final ManagedConsoleSession console;
    private final UUID sessionId = UUID.randomUUID();

    private ManagedConsoleSessionAdapter(ManagedConsoleSession console) {
        this.console = Objects.requireNonNull(console, "console");
    }

    public static ManagedConsoleSessionAdapter of(ManagedConsoleSession console) {
        return new ManagedConsoleSessionAdapter(console);
    }

    @Override
    public UUID sessionId() {
        return sessionId;
    }

    @Override
    public SessionRevision revision() {
        return console.revision();
    }

    @Override
    public EvaluationResult evaluate(EvaluationRequest request, RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (!cancellation.admit()) {
            if (!cancellation.isRequested()) {
                throw new IllegalStateException("evaluation cancellation token was already admitted");
            }
            cancellation.observe();
            return new EvaluationResult.Cancelled(request, console.revision(),
                    Cancellation.observed(request.evaluationId()));
        }
        return console.submit(request, cancellation::isRequested);
    }

    @Override
    public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                 RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        return load(request, RemoteFileRead.readFile(request.path()), cancellation);
    }

    @Override
    public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                 RemoteCancellation cancellation,
                                 int maxFrameBytes) {
        EvaluationSource source = RemoteFileRead.readFile(request.path());
        RemoteServer.preflightLoadEnvelope(request, source, maxFrameBytes);
        return load(request, source, cancellation);
    }

    @Override
    public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                 EvaluationSource source,
                                 RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cancellation, "cancellation");
        EvaluationRequest evaluation = new EvaluationRequest(
                EvaluationId.of(request.requestId()), request.revision(), source);
        if (!cancellation.admit()) {
            if (!cancellation.isRequested()) {
                throw new IllegalStateException("evaluation cancellation token was already admitted");
            }
            cancellation.observe();
            return new EvaluationResult.Cancelled(evaluation, console.revision(),
                    Cancellation.observed(evaluation.evaluationId()));
        }
        return console.submit(evaluation, cancellation::isRequested);
    }

    @Override
    public EvaluationResult reload(ProtocolMessage.ReloadRequest request,
                                   RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (!cancellation.admit()) {
            if (!cancellation.isRequested()) {
                throw new IllegalStateException("evaluation cancellation token was already admitted");
            }
            cancellation.observe();
            return new EvaluationResult.Cancelled(
                    new EvaluationRequest(EvaluationId.of(request.requestId()),
                            request.revision(), EvaluationSource.of("reload " + request.target(), "")),
                    console.revision(), Cancellation.observed(EvaluationId.of(request.requestId())));
        }
        return console.submitReloadResult(request.target(),
                EvaluationId.of(request.requestId()), cancellation::isRequested);
    }

    @Override
    public boolean cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        ConsoleSession.Control control = console.cancel(evaluationId);
        return control.status() == ConsoleSession.ControlStatus.REQUESTED
                || control.status() == ConsoleSession.ControlStatus.OK;
    }

    @Override
    public void reset() {
        ConsoleSession.Control control = console.reset();
        if (control.status() == ConsoleSession.ControlStatus.BUSY
                || control.status() == ConsoleSession.ControlStatus.CLOSED) {
            throw new RemoteSessionUnavailableException(
                    "managed console reset is unavailable: " + control.status());
        }
    }

    @Override
    public RemoteQuery.Result query(RemoteQuery query) {
        Objects.requireNonNull(query, "query");
        ConsoleSession.QueryRequest request = query.kind() == RemoteQuery.Kind.BINDINGS
                ? ConsoleSession.QueryRequest.bindings()
                : ConsoleSession.QueryRequest.type(query.source().orElseThrow());
        ConsoleSession.Query result = console.query(request);
        var status = switch (result.status()) {
            case OK -> RemoteQuery.Status.OK;
            case CLOSED -> RemoteQuery.Status.CLOSED;
            case BUSY -> throw new RemoteSessionUnavailableException("managed console is busy");
            default -> RemoteQuery.Status.UNAVAILABLE;
        };
        if (result.bindings().stream().anyMatch(value -> value.name().length() > 1024
                || value.canonicalType().length() > 4096)
                || result.inferredType().filter(value -> value.length() > 4096).isPresent()) {
            return RemoteQuery.Result.unavailable("query metadata exceeds the transport limit");
        }
        List<RemoteBinding> bindings = result.bindings().stream()
                .limit(RemoteProtocol.MAX_QUERY_BINDINGS)
                .map(value -> new RemoteBinding(value.name(), value.canonicalType(),
                        value.visibility(), value.mutable()))
                .toList();
        Optional<String> detail = result.detail().map(value -> {
            if (value.length() <= 4096) return value;
            int end = Character.isHighSurrogate(value.charAt(4094)) ? 4094 : 4095;
            return value.substring(0, end) + "…";
        });
        return new RemoteQuery.Result(status, bindings, result.inferredType(), detail);
    }

    @Override
    public RemoteCompletion.Result complete(RemoteCompletion request) {
        Objects.requireNonNull(request, "request");
        return switch (request.kind()) {
            case MODULE_FILES -> RemoteFileCompletion.moduleFiles(
                    console.sourceRoots(), request.prefix());
            case BINDING_MEMBERS -> memberCompletion(request.binding().orElseThrow());
        };
    }

    private RemoteCompletion.Result memberCompletion(String bindingName) {
        ConsoleSession.Query bindings = console.query(ConsoleSession.QueryRequest.bindings());
        if (bindings.status() != ConsoleSession.QueryStatus.OK) {
            return RemoteCompletion.Result.unavailable("binding metadata is unavailable");
        }
        Optional<ConsoleSession.Binding> target = bindings.bindings().stream()
                .filter(binding -> binding.name().equals(bindingName))
                .findFirst();
        if (target.isEmpty()) {
            return RemoteCompletion.Result.notFound("unknown committed binding: " + bindingName);
        }
        List<RemoteCompletion.Item> members = RemoteCompletion.members(
                target.orElseThrow().canonicalType());
        if (members.isEmpty()) {
            return new RemoteCompletion.Result(RemoteCompletion.Status.OK, List.of(),
                    Optional.of("binding has no metadata-completable members"));
        }
        return new RemoteCompletion.Result(RemoteCompletion.Status.OK, members, Optional.empty());
    }
}
