# Nominal bytecode methods progress

## Date

2026-09-12

## Git Commit

`f1ccc989e1fd3bfd75121b47cb1fc9a15eb96ce8` (baseline).

## Change Summary

Emit real final JVM classes for source nominal declarations, including private exact
typed fields and initialization, generated and public access methods. Authenticate
nominal, callable and nested aggregate leaves at typed generated, facade and session
boundaries. Extend schema-2 facade metadata replacement and exact nominal runtime
type resolution.

## Files

JVM bytecode emitter and artifact assembly; nominal runtime construction/object and
authority helpers; module/session type linkage; focused runtime and source-to-JVM
tests; extended fuzz selector; backend specification, nominal knowledge/phase plan
and language coverage matrix.

## Behavioral Impact

Source nominal declarations now produce deterministic loadable representation
classes. Their accessors enforce initialization, visibility, mutability, owner
thread, producer lifecycle and exact nominal/callable provenance. The matching
construction ticket can store/read its own incomplete receiver without authorizing
other incomplete or foreign objects. Boundary checks preserve original references.

## Specification Impact

Records the implemented representation/method boundary and explicitly retains
Lyra-side factories, construction expressions, member operations, equality and
session persistence as completion gates.

## Validation

Focused nominal construction/bytecode tests, the complete `mvn -q test` suite,
extended `tools/fuzz-language.sh -q` campaign at 1,800 cases, and
`git diff --check` passed.

## Risks

The tests drive construction tickets from Java. They do not claim that `Name[args]`
or source field/method operations execute yet. Exact source factories and loader
inventory validation remain required before end-to-end nominal completion.

## Follow-up Items

Emit producer-bound module factories and `IrNode.Construction`, then source member
access/rebinding, method slots, equality and persistent-session behavior.
