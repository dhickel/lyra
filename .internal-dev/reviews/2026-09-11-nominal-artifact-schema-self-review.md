# Nominal artifact schema self-review

## Scope

Schema-2 nominal metadata encoding/decoding, export resolution, artifact revisions
and compatibility with schema-1 normal/debug publications. Local self-review only.

## Findings

- Nonempty nominal environments require schema 2; empty environments require
  schema 1. A schema/version mismatch is rejected rather than silently upgraded.
- Identity entries are validated and canonically ordered before recursive member
  and constructor contracts are decoded. Export parsing receives only the completed
  closed environment. No process-global registration is used.
- The nominal section is included in a domain-separated revision extension. Both
  the immutable metadata constructor and the reader's compatibility gate recompute
  the extension. The empty environment returns the existing revision unchanged.
- Tests mutate valid field names, visibility, mutability and declaration kinds as
  well as malformed hashes/types/versions; unchanged revisions must reject them.
  Independent seeded layout models verify decoded ordered fields/parameters.
- The generated facade metadata gate accepts the explicitly supported schema
  versions and still requires exact equality with the validated artifact metadata.

## Risk Assessment

No generated nominal objects are emitted or authenticated by this unit. Exact
metadata does not grant access to a live producer, and nominal artifact publication
through the compiler plus loader/session verification remains unfinished. Existing
source proof and runtime lifecycle checks must not be bypassed during that work.

## Recommendations

Transport the exact closed schema environment through compiler emission and artifact
assembly. Bind it into every artifact revision calculation and schema-aware parse
site. Add independent generated-class/member inventory and producer-authority
checks before claiming executable or persistent nominal support.

## Follow-ups

Extended fuzz and final ordinary reactor validation passed, including the final
valid-kind and unresolved-member-reference negatives. Diff checks passed. Commit
validated work and continue with compiler publication and direct object emission.
