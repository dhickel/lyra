package io.mindspice.lyra.compiler.artifact;

import io.mindspice.lyra.runtime.RuntimeAbi;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Fixed production-code-source collection for bundled artifacts.
 *
 * <p>Ordinary bundles contain exactly the runtime module classes.  Debug
 * bundles additionally contain the known compiler and REPL production
 * closure, discovered from the fixed code sources of known production
 * classes.  Discovery never scans arbitrary class-loader or Surefire
 * resources: each anchor class contributes only the class files under its
 * own package prefix, so CLI, JLine, test, and credential material can never
 * enter a bundle.  A missing or conflicting closure inventory is an
 * actionable packaging error, not an incomplete bundle.</p>
 */
final class BundledRuntime {
    private static final String RUNTIME_PREFIX = "io/mindspice/lyra/runtime/";
    private static final String COMPILER_PREFIX = "io/mindspice/lyra/compiler/";
    private static final String REPL_PREFIX = "io/mindspice/lyra/repl/";
    private static final String REQUIRED_LAUNCHER = RUNTIME_PREFIX + "LyraLauncher.class";
    /** Fixed discovery anchor; lyra-compiler has no static REPL dependency. */
    private static final String REPL_ANCHOR_CLASS = "io.mindspice.lyra.repl.ReplLauncher";

    private BundledRuntime() {
    }

    static Map<String, byte[]> collect(Set<String> generatedBinaryNames) {
        return collect(generatedBinaryNames, false);
    }

    static Map<String, byte[]> collect(Set<String> generatedBinaryNames, boolean debugClosure) {
        Objects.requireNonNull(generatedBinaryNames, "generatedBinaryNames");
        Map<String, byte[]> result = new TreeMap<>(EntryNames.utf8Comparator());
        try {
            collectPrefix(RuntimeAbi.class, RUNTIME_PREFIX, "lyra-runtime", result);
            if (debugClosure) {
                collectPrefix(io.mindspice.lyra.compiler.api.LyraCompiler.class,
                        COMPILER_PREFIX, "lyra-compiler", result);
                Class<?> replAnchor = loadReplAnchor();
                collectPrefix(replAnchor, REPL_PREFIX, "lyra-repl", result);
            }
        } catch (ArtifactAssemblyException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ArtifactAssemblyException("cannot locate the Lyra production class files", exception);
        }
        if (result.isEmpty()) {
            throw new ArtifactAssemblyException("bundled packaging cannot locate Lyra production class files");
        }
        if (!result.containsKey(REQUIRED_LAUNCHER)) {
            throw new ArtifactAssemblyException(
                    "bundled packaging requires the unavailable runtime launcher: "
                            + REQUIRED_LAUNCHER);
        }
        for (String generated : generatedBinaryNames) {
            String entry = EntryNames.classEntry(generated);
            if (result.containsKey(entry)
                    || debugClosure && (generated.startsWith("io.mindspice.lyra.compiler.")
                    || generated.startsWith("io.mindspice.lyra.repl."))) {
                throw new ArtifactAssemblyException(
                        "generated class collides with bundled production closure: " + generated);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Fixed optional discovery: the REPL closure is required for debug bundles. */
    private static Class<?> loadReplAnchor() {
        try {
            return Class.forName(REPL_ANCHOR_CLASS);
        } catch (ClassNotFoundException failure) {
            throw new ArtifactAssemblyException(
                    "debug bundled packaging requires the lyra-repl production closure: "
                            + REPL_ANCHOR_CLASS + " is not loadable from the packaging classpath "
                            + "(run debug bundling from the CLI distribution, which carries "
                            + "lyra-repl, or add lyra-repl to the packaging classpath)", failure);
        }
    }

    /**
     * Collects class files under one fixed package prefix from the anchor
     * class's code source.  The same jar/directory may host several modules
     * (for example the shaded CLI distribution); prefix filtering keeps each
     * inventory disjoint and excludes everything outside the declared
     * production namespaces.
     */
    private static void collectPrefix(Class<?> anchor, String prefix, String module,
                                      Map<String, byte[]> result) throws IOException {
        URL codeSource = anchor.getProtectionDomain().getCodeSource() == null
                ? null : anchor.getProtectionDomain().getCodeSource().getLocation();
        if (codeSource == null) {
            throw new ArtifactAssemblyException(module + " has no production code source");
        }
        int before = result.size();
        readCodeSource(codeSource, prefix, result);
        if (result.size() == before) {
            throw new ArtifactAssemblyException(
                    "cannot locate the " + module + " production class files");
        }
    }

    private static void readCodeSource(URL codeSource, String prefix,
                                       Map<String, byte[]> result) throws IOException {
        if (codeSource.getProtocol().equals("file")) {
            try {
                Path path = Path.of(codeSource.toURI());
                if (Files.isDirectory(path)) {
                    Path packageRoot = path.resolve(prefix);
                    if (Files.isDirectory(packageRoot)) {
                        readDirectory(packageRoot, prefix, result);
                    }
                } else if (path.getFileName().toString().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(path.toFile())) {
                        readJar(jar, prefix, result);
                    }
                }
            } catch (Exception exception) {
                throw new IOException("invalid production code source", exception);
            }
        }
    }

    private static void readDirectory(Path root, String prefix, Map<String, byte[]> result)
            throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        try {
                            String relative = root.relativize(path).toString().replace('\\', '/');
                            put(result, prefix + relative, Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new UncheckedIoException(exception);
                        }
                    });
        } catch (UncheckedIoException exception) {
            throw exception.exception;
        }
    }

    private static void readJar(JarFile jar, String prefix, Map<String, byte[]> result)
            throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".class")) {
                try (var input = jar.getInputStream(entry)) {
                    put(result, name, input.readAllBytes());
                }
            }
        }
    }

    private static void put(Map<String, byte[]> result, String name, byte[] bytes) {
        EntryNames.require(name);
        byte[] previous = result.putIfAbsent(name, bytes.clone());
        if (previous != null && !Arrays.equals(previous, bytes)) {
            throw new ArtifactAssemblyException(
                    "conflicting production closure inventory: duplicate entry " + name
                            + " has different bytes");
        }
    }

    private static final class UncheckedIoException extends RuntimeException {
        private final IOException exception;

        private UncheckedIoException(IOException exception) {
            this.exception = exception;
        }
    }
}
