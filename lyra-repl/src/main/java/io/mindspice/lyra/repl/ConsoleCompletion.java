package io.mindspice.lyra.repl;

import io.mindspice.lyra.repl.remote.RemoteCompletion;
import io.mindspice.lyra.repl.remote.RemoteFileCompletion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared mapping from the bounded execution-host completion lookups to the
 * transport-neutral {@link ConsoleSession} completion records. Completion is
 * metadata/filesystem only: it never compiles, pins, initializes or executes
 * source.
 */
final class ConsoleCompletion {
    private ConsoleCompletion() {
    }

    static ConsoleSession.Completion moduleFiles(LyraSession session,
                                                 ConsoleSession.CompletionRequest request) {
        RemoteCompletion.Result result = RemoteFileCompletion.moduleFiles(
                session.sourceRoots(), request.prefix());
        return map(result);
    }

    static ConsoleSession.Completion moduleFiles(List<java.nio.file.Path> sourceRoots,
                                                 ConsoleSession.CompletionRequest request) {
        RemoteCompletion.Result result = RemoteFileCompletion.moduleFiles(
                sourceRoots, request.prefix());
        return map(result);
    }

    static ConsoleSession.Completion bindingMembers(LyraSession session,
                                                    ConsoleSession.CompletionRequest request) {
        return bindingMembers(
                ConsolePresentation.bindings(session.workspaceState()),
                request.binding().orElseThrow());
    }

    static ConsoleSession.Completion bindingMembers(List<ConsoleSession.Binding> bindings,
                                                    String bindingName) {
        Optional<ConsoleSession.Binding> target = bindings.stream()
                .filter(binding -> binding.name().equals(bindingName))
                .findFirst();
        if (target.isEmpty()) {
            return new ConsoleSession.Completion(ConsoleSession.QueryStatus.NOT_FOUND,
                    List.of(), Optional.of("unknown committed binding: " + bindingName));
        }
        List<RemoteCompletion.Item> members = RemoteCompletion.members(
                target.orElseThrow().canonicalType());
        if (members.isEmpty()) {
            return new ConsoleSession.Completion(ConsoleSession.QueryStatus.OK, List.of(),
                    Optional.of("binding has no metadata-completable members"));
        }
        return new ConsoleSession.Completion(ConsoleSession.QueryStatus.OK,
                members.stream()
                        .map(item -> new ConsoleSession.CompletionItem(
                                item.name(), map(item.kind()), item.typeSpelling()))
                        .toList(),
                Optional.empty());
    }

    private static ConsoleSession.Completion map(RemoteCompletion.Result result) {
        Objects.requireNonNull(result, "result");
        ConsoleSession.QueryStatus status = switch (result.status()) {
            case OK -> ConsoleSession.QueryStatus.OK;
            case NOT_FOUND -> ConsoleSession.QueryStatus.NOT_FOUND;
            case BUSY, STALE -> ConsoleSession.QueryStatus.BUSY;
            case UNAVAILABLE -> ConsoleSession.QueryStatus.UNAVAILABLE;
            case CLOSED -> ConsoleSession.QueryStatus.CLOSED;
        };
        return new ConsoleSession.Completion(status,
                result.items().stream()
                        .map(item -> new ConsoleSession.CompletionItem(
                                item.name(), map(item.kind()), item.typeSpelling()))
                        .toList(),
                result.detail());
    }

    private static ConsoleSession.ItemKind map(RemoteCompletion.ItemKind kind) {
        return switch (kind) {
            case FILE -> ConsoleSession.ItemKind.FILE;
            case DIRECTORY -> ConsoleSession.ItemKind.DIRECTORY;
            case MODULE -> ConsoleSession.ItemKind.MODULE;
            case MEMBER -> ConsoleSession.ItemKind.MEMBER;
        };
    }
}
