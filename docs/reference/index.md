# Lyra reference

This reference describes Lyra 0.1.0 as implemented for language contract 2, runtime ABI 1.1, artifact schemas 1 and 2, and Java 25. It is organized for lookup. The [formal language specification](../specification/language.md) owns normative language semantics. These language pages summarize that contract, while product pages describe current compiler, runtime, CLI, REPL, and editor behavior without adding language rules.

## Language

- [Lexical structure](lexical-structure.md)
- [Literals and types](literals-and-types.md)
- [Bindings and functions](bindings-and-functions.md)
- [Control flow](control-flow.md)
- [Operators](operators.md)
- [Collections and strings](collections-and-strings.md)
- [Structs and classes](structs-and-classes.md)
- [Modules and I/O](modules-and-io.md)
- [Diagnostics and failures](diagnostics.md)
- [Deferred features and rejected spellings](deferred-features.md)

## Products and integration

- [CLI](cli.md)
- [Artifacts and Java API](artifacts-and-java.md)
- [REPL and application attachment](repl.md)
- [Editor](editor.md)

## Terms

| Term | Meaning |
| --- | --- |
| module instance | One initialized, owner-thread-confined instance of a compiled module and its reachable dependencies. |
| producer | The initialized module or session generation that owns a value, closure, type, or factory. |
| session generation | The classes, module state, and resources created for one attempted REPL submission. |
| snapshot | Immutable, bounded display data returned by the REPL. It is not a live Lyra value. |
| attachable artifact | An artifact compiled with the attachable profile and debug capability, so an explicitly enabled REPL can register its root. |
| trusted interface | An interface intended for owner-controlled development. It is not authenticated, sandboxed, or safe for hostile clients. |

## Version dimensions

These versions have different purposes:

| Dimension | Current value | Meaning |
| --- | --- | --- |
| Product SemVer | `0.1.0` | Maven coordinates, launchers, and distribution version. |
| Language contract | `2` | Accepted source syntax and semantics. Source has no edition directive. |
| Runtime ABI | `1.1` | Compatibility of generated classes with the runtime. A runtime accepts the same major and an equal or lower required minor. |
| Artifact schema | `1`, or `2` when nominal schemas are published | Metadata representation. |
| Debug map schema | `1` | Source map representation. |
| Java target | `25` | Compiler, runtime, and generated class-file profile. |

Metadata, tests, and implementation are evidence for this release. They do not turn compiler-private typed IR or provenance records into public APIs. See [language testing](../language-testing.md) for the maintained evidence matrix.
