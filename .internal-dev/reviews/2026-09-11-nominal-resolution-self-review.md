# Nominal declaration/resolution self-review

## Scope

Self-review of the source-issued nominal type/schema and initial resolver changes
against baseline 8aaf3dc4df8d51cf99810c45217b74de932d1d05. This is not independent
review or full feature qualification.

## Findings

- Exact identity is separate from recursive schema shape. Closed schemas reject
  unknown references and function-containing struct data; class references stop
  the data-only traversal at the reference boundary.
- Reciprocal member/self/constructor declaration inventories and member lambda
  ownership are validated before resolution publishes its immutable graph.
- Self is captured as an immutable receiver reference; assignment authority grants
  member mutation without granting self rebinding.
- A tuple-write regression found by conformance and fuzz was corrected without
  changing its existing TYPE012 negative oracle. The failing seed and minimized
  source are recorded in the changelog and nominal knowledge.
- Namespace-first collection changed graph-local allocation order. The callable
  summary test helper now selects the original LET rather than whichever same-name
  declaration (including an import alias) has the first graph-local ID. No callable
  effect assertions were removed or weakened.
- This slice does not certify initialization, contextual replacements or full
  field/callable flow. Typed construction, JVM and session support are still required.

## Risk Assessment

The tested resolver subset is not executable support. New nominal contracts must
not enter packaging/session boundaries until those boundaries authenticate schemas
and producers and direct JVM execution tests pass.

## Recommendations

Continue immediately with typed nominal operations and initialization proof, then
field-sensitive flow/IR/JVM/runtime/session integration. Preserve all source-negative
and malformed-publication invariants while extending their closed inventories.

## Follow-ups

Active nominal plan remains open. Full finalization, source-to-JVM conformance and
persistent-session evidence remain the goal; this checkpoint is not a stopping point.
