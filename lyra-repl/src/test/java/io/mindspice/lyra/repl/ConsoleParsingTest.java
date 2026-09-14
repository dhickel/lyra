package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConsoleParsingTest {
    @Test
    void nominalBodiesRetainMultilineDelimiterCompleteness() {
        for (String prefix : List.of("struct Data { let value :I32\n",
                "class Counter { Counter = (=> || { () })\n")) {
            assertTrue(LexicalCompleteness.inspect(prefix).incomplete());
            assertFalse(LexicalCompleteness.inspect(prefix).invalid());
            assertTrue(LexicalCompleteness.inspect(prefix + "}").complete());
            assertFalse(LexicalCompleteness.inspect(prefix + "}").invalid());
        }
    }

    @Test
    void commandArgumentsSupportQuotesAndEscapes() {
        ConsoleCommand command = ConsoleCommandParser.parse(
                "\\load \"folder with spaces/a\\\"b.lyra\"");

        assertEquals(ConsoleCommand.Kind.LOAD, command.kind());
        assertEquals("folder with spaces/a\"b.lyra", command.arguments().getFirst());
        ConsoleCommand escaped = ConsoleCommandParser.parse(
                "\\load folder\\ with\\ spaces/file.lyra");
        assertEquals("folder with spaces/file.lyra", escaped.arguments().getFirst());
        ConsoleCommand type = ConsoleCommandParser.parse("\\type 1 + 2");
        assertEquals("1 + 2", type.arguments().getFirst());
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\load \"unfinished"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\bindings extra"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\load trailing\\"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\set answer 1"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\quit now"));
    }

    @Test
    void reloadRequiresExactlyOneTargetAndIsNotAnUnavailableNoOp() {
        // An absent target is an ordinary usage error, never UNAVAILABLE.
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\reload"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\reload  one  two"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\reload \"unfinished"));

        ConsoleCommand logical = ConsoleCommandParser.parse("\\reload game->math->vector");
        assertEquals(ConsoleCommand.Kind.RELOAD, logical.kind());
        assertEquals("game->math->vector", logical.arguments().getFirst());

        ConsoleCommand quoted = ConsoleCommandParser.parse(
                "\\reload \"module with spaces\"");
        assertEquals("module with spaces", quoted.arguments().getFirst());

        ConsoleCommand alias = ConsoleCommandParser.parse("\\reload io");
        assertEquals(List.of("io"), alias.arguments());
    }

    @Test
    void typeArgumentsAreVerbatimLyraSourceNotShellTokens() {
        for (String source : List.of("\"two words\"", "'\\uD800'", "\"a\\n\\\\b\"",
                "Tuple[1  \"text\"] /* trailing */", "\"unfinished", "\"trailing\\")) {
            assertEquals(source, ConsoleCommandParser.parse("  \\type\t" + source).arguments().getFirst());
        }
        assertThrows(ConsoleCommandParser.ParseFailure.class, () -> ConsoleCommandParser.parse("\\type   "));
    }

    @Test
    void commandsUseBackslashOnlyAtSourceUnitBoundaries() {
        assertTrue(PlainConsole.isCommandLine("\\help"));
        assertTrue(PlainConsole.isCommandLine("  \\help"));
        assertFalse(PlainConsole.isCommandLine("(:help)"));
        assertFalse(PlainConsole.isCommandLine("let text :String = \\\"\\\\help\\\""));
        assertFalse(PlainConsole.isCommandLine(":help"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse(":help"));
        assertThrows(ConsoleCommandParser.ParseFailure.class,
                () -> ConsoleCommandParser.parse("\\HELP"));
    }

    @Test
    void onlyTheEssentialCommandsHaveParseableKinds() {
        List<String> names = List.of(
                "\\help", "\\bindings", "\\type source", "\\load file", "\\reload module",
                "\\reset", "\\history", "\\quit");
        List<ConsoleCommand.Kind> kinds = List.of(
                ConsoleCommand.Kind.HELP, ConsoleCommand.Kind.BINDINGS,
                ConsoleCommand.Kind.TYPE, ConsoleCommand.Kind.LOAD,
                ConsoleCommand.Kind.RELOAD, ConsoleCommand.Kind.RESET,
                ConsoleCommand.Kind.HISTORY, ConsoleCommand.Kind.QUIT);
        for (int index = 0; index < names.size(); index++) {
            assertEquals(kinds.get(index), ConsoleCommandParser.parse(names.get(index)).kind(),
                    names.get(index));
        }
    }

    @Test
    void completenessReportsLiteralAndDelimiterFailuresWithoutContinuation() {
        LexicalCompleteness.State mismatched = LexicalCompleteness.inspect("{]");
        assertTrue(mismatched.invalid());
        assertTrue(mismatched.complete());
        assertEquals(java.util.List.of('{'), mismatched.openDelimiters());
        assertFalse(LexicalCompleteness.inspect("/* outer /* inner */").complete());
        assertFalse(LexicalCompleteness.inspect("\"unterminated").complete());
        assertFalse(LexicalCompleteness.inspect("'unterminated").complete());
    }

    @Test
    void completenessIgnoresDelimitersInsideNestedCommentsAndLiterals() {
        assertTrue(LexicalCompleteness.inspect("/* outer /* inner */ still */ 1").complete());
        assertFalse(LexicalCompleteness.inspect("/* outer /* inner */").complete());
        assertFalse(LexicalCompleteness.inspect("(\"[not a delimiter]\"").complete());
        assertTrue(LexicalCompleteness.inspect("(\"[not a delimiter]\")").complete());
        assertTrue(LexicalCompleteness.inspect("\"escaped \\\" quote\"").complete());
        assertFalse(LexicalCompleteness.inspect("\"trailing escape\\").complete());
        assertFalse(LexicalCompleteness.inspect("'\\\''").incomplete());
        assertTrue(LexicalCompleteness.inspect("\"bad\\x\"").invalid());
        assertTrue(LexicalCompleteness.inspect("1e3").invalid());
        assertTrue(LexicalCompleteness.inspect("[1 {2 (3)}]").complete());
        assertTrue(LexicalCompleteness.inspect(")").invalid());
    }
}
