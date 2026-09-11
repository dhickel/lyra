# Java 25 Build Validation

## Topic

Selecting the available Java 25 installation for Maven validation.

## Source References

- Repository `AGENTS.md` Java 25 commands
- Local `java -version` and Maven validation on 2026-08-31
- Phase-14 Maven dependency validation on 2026-09-03

## Key Takeaways

- The documented `/home/hickelpickle/.jdks/openjdk-ea-25+36-3489` path is not present in this checkout environment.
- The default `java` executable is OpenJDK 25 and successfully runs the full Maven reactor.
- Setting `JAVA_HOME` to the missing documented path causes Maven to fail before compilation; leave it unset here unless a verified JDK home is supplied.
- `maven-dependency-plugin:3.7.0` and `3.8.1` dependency analysis cannot inspect Java 25 class files in this environment: they reject class-file major version 69. `dependency:tree` remains usable; report `dependency:analyze` as a tooling limitation rather than changing the Java 25 build target.

## Project Relevance

Validation agents should verify a documented JDK path before exporting it and may use the environment's default Java 25 installation when `java -version` confirms the required profile.

## Open Questions

- Whether the repository Java 25 command examples should be updated to a portable discovery-based form.

## Phase-15 Validation Corrections

- `ClassFile.of().verify(bytes)` uses the ambient class-resolution context. It can report a false failure for an otherwise loadable generated class when the bytes refer to sibling generated classes that are not on that context's class path. Validate complete artifacts with a custom loader that defines every generated class, and use an external `-Xverify:all` probe for JVM verification.
- Split nullable values are represented by independent presence and payload components. Every accessor consumes its receiver, so closure-state component loads must reload the closure-state receiver for each component rather than invoke both accessors on one receiver.

## Phase-16 Validation Corrections

- Local function declarations are signature-predeclared and may be referenced by an earlier eager expression. JVM emission must reserve the function identity slot, or mutable function cell, before evaluating that expression; creating the closure only at source-order declaration time leaves forward captures null.
- Generated function-array and tuple-field calls exercise the same authenticated closure path as direct function calls; test them through the generated facade rather than only checking class definition.
- A validated IR must not reach bytecode publication with initialization-cycle metadata, even if a producer-side cycle check was expected to reject it. The emitter now rejects both explicit initialization-plan cycles and retained eager-cycle witnesses before generating classes.

## Phase-22 Validation Corrections

- Do not run concurrent Maven `clean`/test commands for modules in the same reactor checkout. They share `target` directories and can race while deleting or compiling classes, producing misleading missing-class or clean failures. Run the required clean reactor validation serially.
