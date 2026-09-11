# Nominal typed initialization self-review

## Scope

Self-review against baseline 876c4775b6a3f217b2eacf524cf5fa01a5318052. This is not an
independent audit and does not close the nominal implementation plan.

## Findings

- Initialization proof issuance is private and bound to exact child identities;
  nominal IR validation checks schema, receiver/member/constructor identities,
  completion roles and source-child order. Deferred defaults are not module effects.
- A constructor may occur textually before fields. Its execution still follows
  default initialization; the IR check uses certified child-role order instead of
  requiring monotonically increasing source offsets. A positive class test places
  the constructor before method defaults to preserve this regression.
- Immutable self capture is distinct from mutable object storage. Field writes use
  aggregate-capture effect transfer, not a fictitious rebindable self cell.
- The initial heap bridge preserves alias-visible slot replacement and selected
  callable identity. It is not a runtime map representation or universal callable
  fallback. Ordinary callable construction transfer remains explicitly unfinished.
- Constructor branch checks use an independent bit-set oracle. Negative tests
  exercise reads, repeated immutable writes, closure publication and callbacks.
- Public-API empty-class rejection was stale after nominal syntax acceptance.
  Its replacement checks an uninitialized class, not unsupported-emission success.
- Object heap entries require exact field types. Reference ownership requires the
  allocation site; nominal projection steps do not overlap tuples or array wildcards.
- Nominal canonical type hashing was repeated on each spelling request; caching
  removes that work while preserving exact declaration-based equality.

## Risk Assessment

The accepted feature remains incomplete. Contextual replacement self, general
constructor summaries, cycle/import/retention ownership, repeated and nested writes,
complete malformed metadata coverage and every executable backend/session gate are
still required. None may be silently converted to unknown-callable or dynamic lookup
fallbacks. Initial compiler-only heap facts do not authenticate runtime objects.

## Recommendations

Keep the goal active and continue immediately through the remaining semantic,
direct JVM, runtime authority, artifact and retained-session implementation. Retain
all core conformance and closed-variant guards. Add executed nominal fixtures only
when they genuinely load and run successfully.

## Follow-ups

Record final full reactor and extended campaign evidence in the corresponding
changelog before checkpointing. A checkpoint is not finalization or a stopping point.
