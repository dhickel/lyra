package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.repl.SourceOrigin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version-two protocol framing, schema, endpoint and credential-surface
 * assertions. The v2 handshake is a single credential-free hello exchange
 * carrying session identity, revision, mutation sequence and active request;
 * version-one peers receive an explicit upgrade diagnostic.
 */
class RemoteProtocolV2Test {
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
    }

    @Test
    void schemaRoundTripsSourceMetadataAndRejectsUnknownDuplicateOrOldVersions()
            throws Exception {
        EvaluationSource source = new EvaluationSource(
                new SourceOrigin(
                        "editor", Optional.of(java.net.URI.create("file:///tmp/a.lyra")),
                        Optional.of(4L), 10, 13),
                "a😀");
        ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                UUID.randomUUID(), 1, SessionRevision.initial(), 3, source);

        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(request));
        assertEquals(request, decoded);

        byte[] unknown = "{\"kind\":\"clientHello\",\"version\":2,\"clientId\":\"00000000-0000-0000-0000-000000000001\",\"extra\":true}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(ProtocolException.Reason.INVALID_SCHEMA,
                assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(unknown)).reason());

        byte[] duplicate = "{\"kind\":\"clientHello\",\"version\":2,\"version\":2,\"clientId\":\"00000000-0000-0000-0000-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(duplicate));

        // Version-one peers are decoded far enough to identify the version
        // and are then rejected with an explicit upgrade reason.
        byte[] versionOne = "{\"kind\":\"clientHello\",\"version\":1,\"clientId\":\"00000000-0000-0000-0000-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(ProtocolException.Reason.UNSUPPORTED_VERSION,
                assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(versionOne)).reason());
    }

    @Test
    void helloCarriesSessionRevisionMutationSequenceAndActiveRequest() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        ProtocolMessage.ServerHello hello = new ProtocolMessage.ServerHello(
                RemoteProtocol.VERSION, sessionId, 7, 3, 12, Optional.of(active));

        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(hello));
        assertEquals(hello, decoded);
        assertInstanceOf(ProtocolMessage.ServerHello.class, decoded);
    }

    @Test
    void loadReloadAndCompletionSchemasRoundTripWithBounds() throws Exception {
        ProtocolMessage.LoadRequest load = new ProtocolMessage.LoadRequest(
                UUID.randomUUID(), 1, SessionRevision.initial(), 0, "/tmp/x.lyra");
        assertEquals(load, ProtocolCodec.decode(ProtocolCodec.encode(load)));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessage.LoadRequest(
                UUID.randomUUID(), 1, SessionRevision.initial(), 0,
                "x".repeat(RemoteProtocol.MAX_LOAD_PATH_CHARACTERS + 1)));

        ProtocolMessage.ReloadRequest reload = new ProtocolMessage.ReloadRequest(
                UUID.randomUUID(), 2, new SessionRevision(1), 0, "m->answer");
        assertEquals(reload, ProtocolCodec.decode(ProtocolCodec.encode(reload)));

        ProtocolMessage.CompletionRequest completion = new ProtocolMessage.CompletionRequest(
                RemoteProtocol.VERSION, UUID.randomUUID(),
                ProtocolMessage.CompletionKind.BINDING_MEMBERS, 1, 0,
                Optional.empty(), Optional.of("values"));
        assertEquals(completion, ProtocolCodec.decode(ProtocolCodec.encode(completion)));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessage.CompletionRequest(
                RemoteProtocol.VERSION, UUID.randomUUID(),
                ProtocolMessage.CompletionKind.BINDING_MEMBERS, 1, 0,
                Optional.empty(), Optional.empty()));

        ProtocolMessage.CompletionResult result = new ProtocolMessage.CompletionResult(
                RemoteProtocol.VERSION, UUID.randomUUID(), ProtocolMessage.QueryStatus.OK,
                1, 0, List.of(new ProtocolMessage.CompletionItem("length",
                        ProtocolMessage.CompletionItemKind.MEMBER, Optional.of("I32"))),
                Optional.empty());
        assertEquals(result, ProtocolCodec.decode(ProtocolCodec.encode(result)));
        assertEquals(1, result.items().size());
        assertEquals("length", result.items().getFirst().name());
        assertEquals(Optional.of("I32"), result.items().getFirst().typeSpelling());
    }

    @Test
    void queryRetainsTerminalStatusWhenNestedPayloadIsOmitted() throws Exception {
        UUID requestId = UUID.randomUUID();
        ProtocolMessage.QueryResult query = new ProtocolMessage.QueryResult(
                RemoteProtocol.VERSION, UUID.randomUUID(), ProtocolMessage.QueryKind.REQUEST,
                ProtocolMessage.QueryStatus.OK, 1, 2,
                Optional.of(new ProtocolMessage.RequestSnapshot(requestId, 3,
                        ProtocolMessage.RemoteStatus.SUCCESS, 1, Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(ProtocolMessage.RemoteStatus.SUCCESS));

        ProtocolMessage.QueryResult decoded = assertInstanceOf(ProtocolMessage.QueryResult.class,
                ProtocolCodec.decode(ProtocolCodec.encode(query)));
        assertEquals(Optional.of(ProtocolMessage.RemoteStatus.SUCCESS), decoded.terminalStatus());
        assertEquals(query, decoded);
    }

    @Test
    void resultCarriesBoundedRealInitializerProgress() throws Exception {
        ProtocolMessage.Result result = new ProtocolMessage.Result(
                RemoteProtocol.VERSION, UUID.randomUUID(), 1,
                ProtocolMessage.RemoteStatus.SUCCESS, 2, 1,
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(
                        new ProtocolMessage.Initializer("m->answer",
                                ProtocolMessage.InitializerState.SCHEDULED),
                        new ProtocolMessage.Initializer("m->answer",
                                ProtocolMessage.InitializerState.COMPLETED)));

        ProtocolMessage.Result decoded = assertInstanceOf(ProtocolMessage.Result.class,
                ProtocolCodec.decode(ProtocolCodec.encode(result)));
        assertEquals(2, decoded.initializers().size());
        assertEquals(ProtocolMessage.InitializerState.COMPLETED,
                decoded.initializers().get(1).state());
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessage.Result(
                RemoteProtocol.VERSION, UUID.randomUUID(), 1,
                ProtocolMessage.RemoteStatus.SUCCESS, 0, 0,
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                java.util.Collections.nCopies(RemoteProtocol.MAX_INITIALIZERS + 1,
                        new ProtocolMessage.Initializer("m->x",
                                ProtocolMessage.InitializerState.SCHEDULED))));
    }

    @Test
    void statusMessagesRejectTerminalStatusesDuringConstructionAndDecode() {
        UUID requestId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessage.Status(
                RemoteProtocol.VERSION, requestId, 1, ProtocolMessage.RemoteStatus.SUCCESS,
                0, 0, Optional.empty()));

        byte[] terminalStatus = ("{\"kind\":\"status\",\"version\":2,"
                + "\"requestId\":\"" + requestId + "\",\"sequence\":1,"
                + "\"status\":\"SUCCESS\",\"revision\":0,\"mutationSequence\":0}")
                .getBytes(StandardCharsets.UTF_8);
        ProtocolException failure = assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decode(terminalStatus));
        assertEquals(ProtocolException.Reason.INVALID_SCHEMA, failure.reason());
    }

    @Test
    void scalarSnapshotsPreserveEscapedJsonBoundaryBytesAndUtf16Surrogates()
            throws Exception {
        // Control characters, quotes, backslashes, unpaired surrogates and
        // astral characters must survive the JSON boundary byte-for-byte.
        String boundary = "\u0001\"\\\n\uD800\ud83d\ude00\u007f";
        ProtocolMessage.ValueSnapshot original = new ProtocolMessage.ValueSnapshot(
                "String", new ProtocolMessage.Scalar("STRING", boundary));

        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(
                new ProtocolMessage.Result(
                        UUID.randomUUID(), 1, ProtocolMessage.RemoteStatus.SUCCESS, 0, 0,
                        List.of(), Optional.of(original), Optional.empty(), Optional.empty())));

        ProtocolMessage.Result result = assertInstanceOf(ProtocolMessage.Result.class, decoded);
        assertEquals(original, result.value().orElseThrow());
        assertEquals(boundary,
                ((ProtocolMessage.Scalar) result.value().orElseThrow().data()).value());
    }

    @Test
    void rangeSnapshotsRoundTripWithoutTraversingTheirElements() throws Exception {
        var snapshot = new ProtocolMessage.ValueSnapshot("Range<I64>", new ProtocolMessage.Scalar(
                "RANGE", "(-9223372036854775808...9223372036854775807:1)"));
        var result = new ProtocolMessage.Result(UUID.randomUUID(), 1, ProtocolMessage.RemoteStatus.SUCCESS,
                0, 0, List.of(), Optional.of(snapshot), Optional.empty(), Optional.empty());
        assertEquals(result, ProtocolCodec.decode(ProtocolCodec.encode(result)));
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
    void endpointValidationRejectsWildcardNonLoopbackAndPortZero() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getByName("0.0.0.0"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getByName("8.8.8.8"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getByName("10.0.0.1"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getLoopbackAddress(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new LoopbackEndpoint(InetAddress.getLoopbackAddress(), 65536));

        // Supported loopback forms resolve and bind to loopback only.
        assertTrue(LoopbackEndpoint.of("localhost", 1).address().isLoopbackAddress());
        assertTrue(LoopbackEndpoint.of("127.0.0.1", 1).address().isLoopbackAddress());
        try {
            InetAddress ipv6 = InetAddress.getByName("::1");
            if (ipv6.isLoopbackAddress()) {
                assertTrue(LoopbackEndpoint.of("::1", 1).address().isLoopbackAddress());
                assertTrue(LoopbackEndpoint.of("0:0:0:0:0:0:0:1", 1)
                        .address().isLoopbackAddress());
            }
        } catch (java.net.UnknownHostException ignored) {
            // IPv6 loopback is optional on the host.
        }

        // A connectable endpoint must have a bound port.
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteEndpoint(LoopbackEndpoint.bind(0)));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteServerOptions.builder().maxFrameBytes(RemoteProtocol.MIN_FRAME_BYTES - 1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> RemoteClientOptions.builder().maxFrameBytes(RemoteProtocol.MIN_FRAME_BYTES - 1)
                        .build());
    }

    @Test
    void endpointAndServerOptionsExposeNoCredentialSurface() {
        // The v2 endpoint carries an address and an optional session
        // identity only; there is no credential file, token or secret field.
        RemoteEndpoint endpoint = new RemoteEndpoint(
                new LoopbackEndpoint(InetAddress.getLoopbackAddress(), 1), UUID.randomUUID());
        assertEquals(2, RemoteEndpoint.class.getRecordComponents().length);
        assertTrue(endpoint.warning().contains("no authentication"));
        assertTrue(endpoint.display().contains(endpoint.address().toString()));
        assertTrue(endpoint.display().contains("no authentication"));
        assertFalse(endpoint.warning().contains("token"));
        assertFalse(endpoint.toString().contains("credential"));

        // Server options have no credential builder or accessor anymore.
        RemoteServerOptions options = RemoteServerOptions.builder().build();
        for (var method : RemoteServerOptions.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("credential"),
                    method.getName());
            assertFalse(method.getName().toLowerCase().contains("token"), method.getName());
        }
        assertEquals(RemoteProtocol.VERSION, 2);
    }
}
