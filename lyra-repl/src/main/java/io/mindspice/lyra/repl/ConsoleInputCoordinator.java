package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.LyraIoException;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Source-side view of the session's single configured input owner.
 *
 * <p>It asks the runtime environment for one line at a time and assembles
 * complete source units locally. The environment owns the decoder, pending
 * bytes, and input lock used by generated {@code std->io} reads, so source
 * acquisition cannot read ahead into program input.</p>
 */
final class ConsoleInputCoordinator implements PlainConsole.SourceReader {
    private final RuntimeIoEnvironment environment;
    private final StringBuilder buffer = new StringBuilder();
    private boolean eof;

    ConsoleInputCoordinator(RuntimeIoEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public String readSource(List<String> ignoredHistory) throws IOException {
        if (eof) {
            return null;
        }
        while (true) {
            final String line;
            try {
                line = environment.readLine();
            } catch (LyraIoException failure) {
                throw new IOException(failure.summary(), failure.javaCause().orElse(failure));
            }
            if (line == null) {
                eof = true;
                if (buffer.isEmpty()) {
                    return null;
                }
                String source = buffer.toString();
                buffer.setLength(0);
                return source;
            }
            if (buffer.toString().isBlank() && line.stripLeading().startsWith(":")) {
                return line.stripLeading();
            }
            if (buffer.isEmpty() && line.isBlank()) {
                continue;
            }
            buffer.append(line).append('\n');
            if (LexicalCompleteness.inspect(buffer).complete()) {
                String source = buffer.toString();
                buffer.setLength(0);
                return source;
            }
        }
    }
}
