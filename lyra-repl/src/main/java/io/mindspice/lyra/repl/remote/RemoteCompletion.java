package io.mindspice.lyra.repl.remote;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-independent bounded metadata completion request/result.
 *
 * <p>Completion is deliberately metadata-only and read-only: it never
 * compiles source, pins modules, executes user expressions or performs
 * generic reflection. Binding member completion reads committed
 * name/type metadata; module file completion performs a bounded
 * execution-host filesystem lookup beneath the configured source roots.</p>
 */
public record RemoteCompletion(Kind kind, Optional<String> prefix, Optional<String> binding) {
    public RemoteCompletion {
        kind = Objects.requireNonNull(kind, "kind");
        prefix = Objects.requireNonNull(prefix, "prefix").map(value ->
                ProtocolValues.text(value, "prefix", RemoteProtocol.MAX_COMPLETION_PREFIX_CHARACTERS));
        binding = Objects.requireNonNull(binding, "binding").map(value ->
                ProtocolValues.token(value, "binding", RemoteProtocol.MAX_COMPLETION_ITEM_CHARACTERS));
        if (kind == Kind.BINDING_MEMBERS && binding.isEmpty()) {
            throw new IllegalArgumentException("member completion needs a binding name");
        }
        if (kind != Kind.BINDING_MEMBERS && binding.isPresent()) {
            throw new IllegalArgumentException("binding name is only valid for member completion");
        }
    }

    public static RemoteCompletion moduleFiles(Optional<String> prefix) {
        return new RemoteCompletion(Kind.MODULE_FILES, prefix, Optional.empty());
    }

    public static RemoteCompletion bindingMembers(String binding) {
        return new RemoteCompletion(Kind.BINDING_MEMBERS, Optional.empty(), Optional.of(binding));
    }

    public enum Kind {
        MODULE_FILES,
        BINDING_MEMBERS
    }

    public enum Status {
        OK,
        NOT_FOUND,
        BUSY,
        STALE,
        UNAVAILABLE,
        CLOSED
    }

    public record Item(String name, ItemKind kind, Optional<String> typeSpelling) {
        public Item {
            name = ProtocolValues.token(name, "completion item name",
                    RemoteProtocol.MAX_COMPLETION_ITEM_CHARACTERS);
            kind = Objects.requireNonNull(kind, "kind");
            typeSpelling = Objects.requireNonNull(typeSpelling, "typeSpelling").map(value ->
                    ProtocolValues.token(value, "completion item type", 4096));
        }

        public Item(String name, ItemKind kind) {
            this(name, kind, Optional.empty());
        }
    }

    public enum ItemKind {
        FILE,
        DIRECTORY,
        MODULE,
        MEMBER
    }

    public record Result(
            Status status,
            List<Item> items,
            Optional<String> detail) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.size() > RemoteProtocol.MAX_COMPLETION_ITEMS) {
                throw new IllegalArgumentException("too many completion items");
            }
            for (Item item : items) {
                Objects.requireNonNull(item, "items must not contain null");
            }
            detail = Objects.requireNonNull(detail, "detail")
                    .map(value -> ProtocolValues.text(value, "detail", 4096));
        }

        public static Result unavailable(String detail) {
            return new Result(Status.UNAVAILABLE, List.of(), Optional.of(detail));
        }

        public static Result notFound(String detail) {
            return new Result(Status.NOT_FOUND, List.of(), Optional.of(detail));
        }
    }

    /**
     * Fixed member metadata for a committed binding's canonical type. This
     * is a bounded static table over normative core-type members; it never
     * reflects over live values or executes user code.
     */
    public static List<Item> members(String canonicalType) {
        Objects.requireNonNull(canonicalType, "canonicalType");
        if (canonicalType.equals("String") || canonicalType.startsWith("Array<")) {
            return List.of(new Item("length", ItemKind.MEMBER, Optional.of("I32")));
        }
        return List.of();
    }
}
