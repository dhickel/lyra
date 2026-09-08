package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.ApplicationAttachment;
import io.mindspice.lyra.repl.Cancellation;
import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.compiler.session.ExternalBinding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-dispatched adapter over a live application-root attachment.
 *
 * <p>Evaluations run synchronously on the application owner thread through
 * {@link ApplicationAttachment#submit}; the protocol server's owner
 * dispatcher is the only caller, so live root work never crosses a socket
 * thread. Cancellation binds to the exact admitted identity. Load reads a
 * server-side UTF-8 file exactly once into a captured file-URI source.
 * Application-owned modules can never be reloaded, and that target is
 * reported explicitly rather than replayed or silently replaced. Completion
 * reads committed metadata or performs a bounded filesystem lookup only.</p>
 */
public final class ApplicationAttachmentAdapter implements RemoteSessionAdapter {
    private final ApplicationAttachment attachment;
    private final UUID sessionId = UUID.randomUUID();

    private ApplicationAttachmentAdapter(ApplicationAttachment attachment) {
        this.attachment = Objects.requireNonNull(attachment, "attachment");
    }

    public static ApplicationAttachmentAdapter of(ApplicationAttachment attachment) {
        return new ApplicationAttachmentAdapter(attachment);
    }

    @Override
    public UUID sessionId() {
        return sessionId;
    }

    @Override
    public SessionRevision revision() {
        return attachment.revision();
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
            return new EvaluationResult.Cancelled(request, attachment.revision(),
                    Cancellation.observed(request.evaluationId()));
        }
        try {
            // Transport composition: when this operation is dispatched on the
            // attachment's own shared controller, the poll's lease is the
            // admitted evaluation context and must be reused; otherwise the
            // synchronous submission owns its lease.
            return attachment.hasAdmittedLease()
                    ? attachment.submitAdmitted(request, cancellation::isRequested)
                    : attachment.submit(request, cancellation::isRequested);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            // Stale revision or closed/busy admission inside the attachment
            // must surface as an explicit remote outcome, never a wire hang.
            throw new RemoteSessionUnavailableException(failure.getMessage());
        }
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
            return new EvaluationResult.Cancelled(evaluation, attachment.revision(),
                    Cancellation.observed(evaluation.evaluationId()));
        }
        try {
            return attachment.hasAdmittedLease()
                    ? attachment.submitAdmitted(evaluation, cancellation::isRequested)
                    : attachment.submit(evaluation, cancellation::isRequested);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new RemoteSessionUnavailableException(failure.getMessage());
        }
    }

    @Override
    public EvaluationResult reload(ProtocolMessage.ReloadRequest request,
                                   RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        // Application-owned graphs are borrowed, never reloadable, and the
        // attachment has no scratch-module reload surface. This is an
        // explicit structured outcome, not a silent replay or fake rebuild.
        throw new RemoteSessionUnavailableException(
                "application attachment cannot reload modules; target was not rebuilt");
    }

    @Override
    public boolean cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        return attachment.cancel(evaluationId);
    }

    @Override
    public void reset() {
        try {
            if (attachment.hasAdmittedLease()) {
                attachment.resetAdmitted();
            } else {
                attachment.reset();
            }
        } catch (RuntimeException failure) {
            throw new RemoteSessionUnavailableException(
                    "attachment reset is unavailable: " + failure.getMessage());
        }
    }

    @Override
    public RemoteQuery.Result query(RemoteQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.kind() == RemoteQuery.Kind.BINDINGS) {
            List<RemoteBinding> bindings = attachment.context().rootBindings().stream()
                    .filter(binding -> binding.visibility() == ExternalBinding.Visibility.PUBLIC)
                    .map(binding -> new RemoteBinding(binding.name(),
                            binding.type().canonicalSpelling(),
                            binding.visibility().name(), binding.allowsRebinding()))
                    .limit(RemoteProtocol.MAX_QUERY_BINDINGS)
                    .toList();
            return new RemoteQuery.Result(RemoteQuery.Status.OK, bindings,
                    Optional.empty(), Optional.empty());
        }
        return RemoteQuery.Result.unavailable(
                "the attached root does not expose type queries");
    }

    @Override
    public RemoteCompletion.Result complete(RemoteCompletion request) {
        Objects.requireNonNull(request, "request");
        return switch (request.kind()) {
            case MODULE_FILES -> RemoteFileCompletion.moduleFiles(
                    attachment.sourceRoots(), request.prefix());
            case BINDING_MEMBERS -> memberCompletion(request.binding().orElseThrow());
        };
    }

    private RemoteCompletion.Result memberCompletion(String bindingName) {
        Optional<ExternalBinding> target = attachment.context().rootBindings().stream()
                .filter(binding -> binding.name().equals(bindingName)
                        && binding.visibility() == ExternalBinding.Visibility.PUBLIC)
                .findFirst();
        if (target.isEmpty()) {
            return RemoteCompletion.Result.notFound("unknown committed binding: " + bindingName);
        }
        List<RemoteCompletion.Item> members = RemoteCompletion.members(
                target.orElseThrow().type().canonicalSpelling());
        if (members.isEmpty()) {
            return new RemoteCompletion.Result(RemoteCompletion.Status.OK, List.of(),
                    Optional.of("binding has no metadata-completable members"));
        }
        return new RemoteCompletion.Result(RemoteCompletion.Status.OK, members, Optional.empty());
    }
}
