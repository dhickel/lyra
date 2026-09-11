package io.mindspice.lyra.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageServiceTest {
    @TempDir Path root;
    private final LanguageService service = new LanguageService();
    @Test void syntaxHighlightingAndOutlineComeFromCompilerAndRetainUnicodeOffsets() {
        String source = "// let fake = (=> || 0) 😀\nlet greet :Fn<String;String> = (=> |name| {\n"
                + "  let local :Fn<;String> = (=> :String || name)\n  name\n})\nlet message = \"let fake = (=> || 1)\"";
        var result = analyze(source, Map.of());
        assertTrue(result.valid(), result.diagnostics().toString());
        assertEquals(List.of("greet", "local", "message"), result.definitions().stream().map(LanguageService.Definition::name).toList());
        assertTrue(result.definitions().getFirst().topLevel());
        assertFalse(result.definitions().get(1).topLevel());
        assertEquals(source.indexOf("greet"), result.definitions().getFirst().nameOffset());
        assertEquals(List.of("name"), result.definitions().getFirst().parameters());
        assertEquals("syntax-comment", result.styles().getFirst().css());
        assertEquals("// let fake = (=> || 0) 😀", source.substring(result.styles().getFirst().start(), result.styles().getFirst().end()));
    }
    @Test void unsavedImportsAreCheckedWithoutExecutingInitializersOrReadingOldDiskContents() throws Exception {
        Path dependency = root.resolve("dep.lyra");
        Files.writeString(dependency, "let @pub value :String = \"old\"");
        String source = "import dep\nlet result :I32 = dep->:.value";
        assertFalse(analyze(source, Map.of()).valid());
        var result = analyze(source, Map.of(dependency, "let zero :I32 = 0 let @pub value :I32 = (% 1 zero)"));
        assertTrue(result.valid(), result.diagnostics().toString());
        assertEquals("let @pub value :String = \"old\"", Files.readString(dependency));
    }
    @Test void invalidImportsSyntaxAndTypesHaveStructuredCompilerDiagnostics() {
        for (String text : List.of("import missing\n1", "let broken = (=> ||", "let value :I32 = \"wrong\"")) {
            var result = analyze(text, Map.of());
            assertFalse(result.valid(), text); assertFalse(result.diagnostics().isEmpty());
            assertFalse(result.diagnostics().getFirst().code().value().isBlank());
        }
    }
    @Test void changingImportedUnsavedSourceChangesTheDiagnosticSourceIdentity() throws Exception {
        Path dependency = root.resolve("dep.lyra"); Files.writeString(dependency, "let @pub value = 1");
        var result = analyze("import dep\ndep->:.value", Map.of(dependency, "let @pub value :I32 = \"bad\""));
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.primarySpan().sourceId().value().equals(dependency.toUri().toString())));
    }
    @Test void inferredFunctionAliasesAndReferenceNavigationUseCheckedDeclarations() {
        String source = "let original :Fn<I32;I32> = (=> |number| (+ number 1))\nlet alias = original\nlet result :I32 = { ::alias[4] }";
        var result = analyze(source, Map.of());
        assertTrue(result.valid(), result.diagnostics().toString());
        assertTrue(result.definitions().get(1).function());
        int reference = source.lastIndexOf("number");
        var link = result.navigation().stream().filter(n -> n.start() <= reference && reference < n.end()).findFirst().orElseThrow();
        assertEquals(source.indexOf("number"), link.targetStart());
    }
    @Test void unclosedStringKeepsEarlierCompilerHighlightingAndReportsTheError() {
        var result = analyze("let count = 1\nlet text = \"unfinished", Map.of());
        assertFalse(result.valid());
        assertTrue(result.styles().stream().anyMatch(style -> style.css().equals("syntax-keyword") && style.start() == 0));
    }
    @Test void matchAndWhenUseKeywordHighlighting() {
        String source = "let value = (match 1 ?? 1 when #T -> 2 ?? _ -> 3)";
        var result = analyze(source, Map.of());
        assertTrue(result.valid(), result.diagnostics().toString());
        for (String keyword : List.of("match", "when")) {
            int offset = source.indexOf(keyword);
            assertTrue(result.styles().stream().anyMatch(style ->
                    style.css().equals("syntax-keyword") && style.start() == offset));
        }
    }
    @Test void nominalKeywordsAreHighlightedEvenBeforeSemanticSupportIsComplete() {
        String source = "struct Data { let value :I32 } class Counter {}";
        var result = analyze(source, Map.of());
        for (String keyword : List.of("struct", "class")) {
            assertTrue(result.styles().stream().anyMatch(style -> style.css().equals("syntax-keyword")
                    && style.start() == source.indexOf(keyword)));
        }
        // Highlighting is syntax evidence, not a claim that these declarations execute.
    }
    @Test void aggregatesContainingFunctionsAreListedAsDataBindings() {
        var result = analyze("let answer :Fn<;I32> = (=> || 42)\nlet functions :Array<Fn<;I32>> = Array<Fn<;I32>>[answer]", Map.of());
        assertTrue(result.valid(), result.diagnostics().toString());
        assertTrue(result.definitions().getFirst().function());
        assertFalse(result.definitions().get(1).function());
    }
    private LanguageService.Analysis analyze(String text, Map<Path, String> buffers) {
        return service.analyze(root.resolve("main.lyra"), text, List.of(root), buffers, true);
    }
}
