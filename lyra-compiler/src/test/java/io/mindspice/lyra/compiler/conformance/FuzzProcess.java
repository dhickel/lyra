package io.mindspice.lyra.compiler.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Hard process deadlines contain non-cooperative compiler hangs, stack overflows, and heap exhaustion. */
final class FuzzProcess {
    record Result(int exitCode, boolean timedOut, String output) {
        boolean passed() { return !timedOut && exitCode == 0; }
        String fingerprint() {
            if (timedOut) return "TIMEOUT";
            return output.lines().filter(line -> line.startsWith("FUZZ_FAILURE=")).findFirst().orElse("EXIT=" + exitCode);
        }
    }

    static Result run(List<String> arguments, Path directory, Duration caseLimit, Duration campaignLimit)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "--enable-preview", "-Xverify:all", "-Xmx384m",
                "-XX:MaxMetaspaceSize=192m", "-cp", classpath, LanguageFuzzWorker.class.getName()));
        command.addAll(arguments);
        Path log = directory.resolve("worker.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long start = System.nanoTime(), lastProgress = start, lastStamp = -1;
        boolean timeout = false;
        try {
            while (!process.waitFor(Duration.ofMillis(100))) {
                Path checkpoint = directory.resolve("current.properties");
                long stamp = Files.exists(checkpoint) ? Files.getLastModifiedTime(checkpoint).toMillis() : -1;
                if (stamp != lastStamp) { lastProgress = System.nanoTime(); lastStamp = stamp; }
                long now = System.nanoTime();
                if (now - lastProgress > caseLimit.toNanos() || now - start > campaignLimit.toNanos()) {
                    timeout = true;
                    break;
                }
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(Duration.ofSeconds(5))) throw new IOException("Fuzz worker did not terminate");
            }
        }
        return new Result(process.exitValue(), timeout, Files.readString(log));
    }

    /** Bounded delta debugging accepts only the same failure fingerprint, never a new rejection. */
    static Path minimize(Path original, Result failure, int attempts) throws Exception {
        return minimize(original, failure, attempts, candidate -> run(List.of("--replay", candidate.toString()),
                candidate.getParent(), Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    @FunctionalInterface interface Replay { Result run(Path candidate) throws Exception; }

    static Path minimize(Path original, Result failure, int attempts, Replay replay) throws Exception {
        FuzzCase best = FuzzCase.read(original);
        if (!List.of("mutation", "grammar", "bytes").contains(best.get("mode")) || attempts == 0) return original;
        Path directory = original.getParent().resolve("minimized");
        String mode = best.get("mode");
        byte[] bytes = mode.equals("bytes") ? java.util.Base64.getDecoder().decode(best.get("bytes")) : null;
        String source = best.get("source");
        int length = bytes == null ? source.codePointCount(0, source.length()) : bytes.length;
        for (int partitions = 2, used = 0; length > 0 && used < attempts; partitions = Math.min(length, partitions * 2)) {
            int chunk = Math.max(1, (length + partitions - 1) / partitions);
            boolean reduced = false;
            for (int offset = 0; offset < length && used < attempts; offset += chunk) {
                used++;
                int end = Math.min(length, offset + chunk);
                FuzzCase candidate = FuzzCase.read(original);
                String nextSource = source;
                byte[] nextBytes = bytes;
                if (bytes == null) {
                    int startChar = source.offsetByCodePoints(0, offset), endChar = source.offsetByCodePoints(0, end);
                    nextSource = source.substring(0, startChar) + source.substring(endChar);
                    candidate.put("source", nextSource);
                } else {
                    nextBytes = new byte[bytes.length - (end - offset)];
                    System.arraycopy(bytes, 0, nextBytes, 0, offset);
                    System.arraycopy(bytes, end, nextBytes, offset, bytes.length - end);
                    candidate.put("bytes", java.util.Base64.getEncoder().encodeToString(nextBytes));
                }
                candidate.save(directory);
                Result result = replay.run(directory.resolve("current.properties"));
                if (!result.passed() && result.fingerprint().equals(failure.fingerprint())) {
                    best = candidate; source = nextSource; bytes = nextBytes; length -= end - offset;
                    reduced = true;
                    break;
                }
            }
            if (reduced) partitions = 1;
            else if (chunk == 1) break;
        }
        best.save(directory);
        return directory.resolve("current.properties");
    }
}
