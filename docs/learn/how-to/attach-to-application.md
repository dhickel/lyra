# Attach to a running application

## Security warning

An enabled Lyra attachment listener is **unauthenticated, loopback-only, and trusted**. It has no password, token, credential file, encryption, or hostile-client isolation. Any local process that can reach it can execute Lyra source with the application's authority. Enable it only on a machine and account you trust. It is not a sandbox.

## Prerequisites

The application root must export exactly `main :Fn<Array<String>;I32>`. Only an explicitly compiled REPL-capable root can be attached.

## Attach to `lyra run`

Terminal A:

```sh
lyra run app.lyra --repl --repl-port 0 --repl-wait -- arg1
```

The host prints the actual loopback endpoint and the authority warning. Port `0` requests an ephemeral port. `--repl-wait` pauses after root initialization and registration but before `main` until a protocol-v2 controller completes its handshake.

Terminal B:

```sh
lyra attach 127.0.0.1:PORT
```

Use the printed port. The attached workspace sees public top-level root exports. Ordinary typing, visibility, mutability, and imported-module ownership rules still apply. A public `@mut` scalar can be assigned with normal Lyra syntax:

```lyra
count := 42
```

Enter `\quit` to detach. Detaching does not terminate the application.

## Activate a compiled artifact

Compile capability without listening:

```sh
lyra compile app.lyra --repl --format bundled-jar --output app-debug.jar
```

`compile --repl` never starts a listener. Run the artifact with explicit properties:

```sh
java -Dlyra.repl.enabled=true \
  -Dlyra.repl.port=0 \
  -Dlyra.repl.wait=true \
  -jar app-debug.jar arg1
```

Without `-Dlyra.repl.enabled=true`, no listener starts. The optional port and wait properties have no effect unless attachment is enabled.

## Runtime boundaries

- Live work runs on the application's owner thread at generated safe points or explicit host polling.
- At most one controller and one active evaluation are admitted.
- Cancellation is identity-specific and cooperative. Blocking host I/O can delay it. It never terminates `main`.
- Application stdin, stdout, and stderr stay with the host. They are not forwarded through the attachment socket.
- Attached `\load`, `\reload`, and filesystem completion use the application's filesystem.
- There is no arbitrary JVM or process-ID attachment, source replay, automatic reload, state migration, or old-reference retargeting.

For an explicit Java host, use the current `ApplicationAttachment.open(root, context, SessionOptions.defaults())` form demonstrated in [`examples/repl/HostExample.java`](../../../examples/repl/HostExample.java). Open, poll, and close the attachment on the root owner thread. Close the attachment before its caller-owned root.
