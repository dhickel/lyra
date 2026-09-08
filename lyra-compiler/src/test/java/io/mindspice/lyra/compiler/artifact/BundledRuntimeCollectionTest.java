package io.mindspice.lyra.compiler.artifact;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed production-code-source collection contract: ordinary bundles are
 * runtime-only, debug closures are fixed and prefix-scoped, and conflicting
 * or missing inventories are actionable packaging errors.
 */
final class BundledRuntimeCollectionTest {
    @Test
    void ordinaryCollectionIsRuntimeOnlyAndComplete() {
        Map<String, byte[]> entries = BundledRuntime.collect(Set.of());
        assertTrue(entries.containsKey("io/mindspice/lyra/runtime/LyraLauncher.class"));
        assertTrue(entries.size() > 20);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            assertTrue(entry.getKey().startsWith("io/mindspice/lyra/runtime/"),
                    "unexpected entry: " + entry.getKey());
            assertTrue(entry.getKey().endsWith(".class"), "non-class entry: " + entry.getKey());
            assertTrue(entry.getValue().length > 8);
        }
        assertFalse(entries.keySet().stream().anyMatch(name ->
                name.startsWith("io/mindspice/lyra/compiler/")
                        || name.startsWith("io/mindspice/lyra/repl/")
                        || name.startsWith("io/mindspice/lyra/cli/")
                        || name.startsWith("org/jline/")
                        || name.startsWith("org/junit/")));
    }

    @Test
    void generatedClassesMayNeverCollideWithTheClosure() {
        ArtifactAssemblyException failure = assertThrows(ArtifactAssemblyException.class,
                () -> BundledRuntime.collect(Set.of("io.mindspice.lyra.runtime.LyraLauncher")));
        assertTrue(failure.getMessage().contains("collides"), failure.getMessage());
    }

    @Test
    void debugCollectionWithoutReplClosureIsAnActionableError() {
        // The compiler-only test classpath carries no lyra-repl distribution;
        // full debug bundling executes in the CLI distribution where the
        // fixed REPL anchor class is loadable.
        ArtifactAssemblyException failure = assertThrows(ArtifactAssemblyException.class,
                () -> BundledRuntime.collect(Set.of(), true));
        assertTrue(failure.getMessage().contains("lyra-repl production closure"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("CLI distribution"), failure.getMessage());
    }
}
