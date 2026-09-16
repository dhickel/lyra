# CLI installation and Java 25 AOT cache

## Topic

Versioned Lyra CLI installation, stable launcher upgrades, and Java 25 AOT startup-cache handling.

## Source References

- `tools/install-lyra.sh`
- `lyra-cli/src/main/scripts/lyra`
- `README.md` installation section
- `AGENTS.md` versioning and release-update policy
- `tools/phase23-evidence.sh`
- OpenJDK JEP 483, JEP 514, and JEP 515

## Key Takeaways

- Lyra release coordinates use SemVer; the current release is `0.1.1`. Installed releases use immutable version directories and a stable `current` pointer rather than replacing the active JAR in place.
- The stable launcher is exposed through `~/.local/bin/lyra` by default, while release contents live under `~/.local/share/lyra/versions/<version>` (or explicit `--prefix`/`--bin-dir` locations).
- Replacing `current` with ordinary `mv -f temporary-link current` is incorrect when `current` is a symlink to a directory: `mv` may follow it and place the temporary link inside the old release. Use no-target-directory (`mv -T`) or the BSD no-dereference equivalent (`mv -h`) so the symlink itself is atomically replaced, and verify the resolved launcher version after installation.
- The installer generates an AOT cache during installation by training a representative plain REPL launch. The cache is stored inside the exact release and keyed by the CLI JAR digest, Java runtime fingerprint, operating system, and architecture.
- Launching with an absent or incompatible cache must fall back to ordinary Java startup. `-XX:AOTMode=on` is useful for validation, not for the normal installed wrapper.
- Java 25 AOT caches are not portable across arbitrary JDK builds, operating systems, architectures, or changed CLI class paths. A new SemVer release receives a distinct cache identity.
- After changing a Maven project from a `SNAPSHOT` version to a release version, standalone Maven dependency goals may try Central/local-repository resolution even after a reactor build. `tools/phase23-evidence.sh` installs the reactor runtime prerequisite before resolving its JMH dependency class path, while benchmark launch classes still use reactor `target/classes` directly.

## Project Relevance

The installation workflow provides `lyra` as the no-argument REPL entry point while preserving explicit `repl`, `run`, `compile`, and `attach` commands. Versioned release directories make upgrades and rollback safe and prevent stale AOT cache reuse. The AOT cache improves JVM class loading/linking startup but does not remove source compilation work performed by `lyra run` or REPL submissions.

## Open Questions

- A future release may add an explicit cache-regeneration command for a newly installed JDK without reinstalling Lyra.
- Native Windows installation and launcher execution still require a Windows validation environment.
