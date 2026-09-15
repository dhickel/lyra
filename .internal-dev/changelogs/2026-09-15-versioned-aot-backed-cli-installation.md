# Versioned AOT-backed CLI installation

## Date

2026-09-15

## Git Commit

d8c1e299aae7d26d16706b62930c697b748f7c2c

## Change Summary

Changed Lyra to release version `0.1.0`, documented SemVer stepping for agents, and added a versioned CLI installer with a stable `lyra` launcher and locally generated Java 25 AOT cache. The installed no-argument command enters the REPL.

## Files

- Root and module Maven coordinates, runtime/compiler version metadata, packaging scripts, tests, fixtures, examples, and release-audit paths.
- `tools/install-lyra.sh`.
- `AGENTS.md`, `README.md`, backend/runtime decisions and specifications.
- Java 25 JMH evidence dependency-resolution fix in `tools/phase23-evidence.sh`.

## Behavioral Impact

- `lyra` installed by the new script launches the local REPL with no arguments.
- Explicit `repl`, `run`, `compile`, and `attach` commands remain available.
- Releases install under versioned directories with an atomic `current` pointer; the stable launcher is exposed in the selected bin directory.
- The installer trains an AOT cache for the exact installed CLI JAR and Java/OS/architecture fingerprint. Missing or incompatible caches fall back to normal startup.

## Specification Impact

Updated `backend-runtime.md` and `decisions.md` with SemVer, versioned installation, stable launcher, and optional Java 25 AOT-cache deployment decisions.

## Risks

The AOT cache is implementation- and environment-specific and is not portable across arbitrary Java builds, operating systems, architectures, or changed CLI JARs. Native Windows installation remains unvalidated. The full Phase 24 release audit was not completed because the owner requested that it be skipped; the first audit attempt exposed and the implementation fixed a JMH dependency-resolution issue caused by the move away from `SNAPSHOT` coordinates.

## Follow-up Items

- Run the full Phase 24 release audit before declaring a release gate complete.
- Add native Windows installer/launcher validation when a Windows environment is available.
