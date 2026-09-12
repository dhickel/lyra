# Phase 3 — Typed IR, JVM classes and artifacts

## Context

The existing ABI has primitives, ranges, arrays, tuples and generated functions;
nominal fields and constructors need exact typed emission and compatibility data.

## Goal

Execute source-defined structs/classes and expose them through deterministic typed
Java artifacts without an interpreter, source rewriting or universal object storage.

## In Scope

Closed IR definitions/operations; final nominal JVM classes; primitive storage;
constructor factories; current-slot calls and method replacement; equality and
cycle handling; ownership/source failures; metadata, loading, packaging and Java ABI.

## Out of Scope

Arbitrary Java constructors/objects/SAM callbacks, dynamic reflection dispatch,
user-defined equality, speculative optimization tiers and unsupported performance claims.

## Implementation Steps

1. Extend IR nodes, visitors, validators and explicit evaluation/failure edges.
2. Add compiler/runtime nominal schema identity and canonical contract validation;
   define exact descriptors/names and explicit metadata compatibility handling.
3. Emit final reference classes with private typed fields and owner-checked public
   accessors/factories. Preserve initialization-before-publication guarantees.
4. Store receiver-capturing function values directly in method slots. Calls select
   the slot before argument effects; replacement lambdas evaluate receiver once.
5. Emit structural struct equality with cycle-pair tracking and class identity.
   Preserve existing array/tuple/operator behavior and no user-code traversal.
6. Integrate artifact inventories, load/authentication, module ownership, source
   maps, classes/thin/bundled packaging and direct Java facade compilation.

## Validation

Source-to-JVM tests prove field mutation, nested aliases, constructor argument and
initializer order, initialization failure effects, old versus replacement methods,
forwarding callbacks, existing-bound-method assignment and external lexical privacy.
Verify wrong-thread/closed/foreign values, exact descriptors, repeated byte-identical
builds and `-Xverify:all`. Assert primitive fields are unboxed and unchanged slot
reads do not allocate bound wrappers. Do not claim measured speedups without benchmarks.

## Exit Criteria

All normative nominal source operations execute through the direct emitter; positive
tests cannot accept unsupported-emission diagnostics. Full suite and extended runtime
fuzz pass with reviewed committed metadata/docs/evidence. Sessions remain required.

## Current Progress

Runtime nominal identity/schema graphs and explicit schema-aware type/signature
parsing are implemented. Compiler/runtime JVM mapping parity covers nominal,
nilable nominal and array contracts. Deterministic nominal descriptors retain the
full declaration digest and reject a different physical declaration identity.
Artifact schema 2 now serializes and validates closed recursive nominal schemas,
binds their complete canonical contracts into artifact revisions, and resolves
nominal exports only after schema decoding. Schema-1 bytes remain unchanged.
The compiler schema projection is now wired into embedded facade metadata and
artifact assembly with schema-aware export parsing and revision binding.
Producer artifact keys now carry those contracts for generated callable signature
resolution, with per-producer lifecycle checks ahead of shared metadata caching.
The runtime now has a single-use receiver construction capability, field
initialization checks and nominal value/access authority boundaries. Tests use
exact typed Java fixtures; generated source object classes remain to be wired.
Generated plans now include final nominal classes, private exact typed fields,
initialization/access members, constructor-to-instance signatures and recursive
linkage inventories. ABI parity checks these against source schemas. Member bodies
and source construction remain to be emitted; these are still planning tests.
These are contract/mapping/metadata tests, not emitted object execution. Object
artifact publication, direct emission, authority wiring, equality,
Java factories and persistent sessions remain unfinished.
