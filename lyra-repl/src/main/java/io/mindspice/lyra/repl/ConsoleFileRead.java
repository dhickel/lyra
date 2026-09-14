package io.mindspice.lyra.repl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Single-shot execution-host file capture for the console {@code \\load}
 * command. Optional-console paths follow normal filesystem semantics: the
 * file must exist and be a regular file decoded as strict UTF-8, but no
 * permission or symbolic-link security policy is applied. The file is read
 * exactly once into a captured file-URI source and never re-read.
 */
final class ConsoleFileRead {
    private ConsoleFileRead() {
    }

    /** Reads one UTF-8 regular file into a captured file-URI source. */
    static EvaluationSource readFile(String spelling) {
        final Path path;
        try {
            path = Path.of(spelling);
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("invalid load path: " + spelling, failure);
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IllegalArgumentException("cannot read load file: " + normalized);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("load path is not a regular file: " + spelling);
        }
        String label = normalized.toString();
        String text = readUtf8Once(normalized);
        return new EvaluationSource(new SourceOrigin(
                label, Optional.of(normalized.toUri()), Optional.empty(), 0, text.length()),
                text);
    }

    private static String readUtf8Once(Path path) {
        final byte[] bytes;
        try (InputStream source = Files.newInputStream(path, StandardOpenOption.READ)) {
            bytes = source.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read load file: " + path, failure);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("load path is not valid UTF-8: " + path, failure);
        }
    }
}
