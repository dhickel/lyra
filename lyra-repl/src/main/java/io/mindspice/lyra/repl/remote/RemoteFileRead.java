package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;

import java.io.ByteArrayOutputStream;
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
 * Single-shot execution-host file capture for the protocol LOAD operation.
 * The file is opened, read and decoded exactly once; the resulting text is
 * captured with an explicit file URI and never re-read or resubmitted.
 */
final class RemoteFileRead {
    private RemoteFileRead() {
    }

    /** Reads one UTF-8 regular file into a captured file-URI source. */
    static EvaluationSource readFile(String spelling) {
        final Path path;
        try {
            path = Path.of(spelling);
        } catch (InvalidPathException failure) {
            throw new RemoteLoadException("invalid load path: " + spelling);
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new RemoteLoadException("cannot read load file: " + normalized);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new RemoteLoadException(
                    "load path is not a regular file: " + spelling);
        }
        String label = normalized.toString();
        validateLabel(label);
        String text = readUtf8Once(normalized);
        // Capture the text with an explicit file URI; the path is never
        // consulted again and the source is never re-read.
        return new EvaluationSource(new io.mindspice.lyra.repl.SourceOrigin(
                label, Optional.of(normalized.toUri()), Optional.empty(), 0, text.length()),
                text);
    }

    private static String readUtf8Once(Path path) {
        long maximumBytes = 3L * RemoteProtocol.MAX_SOURCE_CHARACTERS;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        // Open normally: optional-console paths follow ordinary filesystem
        // semantics, so a symlink to a regular file is a valid load target.
        try (InputStream source = Files.newInputStream(path, StandardOpenOption.READ)) {
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > maximumBytes) {
                    throw new RemoteLoadException("load source exceeds the protocol source bound");
                }
                bytes.write(buffer, 0, count);
            }
        } catch (RemoteLoadException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RemoteLoadException("cannot read load file: " + path);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
            if (text.length() > RemoteProtocol.MAX_SOURCE_CHARACTERS) {
                throw new RemoteLoadException("load source exceeds the protocol source bound");
            }
            return text;
        } catch (CharacterCodingException failure) {
            throw new RemoteLoadException("load path is not valid UTF-8: " + path);
        }
    }

    private static void validateLabel(String label) {
        if (label.length() > RemoteProtocol.MAX_LABEL_CHARACTERS) {
            throw new RemoteLoadException("load path label exceeds the protocol bound");
        }
        for (int index = 0; index < label.length(); index++) {
            char character = label.charAt(index);
            if (Character.isISOControl(character)) {
                throw new RemoteLoadException("load path label contains a control character");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= label.length()
                        || !Character.isLowSurrogate(label.charAt(index + 1))) {
                    throw new RemoteLoadException("load path label contains an invalid surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new RemoteLoadException("load path label contains an invalid surrogate");
            }
        }
    }

}
