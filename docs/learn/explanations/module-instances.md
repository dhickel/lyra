# Module instances, pinning, and reload

A source file is a module. Its canonical identity comes from its source identity, while logical names map to files through configured roots or resolvers. Source files contain no module declaration.

Instantiating a root creates one instance of each reachable dependency. Functions are linked before eager values initialize. Module initializers then run in a deterministic dependency order and source order. A cyclic function dependency can be valid, but a cycle that requires eager values is rejected rather than exposing partial state.

A persistent session pins each successfully initialized imported module to an exact source revision and producer. A later import of that revision reuses the live instance. It does not reread the file or rerun its initializer.

Explicit `\reload MODULE` constructs a fresh REPL-owned reachable graph from current source. Publication changes which producer future imports select, but it does not rewrite history:

- old closures keep old captured bindings;
- existing selective imports keep their original producer;
- old dependency edges remain old;
- values already obtained are not retargeted.

Application-owned modules borrowed by an attached session are not reloadable by that session. Closing a local session or application root eventually retires its owned producers and invalidates live closures. Detached value snapshots remain display data.

There is no automatic source replay, file watcher, state migration, latest-version handle, or old-reference retargeting.

See [Use the local REPL](../how-to/use-local-repl.md) and the [REPL reference](../../repl.md).
