package io.mindspice.lyra.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFilesTest {
    @TempDir Path root;
    @Test void atomicSaveRetainsBomAndCrLfAndRejectsExternalChanges() throws Exception {
        Path file = root.resolve("main.lyra"); Files.writeString(file, "\uFEFFlet a = 1\r\n");
        var document = new WorkspaceFiles.Document(file);
        assertEquals("let a = 1\n", document.text());
        document.save("let a = 2\n", false);
        assertEquals("\uFEFFlet a = 2\r\n", Files.readString(file));
        Files.writeString(file, "external change");
        assertThrows(FileSystemException.class, () -> document.save("unsaved edits", false));
        assertEquals("external change", Files.readString(file));
        assertEquals("let a = 2\n", document.text());
        document.save("unsaved edits", true);
        assertEquals("\uFEFFunsaved edits", Files.readString(file));
    }
    @Test void rejectsBinaryMalformedUtf8AndOversizedDocuments() throws Exception {
        Path file = root.resolve("input");
        Files.write(file, new byte[]{(byte) 0xC3, 0x28}); assertThrows(java.io.IOException.class, () -> new WorkspaceFiles.Document(file));
        Files.write(file, new byte[]{0}); assertThrows(java.io.IOException.class, () -> new WorkspaceFiles.Document(file));
        Files.write(file, new byte[WorkspaceFiles.MAX_FILE_BYTES + 1]); assertThrows(java.io.IOException.class, () -> new WorkspaceFiles.Document(file));
    }
    @Test void renameNeverReplacesAnotherSourceFile() throws Exception {
        Path first = root.resolve("first.lyra"), second = root.resolve("second.lyra");
        Files.writeString(first, "first source"); Files.writeString(second, "second source");
        assertThrows(FileAlreadyExistsException.class, () -> WorkspaceFiles.rename(first, second));
        assertEquals("first source", Files.readString(first)); assertEquals("second source", Files.readString(second));
        Path moved = root.resolve("renamed.lyra");
        assertEquals(moved, WorkspaceFiles.rename(first, moved));
        assertFalse(Files.exists(first)); assertEquals("first source", Files.readString(moved));
    }
    @Test void settingsAndRecoveryRoundTripSpacesUnicodeAndArgumentsWithoutExecutingSource() throws Exception {
        Path source = root.resolve("hello world.lyra"); Files.writeString(source, "let f = 1");
        var settings = WorkspaceSettings.open(root).withEntry(new WorkspaceSettings.RunTarget(source, "greet", "\"hello 😀\""));
        settings.save(); assertEquals(settings, WorkspaceSettings.open(root));
        var recovery = new RecoveryStore(root);
        recovery.write(source, "let f = 2\n");
        assertEquals("let f = 2\n", recovery.read(source).orElseThrow());
        assertEquals("let f = 1", Files.readString(source));
        recovery.remove(source); assertTrue(recovery.read(source).isEmpty());
    }
    @Test void commentAndSearchOperationsHandleMultilineUnicodeAndZeroWidthPatterns() {
        String source = "  let a = 1\n    // note\n";
        assertEquals(source, TextEdits.toggleComment(TextEdits.toggleComment(source)));
        var matches = TextEdits.matches("😀 one ONE", "one", false, false);
        assertEquals(List.of(new TextEdits.Match(3, 6), new TextEdits.Match(7, 10)), matches);
        assertEquals("😀 $ $", TextEdits.replaceAll("😀 one ONE", matches, "$"));
        assertEquals(3, TextEdits.matches("ab", "(?=)", true, true).size());
        assertEquals(2, TextEdits.lineAt("a\n😀b", 4));
    }
    @Test void breakpointsFollowTheirOriginalLineThroughInsertionsAndDeletions() {
        assertEquals(3, TextEdits.rebaseLine("one\ntwo\nthree", "new\none\ntwo\nthree", 2));
        assertEquals(2, TextEdits.rebaseLine("one\ntwo\nthree", "one\nthree", 3));
        assertEquals(2, TextEdits.rebaseLine("one\ntwo\nthree", "one\nchanged\nthree", 2));
    }
}
