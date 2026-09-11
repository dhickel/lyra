package io.mindspice.lyra.editor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Disk operations are independent of JavaFX and never overwrite a newer disk revision silently. */
public final class WorkspaceFiles {
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private WorkspaceFiles() { }

    public static byte[] readBytes(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("File exceeds the editor's 2 MiB limit: " + file);
            return bytes;
        }
    }

    public static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static Path rename(Path source, Path destination) throws IOException {
        // ATOMIC_MOVE permits replacing an existing destination on some providers.
        // A rename must preserve both files when the requested name is already taken.
        return Files.move(source, destination);
    }

    public static void atomicWrite(Path file, byte[] bytes) throws IOException {
        Path target = Files.exists(file) ? file.toRealPath() : file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(target.getParent(), ".lyra-save-", ".tmp");
        try {
            if (Files.exists(target) && Files.getFileStore(target).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(target));
            }
            try (var out = FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Refuse unsafe replacement; the user still has the original and the editor buffer.
                throw new IOException("This filesystem does not support atomic saves: " + target, unsupported);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static final class Document {
        private final Path path;
        private volatile byte[] diskRevision;
        private final boolean bom;
        private final String newline;
        private volatile String savedText;

        public Document(Path path) throws IOException {
            this.path = path.toRealPath();
            diskRevision = readBytes(this.path);
            String decoded = decode(diskRevision);
            if (decoded.indexOf('\0') >= 0) throw new IOException("Binary files cannot be edited: " + path);
            bom = decoded.startsWith("\uFEFF");
            newline = decoded.contains("\r\n") ? "\r\n" : "\n";
            savedText = (bom ? decoded.substring(1) : decoded).replace("\r\n", "\n").replace('\r', '\n');
        }

        public Path path() { return path; }
        public String text() { return savedText; }
        public boolean changedOnDisk() throws IOException {
            return !Files.exists(path) || !Arrays.equals(diskRevision, readBytes(path));
        }
        public void save(String text, boolean overwriteExternalChange) throws IOException {
            if (!overwriteExternalChange && changedOnDisk()) throw new FileSystemException(path.toString(), null,
                    "File changed outside Lyra Editor. Reload or explicitly overwrite it.");
            byte[] next = ((bom ? "\uFEFF" : "") + text.replace("\n", newline)).getBytes(StandardCharsets.UTF_8);
            if (next.length > MAX_FILE_BYTES) throw new IOException("File exceeds the editor's 2 MiB limit.");
            atomicWrite(path, next);
            diskRevision = next;
            savedText = text;
        }
    }
}
