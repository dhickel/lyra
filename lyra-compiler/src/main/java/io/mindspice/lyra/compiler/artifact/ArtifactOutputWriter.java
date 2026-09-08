package io.mindspice.lyra.compiler.artifact;

import io.mindspice.lyra.runtime.ArtifactMetadata;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32;

/** Deterministic writers with staging-before-publication semantics. */
final class ArtifactOutputWriter {
    private ArtifactOutputWriter() {
    }

    static void writeClasses(ArtifactAssembly assembly, Path output,
                             ArtifactWriteOptions options) throws IOException {
        Path target = prepareTarget(output, options);
        Path parent = parent(target);
        Files.createDirectories(parent);
        rejectSymbolicPath(target);
        Path staging = sibling(parent, target.getFileName() + ".lyra-staging-");
        Files.createDirectory(staging);
        boolean published = false;
        try {
            for (Map.Entry<String, byte[]> entry : assembly.entries().entrySet()) {
                EntryNames.require(entry.getKey());
                Path destination = staging.resolve(entry.getKey()).normalize();
                if (!destination.startsWith(staging)) {
                    throw new ArtifactAssemblyException("class-directory entry escapes staging root");
                }
                Files.createDirectories(destination.getParent());
                writeFile(destination, entry.getValue());
            }
            forceDirectoryTree(staging);
            publishDirectory(staging, target, options.force());
            published = true;
        } finally {
            if (!published) {
                deleteTree(staging);
            }
        }
    }

    static void writeJar(ArtifactAssembly assembly, Path output,
                          ArtifactWriteOptions options) throws IOException {
        Path target = prepareTarget(output, options);
        Path parent = parent(target);
        Files.createDirectories(parent);
        rejectSymbolicPath(target);
        Path staging = sibling(parent, target.getFileName() + ".lyra-staging-");
        boolean published = false;
        try {
            writeJarFile(assembly, staging);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.READ)) {
                channel.force(true);
            }
            publishFile(staging, target, options.force());
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private static void writeJarFile(ArtifactAssembly assembly, Path staging) throws IOException {
        TreeMap<String, byte[]> entries = new TreeMap<>(EntryNames.utf8Comparator());
        entries.put("META-INF/MANIFEST.MF", manifest(assembly.metadata()));
        for (Map.Entry<String, byte[]> entry : assembly.entries().entrySet()) {
            if (entries.put(entry.getKey(), entry.getValue()) != null) {
                throw new ArtifactAssemblyException("artifact entries may not replace the manifest");
            }
        }
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            DeterministicZipWriter writer = new DeterministicZipWriter(Channels.newOutputStream(channel));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writer.entry(entry.getKey(), entry.getValue());
            }
            writer.finish();
            setEpochTime(staging);
            channel.force(true);
        }
    }

    private static byte[] manifest(ArtifactMetadata metadata) {
        ArrayList<ManifestAttribute> attributes = new ArrayList<>(List.of(
                new ManifestAttribute("Lyra-Artifact-Id", metadata.artifactId()),
                new ManifestAttribute("Lyra-Artifact-Revision", metadata.artifactRevision().value()),
                new ManifestAttribute("Lyra-Java-Profile", metadata.profile().name()),
                new ManifestAttribute("Lyra-Packaging-Mode", metadata.packagingMode().canonicalSpelling()),
                new ManifestAttribute("Lyra-Preview-Required", Boolean.toString(metadata.previewRequired()))));
        if (metadata.packagingMode() == io.mindspice.lyra.runtime.PackagingMode.BUNDLED_JAR) {
            // Profile-aware launcher composition: ordinary bundles run the
            // dependency-free runtime launcher; debug-capable bundles run the
            // fixed REPL launcher that owns the closure preflight.
            attributes.add(new ManifestAttribute("Main-Class", metadata.replCapable()
                    ? "io.mindspice.lyra.repl.ReplLauncher"
                    : "io.mindspice.lyra.runtime.LyraLauncher"));
        }
        ArrayList<ManifestAttribute> sorted = new ArrayList<>(attributes);
        sorted.sort(Comparator.comparing(ManifestAttribute::name));
        StringBuilder result = new StringBuilder();
        appendManifestAttribute(result, "Manifest-Version", "1.0");
        for (ManifestAttribute attribute : sorted) {
            appendManifestAttribute(result, attribute.name(), attribute.value());
        }
        result.append("\r\n");
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendManifestAttribute(StringBuilder result, String name, String value) {
        if (name.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf(0) >= 0) {
            throw new ArtifactAssemblyException("invalid manifest attribute");
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        int valueOffset = 0;
        // The 72-byte manifest limit includes CRLF.  The attribute prefix
        // therefore leaves two fewer payload bytes than the visible line
        // prefix alone would suggest.
        int firstCapacity = 72 - nameBytes.length - 2 - 2;
        if (firstCapacity < 0) {
            throw new ArtifactAssemblyException("manifest attribute name is too long: " + name);
        }
        valueOffset = appendManifestChunk(result, name + ": ", valueBytes, valueOffset,
                firstCapacity);
        while (valueOffset < valueBytes.length) {
            result.append("\r\n ");
            // A continuation line has a leading space and CRLF, so it
            // can carry at most 69 value bytes within the 72-byte limit.
            valueOffset = appendManifestChunk(result, "", valueBytes, valueOffset, 69);
        }
        result.append("\r\n");
    }

    private static int appendManifestChunk(StringBuilder result, String prefix, byte[] value,
                                           int offset, int capacity) {
        int end = offset;
        int used = 0;
        while (end < value.length) {
            int codePointLength = utf8CodePointLength(value[end]);
            if (used + codePointLength > capacity) {
                break;
            }
            if (end + codePointLength > value.length) {
                throw new ArtifactAssemblyException("manifest value has malformed UTF-8");
            }
            for (int index = 1; index < codePointLength; index++) {
                if ((value[end + index] & 0xc0) != 0x80) {
                    throw new ArtifactAssemblyException("manifest value has malformed UTF-8");
                }
            }
            used += codePointLength;
            end += codePointLength;
        }
        if (offset == end && offset < value.length) {
            throw new ArtifactAssemblyException("manifest value cannot fit one UTF-8 line");
        }
        result.append(prefix).append(new String(value, offset, end - offset, StandardCharsets.UTF_8));
        return end;
    }

    private static int utf8CodePointLength(byte first) {
        int value = first & 0xff;
        if (value < 0x80) return 1;
        if ((value & 0xe0) == 0xc0) return 2;
        if ((value & 0xf0) == 0xe0) return 3;
        if ((value & 0xf8) == 0xf0) return 4;
        throw new ArtifactAssemblyException("manifest value has malformed UTF-8");
    }

    private record ManifestAttribute(String name, String value) {
    }

    /** Minimal ZIP writer so the ZIP epoch can be encoded without JDK extras. */
    private static final class DeterministicZipWriter {
        private static final int UTF8_FLAG = 0x800;
        private final OutputStream output;
        private final List<CentralEntry> centralEntries = new ArrayList<>();
        private long written;

        private DeterministicZipWriter(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        private void entry(String name, byte[] bytes) throws IOException {
            EntryNames.require(name);
            Objects.requireNonNull(bytes, "bytes");
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 0xffff || bytes.length > 0xffff_ffffL
                    || written > 0xffff_ffffL) {
                throw new ArtifactAssemblyException("ZIP32 limits exceeded by artifact entry: " + name);
            }
            CRC32 crc = new CRC32();
            crc.update(bytes);
            long offset = written;
            u4(0x04034b50L); // local-file header
            u2(10); // version needed for stored entries
            u2(UTF8_FLAG);
            u2(0); // stored method
            u2(0); // ZIP epoch time
            u2(0x21); // 1980-01-01, the earliest representable DOS/ZIP date
            u4(crc.getValue());
            u4(bytes.length);
            u4(bytes.length);
            u2(nameBytes.length);
            u2(0); // no local extra fields
            write(nameBytes);
            write(bytes);
            centralEntries.add(new CentralEntry(nameBytes, crc.getValue(), bytes.length, offset));
        }

        private void finish() throws IOException {
            if (centralEntries.size() > 0xffff) {
                throw new ArtifactAssemblyException("ZIP32 entry-count limit exceeded");
            }
            long centralOffset = written;
            for (CentralEntry entry : centralEntries) {
                u4(0x02014b50L); // central-directory header
                u2(20); // made by, DOS-compatible creator
                u2(10);
                u2(UTF8_FLAG);
                u2(0);
                u2(0);
                u2(0x21);
                u4(entry.crc());
                u4(entry.size());
                u4(entry.size());
                u2(entry.name().length);
                u2(0); // no central extra fields
                u2(0); // no comment
                u2(0); // disk number
                u2(0); // internal attributes
                u4(0); // external attributes
                u4(entry.offset());
                write(entry.name());
            }
            long centralSize = written - centralOffset;
            if (centralOffset > 0xffff_ffffL || centralSize > 0xffff_ffffL) {
                throw new ArtifactAssemblyException("ZIP32 central-directory limits exceeded");
            }
            u4(0x06054b50L); // end of central directory
            u2(0);
            u2(0);
            u2(centralEntries.size());
            u2(centralEntries.size());
            u4(centralSize);
            u4(centralOffset);
            u2(0); // no archive comment
            output.flush();
        }

        private void write(byte[] bytes) throws IOException {
            output.write(bytes);
            written += bytes.length;
        }

        private void u2(int value) throws IOException {
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
            written += 2;
        }

        private void u4(long value) throws IOException {
            output.write((int) value & 0xff);
            output.write((int) (value >>> 8) & 0xff);
            output.write((int) (value >>> 16) & 0xff);
            output.write((int) (value >>> 24) & 0xff);
            written += 4;
        }

        private record CentralEntry(byte[] name, long crc, long size, long offset) {
        }
    }

    private static Path prepareTarget(Path output, ArtifactWriteOptions options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        Path target = output.toAbsolutePath().normalize();
        if (target.getFileName() == null) {
            throw new ArtifactAssemblyException("publication target has no file name");
        }
        rejectSymbolicPath(target);
        if (Files.isSymbolicLink(target)) {
            throw new ArtifactAssemblyException("publication target must not be a symbolic link: " + output);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !options.force()) {
            throw new ArtifactAssemblyException("publication target already exists: " + output);
        }
        return target;
    }

    private static void rejectSymbolicPath(Path target) {
        Path current = target.getRoot();
        for (Path component : target) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new ArtifactAssemblyException(
                        "publication path must not contain a symbolic link: " + current);
            }
        }
    }

    private static Path parent(Path target) {
        Path parent = target.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    private static Path sibling(Path parent, String prefix) throws IOException {
        for (int attempt = 0; attempt < 32; attempt++) {
            Path candidate = parent.resolve(prefix + UUID.randomUUID());
            if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("could not allocate an artifact staging path");
    }

    private static void writeFile(Path destination, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            setEpochTime(destination);
            channel.force(true);
        }
    }

    private static void publishFile(Path staging, Path target, boolean force) throws IOException {
        if (!force) {
            atomicMove(staging, target);
            forceDirectory(target.getParent());
            return;
        }
        publishWithBackup(staging, target);
    }

    private static void publishDirectory(Path staging, Path target, boolean force) throws IOException {
        if (!force && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (!force) {
            atomicMove(staging, target);
            forceDirectory(target.getParent());
            return;
        }
        publishWithBackup(staging, target);
    }

    /**
     * Publishes after moving an existing target out of the way.  The backup is
     * required for both file and directory output: a non-atomic filesystem
     * fallback must not destroy the previous artifact when the new move or
     * directory fsync fails.
     */
    private static void publishWithBackup(Path staging, Path target)
            throws IOException {
        Path backup = sibling(target.getParent(), target.getFileName() + ".lyra-backup-");
        boolean hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        boolean backedUp = false;
        boolean targetPublished = false;
        try {
            if (hadTarget) {
                atomicMove(target, backup);
                backedUp = true;
            }
            atomicMove(staging, target);
            targetPublished = true;
            forceDirectory(target.getParent());
            if (backedUp) {
                deleteTree(backup);
                forceDirectory(target.getParent());
            }
        } catch (IOException | RuntimeException exception) {
            try {
                if (backedUp && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        deleteTree(target);
                    }
                    if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                        atomicMove(backup, target);
                    }
                } else if (!hadTarget && targetPublished
                        && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(target);
                }
            } catch (IOException rollback) {
                exception.addSuppressed(rollback);
            }
            throw exception;
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        // Never silently degrade publication to a visible non-atomic move.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void forceDirectoryTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            setEpochTime(path);
                            forceDirectory(path);
                        } catch (IOException exception) {
                            throw new UncheckedIoException(exception);
                        }
                    });
        } catch (UncheckedIoException exception) {
            throw exception.exception;
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            // Some file systems do not allow directory channels.  The staged
            // files are still complete before the publication rename.
        }
    }

    private static void setEpochTime(Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(0L));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            // The publication remains safe on file systems without writable
            // timestamps; fsync/rename still occur where supported.
        } catch (IOException exception) {
            throw new ArtifactAssemblyException("cannot normalize artifact timestamp: " + path,
                    exception);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIoException(exception);
                }
            });
        } catch (UncheckedIoException exception) {
            throw exception.exception;
        }
    }

    private static final class UncheckedIoException extends RuntimeException {
        private final IOException exception;

        private UncheckedIoException(IOException exception) {
            this.exception = exception;
        }
    }
}
