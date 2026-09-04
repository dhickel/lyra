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

/** Locates exactly the runtime module's class files for bundled artifacts. */
final class BundledRuntime {
    private static final String PREFIX = "io/mindspice/lyra/runtime/";
    private static final String REQUIRED_LAUNCHER = PREFIX + "LyraLauncher.class";

    private BundledRuntime() {
    }

    static Map<String, byte[]> collect(Set<String> generatedBinaryNames) {
        Objects.requireNonNull(generatedBinaryNames, "generatedBinaryNames");
        Map<String, byte[]> result = new TreeMap<>(EntryNames.utf8Comparator());
        try {
            // Use the code source of a production runtime class rather than
            // every class-loader resource.  The latter also exposes test
            // output directories under Surefire and would leak test/JUnit
            // classes into a bundled artifact.
            URL codeSource = RuntimeAbi.class.getProtectionDomain().getCodeSource() == null
                    ? null : RuntimeAbi.class.getProtectionDomain().getCodeSource().getLocation();
            if (codeSource == null) {
                throw new ArtifactAssemblyException("lyra-runtime has no production code source");
            }
            readCodeSource(codeSource, result);
        } catch (ArtifactAssemblyException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ArtifactAssemblyException("cannot locate the lyra-runtime class files", exception);
        }
        if (result.isEmpty()) {
            throw new ArtifactAssemblyException("bundled packaging cannot locate lyra-runtime class files");
        }
        if (!result.containsKey(REQUIRED_LAUNCHER)) {
            throw new ArtifactAssemblyException(
                    "bundled packaging requires the unavailable runtime launcher: "
                            + REQUIRED_LAUNCHER);
        }
        for (String generated : generatedBinaryNames) {
            String entry = EntryNames.classEntry(generated);
            if (result.containsKey(entry)) {
                throw new ArtifactAssemblyException("generated class collides with bundled runtime: " + generated);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void readCodeSource(URL codeSource, Map<String, byte[]> result) throws IOException {
        if (codeSource.getProtocol().equals("file")) {
            try {
                Path path = Path.of(codeSource.toURI());
                if (Files.isDirectory(path)) {
                    Path runtime = path.resolve(PREFIX);
                    if (Files.isDirectory(runtime)) {
                        readDirectory(runtime, result);
                    }
                } else if (path.getFileName().toString().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(path.toFile())) {
                        readJar(jar, result);
                    }
                }
            } catch (Exception exception) {
                throw new IOException("invalid runtime code source", exception);
            }
        }
    }

    private static void readDirectory(Path root, Map<String, byte[]> result) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        try {
                            String relative = root.relativize(path).toString().replace('\\', '/');
                            put(result, PREFIX + relative, Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new UncheckedIoException(exception);
                        }
                    });
        } catch (UncheckedIoException exception) {
            throw exception.exception;
        }
    }

    private static void readJar(JarFile jar, Map<String, byte[]> result) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(PREFIX) && name.endsWith(".class")) {
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
            throw new ArtifactAssemblyException("duplicate runtime class has different bytes: " + name);
        }
    }

    private static final class UncheckedIoException extends RuntimeException {
        private final IOException exception;

        private UncheckedIoException(IOException exception) {
            this.exception = exception;
        }
    }
}
