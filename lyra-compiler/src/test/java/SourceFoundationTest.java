import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.DiagnosticCode;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.diagnostic.RelatedSpan;
import io.mindspice.lyra.compiler.diagnostic.Severity;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourcePosition;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Assertion-grade, dependency-free tests for the domain-2 foundation. */
public final class SourceFoundationTest {
    public SourceFoundationTest() {
    }

    @Test
    public void testMalformedUtf8IsStructured() {
        malformedUtf8IsStructured();
    }

    @Test
    public void testBomIsIgnoredAndBytesAreCanonical() {
        bomIsIgnoredAndBytesAreCanonical();
    }

    @Test
    public void testUtf16OffsetsAndLineColumnsAreExact() {
        utf16OffsetsAndLineColumnsAreExact();
    }

    @Test
    public void testSnapshotsAndResultsAreDefensive() {
        snapshotsAndResultsAreDefensive();
    }

    @Test
    public void testIdentitiesAreStableAndPhysicalKeysDeduplicateSymlinks() throws Exception {
        identitiesAreStableAndPhysicalKeysDeduplicateSymlinks();
    }

    @Test
    public void testSpansAndCodesRejectInvalidData() {
        spansAndCodesRejectInvalidData();
    }

    @Test
    public void testDiagnosticsCarryDataAndRenderLocations() {
        diagnosticsCarryDataAndRenderLocations();
    }

    private static void malformedUtf8IsStructured() {
        byte[] bytes = {'o', 'k', (byte) 0xC3, '('};
        SourceId sourceId = SourceId.path("src/main.lyra");
        PhaseResult<SourceSnapshot> result = SourceSnapshot.capture(
                sourceId,
                PhysicalSourceKey.uri(URI.create("memory:src/main.lyra")),
                bytes);

        check(result instanceof PhaseResult.Failure<?>, "malformed UTF-8 must fail the source phase");
        check(result.optionalValue().isEmpty(), "a failed phase must not expose a snapshot");
        Diagnostic diagnostic = result.diagnostics().getFirst();
        check(diagnostic.code().equals(CompilerDiagnosticCodes.SOURCE_MALFORMED_UTF8),
                "malformed UTF-8 code");
        check(diagnostic.phase() == Phase.SOURCE, "malformed UTF-8 phase");
        check(diagnostic.severity() == Severity.ERROR, "malformed UTF-8 severity");
        check(diagnostic.primarySpan().equals(SourceSpan.at(sourceId, 2)),
                "malformed UTF-8 is anchored at the decoded prefix");
        check(diagnostic.summary().contains("byte offset 2"), "raw byte offset is retained in the summary");
    }

    private static void bomIsIgnoredAndBytesAreCanonical() {
        byte[] content = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[content.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(content, 0, withBom, 3, content.length);

        SourceSnapshot snapshot = success(SourceSnapshot.capture(
                SourceId.path("main.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:main")),
                withBom));
        SourceSnapshot withoutBom = success(SourceSnapshot.capture(
                SourceId.path("other.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:other")),
                content));

        check(snapshot.hadUtf8Bom(), "initial UTF-8 BOM is detected");
        check(snapshot.text().equals("alpha"), "initial UTF-8 BOM is not source text");
        check(Arrays.equals(snapshot.utf8Bytes(), content), "canonical bytes omit the BOM");
        check(Arrays.equals(snapshot.capturedUtf8Bytes(), withBom), "captured bytes retain the original capture");
        check(snapshot.sha256().equals(withoutBom.sha256()), "BOM does not affect the source revision digest");
    }

    private static void utf16OffsetsAndLineColumnsAreExact() {
        String text = "A😀B\né\r\nZ";
        SourceSnapshot snapshot = success(SourceSnapshot.capture(
                SourceId.path("unicode.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:unicode")),
                text.getBytes(StandardCharsets.UTF_8)));

        check(snapshot.utf16Length() == 9, "surrogate pairs count as two UTF-16 code units");
        expectPosition(snapshot.positionAt(0), 0, 1, 1);
        expectPosition(snapshot.positionAt(1), 1, 1, 2);
        expectPosition(snapshot.positionAt(2), 2, 1, 3);
        expectPosition(snapshot.positionAt(3), 3, 1, 4);
        expectPosition(snapshot.positionAt(4), 4, 1, 5);
        expectPosition(snapshot.positionAt(5), 5, 2, 1);
        expectPosition(snapshot.positionAt(6), 6, 2, 2);
        expectPosition(snapshot.positionAt(7), 7, 2, 3);
        expectPosition(snapshot.positionAt(8), 8, 3, 1);
        expectPosition(snapshot.positionAt(9), 9, 3, 2);

        check(snapshot.lineIndex().lineCount() == 3, "CRLF is one line break");
        check(snapshot.lineIndex().lineText(1).equals("A😀B"), "first line text");
        check(snapshot.lineIndex().lineText(2).equals("é"), "second line text");
        check(snapshot.lineIndex().lineText(3).equals("Z"), "third line text");

        SourceSpan emoji = new SourceSpan(snapshot.sourceId(), 1, 3);
        expectPosition(emoji.startPosition(snapshot), 1, 1, 2);
        expectPosition(emoji.endPosition(snapshot), 3, 1, 4);
        check(emoji.location(snapshot).equals("unicode.lyra:1:2"), "span location uses one-based columns");
    }

    private static void snapshotsAndResultsAreDefensive() {
        byte[] input = "immutable".getBytes(StandardCharsets.UTF_8);
        SourceSnapshot snapshot = success(SourceSnapshot.capture(
                SourceId.path("immutable.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:immutable")),
                input));
        input[0] = 'X';
        byte[] returned = snapshot.utf8Bytes();
        returned[0] = 'X';
        byte[] captured = snapshot.capturedUtf8Bytes();
        captured[0] = 'X';
        check(snapshot.text().equals("immutable"), "snapshot text cannot be changed through input or accessors");
        check(snapshot.utf8Bytes()[0] == 'i', "snapshot bytes are defensive copies");

        Diagnostic diagnostic = Diagnostic.error(
                CompilerDiagnosticCodes.SOURCE_INVALID_SPAN,
                SourceSpan.at(snapshot.sourceId(), 0),
                "bad span");
        List<Diagnostic> diagnostics = new ArrayList<>(List.of(diagnostic));
        PhaseResult.Failure<SourceSnapshot> failure = new PhaseResult.Failure<>(diagnostics);
        diagnostics.clear();
        check(failure.diagnostics().size() == 1, "phase result copies diagnostic lists");
        expectThrows(UnsupportedOperationException.class,
                () -> failure.diagnostics().clear());
        check(failure.optionalValue().isEmpty(), "failure has no partial value");
    }

    private static void identitiesAreStableAndPhysicalKeysDeduplicateSymlinks() throws Exception {
        SourceId normalized = SourceId.path("./game\\math/../math/vector.lyra");
        check(normalized.value().equals("game/math/vector.lyra"), "source paths normalize to POSIX relative form");
        check(SourceId.path("dir/a:b.lyra").isPath(), "explicit path identities keep POSIX colons as path characters");
        check(SourceId.fromPath(Path.of("/tmp/project"), Path.of("game/./main.lyra"))
                        .equals(SourceId.path("game/main.lyra")), "root-relative path identity");
        check(SourceId.uri(URI.create("MEMORY://resolver/./source"))
                        .value().equals("memory://resolver/source"), "URI identity normalization");
        check(ModuleId.fromSourceId(normalized).value().equals(normalized.value()),
                "module identity preserves stable source identity");
        check(ModuleId.path("dir/a:b.lyra").isPath(), "module path identity preserves explicit path form");
        PhysicalSourceKey windowsPath = new PhysicalSourceKey("C:\\workspace\\main.lyra");
        check(windowsPath.value().equals("C:/workspace/main.lyra"),
                "Windows physical paths normalize separators");
        check(windowsPath.isPath(), "Windows drive paths are not URI physical keys");
        check(new PhysicalSourceKey("C:\\workspace\\dir\\..\\main.lyra").equals(windowsPath),
                "Windows physical paths normalize dot segments");
        PhysicalSourceKey driveLikeUri = PhysicalSourceKey.uri(URI.create("c:/resolver/../source"));
        check(driveLikeUri.isUri(), "URI factory preserves URI kind for drive-like schemes");
        check(driveLikeUri.asUri().toString().equals("c:/source"),
                "drive-like URI normalization is retained");
        expectThrows(IllegalStateException.class, windowsPath::asUri);
        expectThrows(IllegalArgumentException.class, () -> SourceId.path("/absolute/checkout.lyra"));
        expectThrows(IllegalArgumentException.class, () -> SourceId.path("../outside.lyra"));
        expectThrows(IllegalArgumentException.class, () -> SourceId.path("./C:/absolute.lyra"));
        expectThrows(IllegalArgumentException.class, () -> SourceId.path("dir/../C:/absolute.lyra"));

        Path directory = Files.createTempDirectory("lyra-source-foundation");
        try {
            Path real = directory.resolve("real.lyra");
            Path link = directory.resolve("alias.lyra");
            Files.writeString(real, "source", StandardCharsets.UTF_8);
            try {
                Files.createSymbolicLink(link, real.getFileName());
                check(PhysicalSourceKey.from(real).equals(PhysicalSourceKey.from(link)),
                        "real physical keys deduplicate symlink aliases");
            } catch (UnsupportedOperationException exception) {
                // The source identity contract remains covered on filesystems without symlink support.
            }
            check(!PhysicalSourceKey.of(real).equals(SourceId.path("real.lyra")),
                    "physical and stable identities are distinct value types");
        } finally {
            Files.deleteIfExists(directory.resolve("alias.lyra"));
            Files.deleteIfExists(directory.resolve("real.lyra"));
            Files.deleteIfExists(directory);
        }
    }

    private static void spansAndCodesRejectInvalidData() {
        SourceId sourceId = SourceId.path("invalid.lyra");
        expectThrows(IllegalArgumentException.class, () -> new SourceSpan(sourceId, -1, 0));
        expectThrows(IllegalArgumentException.class, () -> new SourceSpan(sourceId, 2, 1));
        expectThrows(IllegalArgumentException.class,
                () -> new SourceSpan(sourceId, 0, 2).validateAgainst(
                        success(SourceSnapshot.capture(
                                sourceId,
                                PhysicalSourceKey.uri(URI.create("memory:invalid")),
                                "x".getBytes(StandardCharsets.UTF_8)))));
        expectThrows(IllegalArgumentException.class,
                () -> new DiagnosticCode("LYC-LEX-not-a-number", Phase.LEX));
        expectThrows(IllegalArgumentException.class,
                () -> new DiagnosticCode("LYC-PARSE-001", Phase.LEX));
        expectThrows(IllegalArgumentException.class, () -> DiagnosticCode.of("LYC-UNKNOWN-001"));
        check(DiagnosticCode.of(Phase.LEX, 7).value().equals("LYC-LEX-007"),
                "registry code formatting is stable");
    }

    private static void diagnosticsCarryDataAndRenderLocations() {
        SourceSnapshot snapshot = success(SourceSnapshot.capture(
                SourceId.path("diagnostic.lyra"),
                PhysicalSourceKey.uri(URI.create("memory:diagnostic")),
                "a😀b\n".getBytes(StandardCharsets.UTF_8)));
        SourceSpan primary = new SourceSpan(snapshot.sourceId(), 1, 3);
        RelatedSpan related = new RelatedSpan(
                new SourceSpan(snapshot.sourceId(), 0, 1), "declaration");
        List<RelatedSpan> relatedSpans = new ArrayList<>(List.of(related));
        Diagnostic diagnostic = Diagnostic.error(
                CompilerDiagnosticCodes.SOURCE_INVALID_SPAN,
                primary,
                "invalid source span",
                relatedSpans);
        relatedSpans.clear();

        check(diagnostic.phase() == Phase.SOURCE, "diagnostic phase comes from its stable code");
        check(diagnostic.relatedSpans().size() == 1, "related spans are copied");
        expectThrows(UnsupportedOperationException.class,
                () -> diagnostic.relatedSpans().clear());
        String rendered = diagnostic.render(snapshot);
        check(rendered.contains("ERROR LYC-SOURCE-004 diagnostic.lyra:1:2: invalid source span"),
                "rendered diagnostic has stable code and UTF-16 location");
        check(rendered.contains("a😀b"), "rendered diagnostic includes source excerpt");
        check(rendered.contains("^"), "rendered diagnostic includes a primary marker");
        check(rendered.contains("declaration: diagnostic.lyra:1:1"),
                "rendered diagnostic includes related source location");
        check(diagnostic.render().contains("diagnostic.lyra:1..3"),
                "location-only rendering remains available without source text");
    }

    @SuppressWarnings("unchecked")
    private static SourceSnapshot success(PhaseResult<SourceSnapshot> result) {
        check(result instanceof PhaseResult.Success<?>, "expected a successful source capture");
        return ((PhaseResult.Success<SourceSnapshot>) result).value();
    }

    private static void expectPosition(
            SourcePosition position, int offset, int line, int column) {
        check(position.offset() == offset
                        && position.line() == line
                        && position.column() == column,
                "unexpected position: " + position);
    }

    private static <T extends Throwable> void expectThrows(
            Class<T> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError("expected " + expected.getName() + " but got "
                    + thrown.getClass().getName(), thrown);
        }
        throw new AssertionError("expected " + expected.getName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
