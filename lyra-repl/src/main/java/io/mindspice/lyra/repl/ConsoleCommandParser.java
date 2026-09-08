package io.mindspice.lyra.repl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Parses the small command language used by the dependency-free console. */
final class ConsoleCommandParser {
    private ConsoleCommandParser() {
    }

    static ConsoleCommand parse(String line) {
        Objects.requireNonNull(line, "line");
        String input = line.stripLeading();
        int end = 0;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) end++;
        String name = input.substring(0, end);
        if (!name.startsWith(":")) {
            throw new ParseFailure("a console command must start with ':'");
        }
        ConsoleCommand.Kind kind = switch (name) {
            case ":help" -> ConsoleCommand.Kind.HELP;
            case ":bindings" -> ConsoleCommand.Kind.BINDINGS;
            case ":type" -> ConsoleCommand.Kind.TYPE;
            case ":load" -> ConsoleCommand.Kind.LOAD;
            case ":reload" -> ConsoleCommand.Kind.RELOAD;
            case ":reset" -> ConsoleCommand.Kind.RESET;
            case ":history" -> ConsoleCommand.Kind.HISTORY;
            case ":quit" -> ConsoleCommand.Kind.QUIT;
            default -> throw new ParseFailure("unknown command: " + name);
        };
        String remainder = input.substring(end).stripLeading();
        if (kind == ConsoleCommand.Kind.TYPE) {
            if (remainder.isEmpty()) {
                throw new ParseFailure(name + " expects a source argument");
            }
            // This is Lyra source, not shell-like path arguments. Preserve
            // literals, escapes, comments and internal whitespace verbatim.
            return new ConsoleCommand(kind, List.of(remainder));
        }
        List<String> arguments = tokenize(remainder);
        int expected = switch (kind) {
            case LOAD, RELOAD -> 1;
            default -> 0;
        };
        if (arguments.size() != expected) {
            throw new ParseFailure(name + " expects " + expected + " argument"
                    + (expected == 1 ? "" : "s"));
        }
        return new ConsoleCommand(kind, arguments);
    }

    private static List<String> tokenize(String line) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                token.append(character);
                inToken = true;
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                inToken = true;
                continue;
            }
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    token.append(character);
                }
                inToken = true;
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                inToken = true;
            } else if (Character.isWhitespace(character)) {
                if (inToken) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    inToken = false;
                }
            } else {
                token.append(character);
                inToken = true;
            }
        }
        if (escaped) {
            throw new ParseFailure("trailing escape in command arguments");
        }
        if (quote != 0) {
            throw new ParseFailure("unterminated quoted command argument");
        }
        if (inToken) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    static final class ParseFailure extends IllegalArgumentException {
        ParseFailure(String message) {
            super(message);
        }
    }
}

record ConsoleCommand(ConsoleCommand.Kind kind, List<String> arguments) {
    ConsoleCommand {
        kind = Objects.requireNonNull(kind, "kind");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    enum Kind {
        HELP, BINDINGS, TYPE, LOAD, RELOAD, RESET, HISTORY, QUIT
    }
}
