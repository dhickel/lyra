package io.mindspice.lyra.repl.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A 256-bit credential backed by an owner-restricted, exclusively-created file.
 * The token is never included in {@link #toString()} or endpoint descriptions.
 */
public final class TokenCredential implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FILE_BYTES = 128;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path path;
    private byte[] token;

    private TokenCredential(Path path, byte[] token) {
        this.path = path.toAbsolutePath().normalize();
        this.token = token.clone();
    }

    /** Generates and exclusively creates a credential at {@code path}. */
    public static TokenCredential create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path normalized = path.toAbsolutePath().normalize();
        byte[] token = new byte[RemoteProtocol.TOKEN_BYTES];
        RANDOM.nextBytes(token);
        byte[] fileContents = encodedFileContents(token);
        try {
            try (SecureParent parent = openSecureParent(normalized)) {
                boolean created = false;
                try {
                    SeekableByteChannel channel = createExclusive(parent);
                    created = true;
                    try (channel) {
                        writeCreatedFile(channel, fileContents);
                    }
                    validateSecureFile(parent);
                    return new TokenCredential(normalized, token);
                } catch (IOException | RuntimeException failure) {
                    if (created) {
                        parent.deleteCreatedQuietly();
                    }
                    throw failure;
                }
            }
        } finally {
            Arrays.fill(fileContents, (byte) 0);
            Arrays.fill(token, (byte) 0);
        }
    }

    /** Creates a credential at a fresh, random file name in the default temp directory. */
    public static TokenCredential createTemporary() throws IOException {
        Path directory = Path.of(System.getProperty("java.io.tmpdir", "."))
                .toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 32; attempt++) {
            byte[] randomName = new byte[12];
            RANDOM.nextBytes(randomName);
            String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(randomName);
            Path candidate = directory.resolve("lyra-repl-" + suffix + ".token");
            try {
                return create(candidate);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Extremely unlikely; retry with another cryptographically random name.
            } finally {
                Arrays.fill(randomName, (byte) 0);
            }
        }
        throw new IOException("could not allocate a unique credential path");
    }

    /** Reads and validates an existing credential without following path symlinks. */
    public static TokenCredential read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path normalized = path.toAbsolutePath().normalize();
        try (SecureParent parent = openSecureParent(normalized)) {
            BasicFileAttributes beforeOpen = validateSecureFile(parent);
            long size = beforeOpen.size();
            if (size <= 0 || size > MAX_FILE_BYTES) {
                throw new IOException("credential file has an invalid size");
            }

            byte[] contents;
            try (SeekableByteChannel channel = openForRead(parent)) {
                BasicFileAttributes afterOpen = validateSecureFile(parent);
                if (!sameFile(beforeOpen, afterOpen)) {
                    throw new IOException("credential file changed while opening");
                }
                if (afterOpen.size() != size) {
                    throw new IOException("credential file changed while opening");
                }
                contents = readBounded(channel, size);
            }
            try {
                return parse(normalized, contents);
            } finally {
                Arrays.fill(contents, (byte) 0);
            }
        }
    }

    public Path path() {
        return path;
    }

    /** Constant-time comparison against a fixed-size decoded token. */
    public boolean matches(String presented) {
        Objects.requireNonNull(presented, "presented");
        byte[] candidate = new byte[RemoteProtocol.TOKEN_BYTES];
        boolean valid = presented.length() == 43;
        try {
            byte[] decoded;
            if (valid) {
                try {
                    decoded = Base64.getUrlDecoder().decode(presented);
                } catch (IllegalArgumentException ignored) {
                    decoded = new byte[0];
                    valid = false;
                }
            } else {
                decoded = new byte[0];
            }
            if (decoded.length == candidate.length) {
                System.arraycopy(decoded, 0, candidate, 0, candidate.length);
            } else {
                valid = false;
            }
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(candidate);
            valid &= canonical.equals(presented);
            // Always compare the same number of bytes, even for malformed or
            // wrong-length credentials.
            return MessageDigest.isEqual(token, candidate) && valid;
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    @Override
    public void close() {
        Arrays.fill(token, (byte) 0);
    }

    @Override
    public String toString() {
        return "TokenCredential[path=" + path + "]";
    }

    String encodedForTransport() {
        byte[] copy = token.clone();
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(copy);
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    private static byte[] encodedFileContents(byte[] token) {
        byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(token);
        byte[] result = Arrays.copyOf(encoded, encoded.length + 1);
        result[encoded.length] = '\n';
        Arrays.fill(encoded, (byte) 0);
        return result;
    }

    private static SeekableByteChannel createExclusive(SecureParent parent) throws IOException {
        FileAttribute<Set<PosixFilePermission>> attribute =
                PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE);
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        return parent.directory.newByteChannel(parent.fileName, options, attribute);
    }

    private static SeekableByteChannel openForRead(SecureParent parent) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        return parent.directory.newByteChannel(parent.fileName, options);
    }

    private static void writeCreatedFile(SeekableByteChannel channel, byte[] contents)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(contents);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        if (channel instanceof FileChannel fileChannel) {
            fileChannel.force(true);
        }
    }

    private static TokenCredential parse(Path path, byte[] contents) throws IOException {
        if (contents.length == 0 || contents.length > MAX_FILE_BYTES) {
            throw new IOException("credential file has an invalid size");
        }
        long size = contents.length;
        String encoded = new String(contents, java.nio.charset.StandardCharsets.US_ASCII);
        if (encoded.length() != size || !encoded.endsWith("\n")) {
            throw new IOException("credential file must end with a newline");
        }
        encoded = encoded.substring(0, encoded.length() - 1);
        if (encoded.endsWith("\r") || encoded.isEmpty()) {
            throw new IOException("credential file has an invalid token format");
        }
        byte[] token = null;
        try {
            try {
                token = Base64.getUrlDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new IOException("credential file has an invalid token format", exception);
            }
            if (token.length != RemoteProtocol.TOKEN_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(token)
                    .equals(encoded)) {
                throw new IOException("credential file has an invalid token length");
            }
            return new TokenCredential(path, token);
        } finally {
            if (token != null) {
                Arrays.fill(token, (byte) 0);
            }
        }
    }

    private static BasicFileAttributes validateSecureFile(SecureParent parent) throws IOException {
        BasicFileAttributeView basic = parent.directory.getFileAttributeView(
                parent.fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (basic == null) {
            throw new IOException("credential provider lacks secure file attributes");
        }
        BasicFileAttributes attributes = basic.readAttributes();
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("credential path is not a regular file");
        }

        PosixFileAttributeView posix = parent.directory.getFileAttributeView(
                parent.fileName, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            throw new IOException("credential provider lacks ownership and permission attributes");
        }
        PosixFileAttributes posixAttributes = posix.readAttributes();
        Set<PosixFilePermission> permissions = posixAttributes.permissions();
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || permissions.stream().anyMatch(permission ->
                permission != PosixFilePermission.OWNER_READ
                        && permission != PosixFilePermission.OWNER_WRITE)) {
            throw new IOException("credential file permissions are not owner-restricted");
        }
        UserPrincipal expected = currentUser();
        if (!posixAttributes.owner().equals(expected)) {
            throw new IOException("credential file owner is not the current user");
        }
        return attributes;
    }

    private static UserPrincipal currentUser() throws IOException {
        String name;
        try {
            name = System.getProperty("user.name");
        } catch (SecurityException failure) {
            throw new IOException("current user cannot be verified", failure);
        }
        if (name == null || name.isBlank()) {
            throw new IOException("current user cannot be verified");
        }
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(name);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("credential owner cannot be verified", failure);
        }
    }

    private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
        Object firstKey = first.fileKey();
        Object secondKey = second.fileKey();
        return firstKey != null && firstKey.equals(secondKey);
    }

    private static byte[] readBounded(SeekableByteChannel channel, long expectedSize)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize);
        ByteBuffer buffer = ByteBuffer.allocate(32);
        long total = 0;
        int read;
        while ((read = channel.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > MAX_FILE_BYTES - read) {
                throw new IOException("credential file exceeds the bounded size");
            }
            buffer.flip();
            output.write(buffer.array(), buffer.arrayOffset() + buffer.position(), read);
            total += read;
            buffer.clear();
        }
        if (total != expectedSize) {
            throw new IOException("credential file changed while reading");
        }
        return output.toByteArray();
    }

    private static SecureParent openSecureParent(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root == null || normalized.getNameCount() == 0) {
            throw new IOException("credential path has no parent directory");
        }

        DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
        if (!(rootStream instanceof SecureDirectoryStream<?>)) {
            try {
                rootStream.close();
            } catch (IOException ignored) {
            }
            throw new IOException("credential provider lacks secure directory operations");
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) rootStream;
        List<SecureDirectoryStream<Path>> streams = new ArrayList<>();
        streams.add(current);
        try {
            for (int index = 0; index < normalized.getNameCount() - 1; index++) {
                Path component = normalized.getName(index);
                BasicFileAttributeView view = current.getFileAttributeView(
                        component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    throw new IOException("credential path lacks secure directory attributes");
                }
                BasicFileAttributes attributes = view.readAttributes();
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IOException("credential path contains a symlink or non-directory");
                }
                SecureDirectoryStream<Path> next = current.newDirectoryStream(
                        component, LinkOption.NOFOLLOW_LINKS);
                streams.add(next);
                current = next;
            }
            return new SecureParent(current, normalized.getFileName(), streams);
        } catch (IOException failure) {
            closeStreamsAfterFailure(streams, failure);
            throw failure;
        } catch (RuntimeException failure) {
            closeStreamsAfterFailure(streams, failure);
            throw failure;
        }
    }

    private static void closeStreamsAfterFailure(
            List<SecureDirectoryStream<Path>> streams, Throwable failure) {
        try {
            closeStreams(streams);
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeStreams(List<SecureDirectoryStream<Path>> streams) throws IOException {
        IOException failure = null;
        for (int index = streams.size() - 1; index >= 0; index--) {
            try {
                streams.get(index).close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class SecureParent implements AutoCloseable {
        private final SecureDirectoryStream<Path> directory;
        private final Path fileName;
        private final List<SecureDirectoryStream<Path>> streams;

        private SecureParent(
                SecureDirectoryStream<Path> directory,
                Path fileName,
                List<SecureDirectoryStream<Path>> streams) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.fileName = Objects.requireNonNull(fileName, "fileName");
            this.streams = List.copyOf(streams);
        }

        private void deleteCreatedQuietly() {
            try {
                directory.deleteFile(fileName);
            } catch (IOException | RuntimeException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            closeStreams(streams);
        }
    }
}
