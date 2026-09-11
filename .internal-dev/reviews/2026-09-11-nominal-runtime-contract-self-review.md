# Nominal runtime contract self-review

## Scope

Runtime nominal identity/schema construction, schema-aware canonical parsing and
physical JVM descriptor mapping. Local self-review only; no emitted-object or
independent release review is claimed.

## Findings

- Runtime identities retain origin module/revision/name/occurrence, not field shape
  or a compilation-local ordinal. A fixed digest fixture checks the versioned
  length-prefixed identity input independently of compiler/runtime parity.
- Closed environments reject missing and duplicate schemas before use. Recursive
  data validation terminates by nominal identity and stops at class references,
  while traversing nested structs/arrays/tuples to reject functions as struct data.
- Default parsing still has no nominal names. Explicit environment parsing checks
  the exact digest grammar and resolves it only in the supplied immutable schema
  graph. Noncanonical qualifiers, extra text and unknown hashes reject.
- JVM physical plans use a distinct nominal reference family and check the full
  declaration digest against the descriptor; arrays recurse into this check.
  Compiler/runtime nilable and ordinary reference mappings agree.
- Existing nonnominal parsing uses one immutable empty environment, avoiding an
  environment allocation for each scalar/structural parse.

## Risk Assessment

Contracts and descriptor plans do not authenticate or emit live objects. Artifact
schema encoding, compatible readers, generated object plans, lifecycle authority,
equality and persistent loading remain required. This unit must not be described
as nominal runtime execution support.

## Recommendations

Preserve nonnominal artifact encodings while explicitly versioning nominal schema
publication. Parse recursive metadata in two passes inside a scoped schema graph,
never through global registration or unknown-name fallbacks. Validate object class
inventories against the exact published schemas before activation.

## Follow-ups

Extended fuzz and final ordinary reactor validation passed, including the new
seeded runtime contracts and final negative/hash assertions. Diff checks passed.
Commit validated work and continue with versioned metadata and direct object emission.
