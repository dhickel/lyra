# Nominal session linkage

## Topic

Persistent nominal types, objects, methods and constructors across compiler/runtime
session generations.

## Source References

- `.internal-dev/specifications/repl.md`
- `.internal-dev/specifications/backend-runtime.md`
- `lyra-compiler/src/main/java/io/mindspice/lyra/compiler/api/SessionFlowCertificate.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionStorageDomain.java`
- `lyra-runtime/src/main/java/io/mindspice/lyra/runtime/SessionTypeLoader.java`
- `lyra-repl/src/test/java/io/mindspice/lyra/repl/NominalSessionTest.java`

## Key Takeaways

- Canonical nominal spellings cannot be parsed without the exact artifact schema
  environment. Defer requirement parsing until producer/artifact selection; never
  interpret an unknown nominal hash as a new type.
- Same binary names in sibling generation loaders are different JVM classes. Exact
  identity-named nominal representation classes must use the session/root structural
  parent domain, while module state, cells and closures remain generation-local.
- A retained schema is not executable constructor authority. Persist a typed
  MethodHandle bound to the original initialized module state, authenticate it in the
  immutable link table and cache it outside the construction hot path.
- Retained heap proofs must traverse nominal fields. Otherwise a replaced method can
  survive at runtime while its nested lambda identity is lost by the next compiler
  certificate.
- The standalone class-file verifier cannot resolve self-typed nominal descriptors
  before their staged loader owns the class. Validate the generic inventory eagerly
  and rely on JVM definition verification when the staged class is actually loaded.
- Snapshot inspection uses generated public field accessors only. It never calls
  source methods or `toString`, and it omits private class members.

## Project Relevance

These rules preserve exact type identity, lexical privacy, method receiver capture
and constructor behavior across standalone and attached REPL submissions without
source replay or map-shaped object representations.

## Open Questions

Graphical editor qualification and native-platform release evidence remain governed
by their existing release gates; they do not change nominal runtime semantics.
