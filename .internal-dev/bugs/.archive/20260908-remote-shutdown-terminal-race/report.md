# Graceful remote shutdown terminal-frame race

## Summary

Server-wide graceful shutdown could race ordinary connection-handler cleanup and drop a queued request's terminal `CANCELLED` frame, leaving the client with `DISCONNECTED`.

## Scope

Credential-free remote-v2 shutdown and activation lifecycle. Request execution, mutation state and root ownership were unaffected; terminal delivery was unreliable in one close race.

## Reproduction

Run `ReplActivationJavaHostTest.queuedControlWorkIsRetiredAtShutdownWithoutClosingTheRoot` repeatedly. The child queues a real request on the root controller without polling, then closes activation. Before repair, a final full-suite run observed `DISCONNECTED` instead of `CANCELLED`.

## Expected

Service close retires queued work as `CANCELLED`, drains the terminal frame within the bounded window, closes control resources, and leaves the externally owned root open.

## Actual

After the server-wide closed flag became visible, the connection handler could exit its read loop and invoke ordinary `closeConnection`. That path closed the socket and cleared the outbound queue while `RemoteServer.close` was enqueueing or draining the terminal frame.

## Evidence

The failure occurred during Java 25 `mvn -q test` on baseline `9ee3f02`. After repair, the exact forked method passed five consecutive Maven runs; final `mvn -q test` and `mvn -q clean verify` passed 980 tests with zero failures, errors or skips.

## Impact

Reconnect/status could truthfully report a disconnect but the controller lost the stronger terminal result promised for orderly server shutdown. This violated the Phase 12/13 shutdown contract and made lifecycle evidence flaky.

## Status

Resolved in the Phase 13 worktree. Server-wide close now owns bounded terminal drain, transport closure and connection-slot retirement for its snapshot; handlers perform ordinary disconnect cleanup only when the server itself remains open. Mirrored and closed as https://github.com/dhickel/lyra/issues/4.

## Next Action

None. Preserve the repeated forked race and the separation between graceful server shutdown and ordinary peer-disconnect cleanup.
