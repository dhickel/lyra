package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.EvaluationStatus;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.SessionRevision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteProtocolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void framesAreLengthPrefixedUtf8AndBoundedBeforeAllocation() throws Exception {
        FrameCodec codec = new FrameCodec(8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.writeUtf8Frame(output, "hé");

        assertEquals(Optional.of("hé"), codec.readUtf8Frame(
                new ByteArrayInputStream(output.toByteArray())));
        assertThrows(ProtocolException.class, () -> codec.readFrame(
                new ByteArrayInputStream(new byte[] {0, 0, 0, 9})));
        assertThrows(ProtocolException.class, () -> codec.readUtf8Frame(
                new ByteArrayInputStream(new byte[] {0, 0, 0, 2, (byte) 0xc3, 0x28})));
        assertThrows(IllegalArgumentException.class,
                () -> codec.writeFrame(new ByteArrayOutputStream(), new byte[9]));

        ProtocolMessage.Authenticate authenticate = new ProtocolMessage.Authenticate(
                UUID.randomUUID(), new byte[RemoteProtocol.CHALLENGE_BYTES],
                "A".repeat(43));
        assertTrue(authenticate.toString().contains("<redacted>"));
        assertFalse(authenticate.toString().contains("A".repeat(43)));
    }

    @Test
    void schemaRoundTripsSourceMetadataAndRejectsUnknownDuplicateOrUnsupportedFields()
            throws Exception {
        EvaluationSource source = new EvaluationSource(
                new io.mindspice.lyra.repl.SourceOrigin(
                        "editor", Optional.of(java.net.URI.create("file:///tmp/a.lyra")),
                        Optional.of(4L), 10, 13),
                "a😀");
        ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                UUID.randomUUID(), 1, SessionRevision.initial(), source);

        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(request));
        assertEquals(request, decoded);

        byte[] unknown = "{\"kind\":\"clientHello\",\"version\":1,\"clientId\":\"00000000-0000-0000-0000-000000000001\",\"extra\":true}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(ProtocolException.Reason.INVALID_SCHEMA,
                assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(unknown)).reason());

        byte[] duplicate = "{\"kind\":\"clientHello\",\"version\":1,\"version\":1,\"clientId\":\"00000000-0000-0000-0000-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(duplicate));

        byte[] unsupported = "{\"kind\":\"clientHello\",\"version\":2,\"clientId\":\"00000000-0000-0000-0000-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(ProtocolException.Reason.UNSUPPORTED_VERSION,
                assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(unsupported)).reason());
    }

    @Test
    void statusMessagesRejectTerminalStatusesDuringConstructionAndDecode() {
        UUID requestId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessage.Status(
                RemoteProtocol.VERSION, requestId, 1, ProtocolMessage.RemoteStatus.SUCCESS,
                0, Optional.empty()));

        byte[] terminalStatus = ("{\"kind\":\"status\",\"version\":1,"
                + "\"requestId\":\"" + requestId + "\",\"sequence\":1,"
                + "\"status\":\"SUCCESS\",\"revision\":0}")
                .getBytes(StandardCharsets.UTF_8);
        ProtocolException failure = assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decode(terminalStatus));
        assertEquals(ProtocolException.Reason.INVALID_SCHEMA, failure.reason());
    }

    @Test
    void scalarSnapshotsPreserveEmptyControlsAndUtf16Surrogates() throws Exception {
        ProtocolMessage.ValueSnapshot original = new ProtocolMessage.ValueSnapshot(
                "String", new ProtocolMessage.Scalar("STRING", "\u0001\uD800"));

        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(
                new ProtocolMessage.Result(
                        UUID.randomUUID(), 1, ProtocolMessage.RemoteStatus.SUCCESS, 0,
                        List.of(), Optional.of(original), Optional.empty(), Optional.empty())));

        ProtocolMessage.Result result = assertInstanceOf(ProtocolMessage.Result.class, decoded);
        assertEquals(original, result.value().orElseThrow());
        assertEquals("\u0001\uD800",
                ((ProtocolMessage.Scalar) result.value().orElseThrow().data()).value());
    }

    @Test
    void cancelledAdapterAdmissionDoesNotInvokeSessionSubmission() {
        try (LyraSession session = LyraSession.open()) {
            LyraSessionAdapter adapter = LyraSessionAdapter.of(session);
            EvaluationRequest request = new EvaluationRequest(
                    EvaluationId.create(), SessionRevision.initial(),
                    EvaluationSource.of("cancel-before-submit.lyra", "not evaluated"));
            RemoteCancellation cancellation = new RemoteCancellation();
            assertTrue(cancellation.request());

            assertEquals(EvaluationStatus.CANCELLED,
                    adapter.evaluate(request, cancellation).status());
        }
    }

    @Test
    void positiveSubMillisecondClientTimeoutsBecomeFiniteSocketTimeouts() {
        RemoteClientOptions options = RemoteClientOptions.builder()
                .connectTimeout(Duration.ofNanos(1))
                .handshakeTimeout(Duration.ofNanos(1))
                .build();

        assertEquals(Duration.ofMillis(1), options.connectTimeout());
        assertEquals(Duration.ofMillis(1), options.handshakeTimeout());
        assertEquals(1, RemoteClient.timeoutMillis(options.connectTimeout()));
        assertEquals(1, RemoteClient.timeoutMillis(options.handshakeTimeout()));
    }

    @Test
    void credentialFilesAreExclusiveOwnerRestrictedAndComparedWithoutTokenPrinting()
            throws Exception {
        Path path = temporaryDirectory.resolve("credential");
        TokenCredential credential = TokenCredential.create(path);
        try {
            assertTrue(Files.isRegularFile(path));
            assertThrows(IOException.class, () -> TokenCredential.create(path));
            TokenCredential loaded = TokenCredential.read(path);
            try {
                assertTrue(loaded.matches(credential.encodedForTransport()));
                assertFalse(loaded.matches("not-a-token"));
                assertFalse(loaded.toString().contains(credential.encodedForTransport()));
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                    assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
                    assertTrue(permissions.stream().allMatch(permission ->
                            permission == PosixFilePermission.OWNER_READ
                                    || permission == PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                    // The protocol remains exclusive on non-POSIX file systems.
                }
            } finally {
                loaded.close();
            }
        } finally {
            credential.close();
        }

        Path target = temporaryDirectory.resolve("target");
        Files.writeString(target, "not a credential", StandardCharsets.US_ASCII);
        Path link = temporaryDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, target.getFileName());
            assertThrows(IOException.class, () -> TokenCredential.create(link));
            assertThrows(IOException.class, () -> TokenCredential.read(link));

            Path realParent = temporaryDirectory.resolve("real-parent");
            Files.createDirectories(realParent.resolve("nested"));
            Path ancestorLink = temporaryDirectory.resolve("ancestor-link");
            Files.createSymbolicLink(ancestorLink, realParent.getFileName());
            Path throughAncestor = ancestorLink.resolve("nested").resolve("credential");
            assertThrows(IOException.class, () -> TokenCredential.create(throughAncestor));

            Path realCredentialPath = realParent.resolve("nested").resolve("real-credential");
            try (TokenCredential nestedCredential = TokenCredential.create(realCredentialPath)) {
                assertThrows(IOException.class, () -> TokenCredential.read(
                        ancestorLink.resolve("nested").resolve("real-credential")));
            }
        } catch (UnsupportedOperationException ignored) {
            // Symlink support is platform-specific; the POSIX path above is validated here.
        }
    }

    @Test
    void endpointValidationRejectsLanAndWildcardAddresses() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getByName("0.0.0.0"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getByName("8.8.8.8"), 1));
        assertTrue(LoopbackEndpoint.of("localhost", 1).address().isLoopbackAddress());
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteEndpoint(LoopbackEndpoint.bind(0),
                        temporaryDirectory.resolve("token")));
    }
}
