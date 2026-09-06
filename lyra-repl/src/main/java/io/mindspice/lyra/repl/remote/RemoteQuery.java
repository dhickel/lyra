package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transport-independent internal query request/result for an attached session. */
public record RemoteQuery(Kind kind, Optional<EvaluationSource> source) {
    public RemoteQuery {
        kind = Objects.requireNonNull(kind, "kind");
        source = Objects.requireNonNull(source, "source");
        if (kind == Kind.TYPE && source.isEmpty()) {
            throw new IllegalArgumentException("type query needs source");
        }
        if (kind != Kind.TYPE && source.isPresent()) {
            throw new IllegalArgumentException("source is only valid for type queries");
        }
    }

    public enum Kind {
        BINDINGS,
        TYPE
    }

    public enum Status {
        OK,
        UNAVAILABLE,
        CLOSED
    }

    public record Result(
            Status status,
            List<RemoteBinding> bindings,
            Optional<String> inferredType,
            Optional<String> detail) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
            if (bindings.size() > RemoteProtocol.MAX_QUERY_BINDINGS) {
                throw new IllegalArgumentException("too many bindings");
            }
            for (RemoteBinding binding : bindings) {
                Objects.requireNonNull(binding, "bindings must not contain null");
            }
            inferredType = Objects.requireNonNull(inferredType, "inferredType")
                    .map(value -> ProtocolValues.text(value, "inferredType", 4096));
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }

        public static Result unavailable(String detail) {
            return new Result(Status.UNAVAILABLE, List.of(), Optional.empty(), Optional.of(detail));
        }
    }
}
