package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Git output capture without a pipe whose EOF can be held open by descendants. */
public final class GitCommandOutput {
    private GitCommandOutput() {}

    public static List<String> readLines(ProcessBuilder builder, long lifetimeMs)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("kompile-index-git-", ".log");
        try {
            builder.redirectOutput(output.toFile());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectErrorStream(false);
            return awaitOutput(builder.start(), output, lifetimeMs);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    static List<String> awaitOutput(Process process, Path output, long lifetimeMs)
            throws IOException, InterruptedException {
        try {
            if (!process.waitFor(lifetimeMs, TimeUnit.MILLISECONDS))
                throw new IOException("Git collection exceeded its lifetime");
            if (process.exitValue() != 0) return List.of();
            // Read the completed file snapshot, not a potentially still-growing
            // stream inherited by a descendant. Bound both completion and memory.
            long size = Files.size(output);
            if (size > 32 * 1024 * 1024) throw new IOException("Git output exceeds 32 MiB");
            try (var input = Files.newInputStream(output)) {
                return new String(input.readNBytes((int) size), StandardCharsets.UTF_8).lines().toList();
            }
        } finally {
            // Snapshot descendants before destroying their parent. No stream draining or
            // unbounded wait here: inherited descriptors cannot hold a collector thread.
            try {
                process.descendants().forEach(child -> {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) {}
                });
            } catch (RuntimeException ignored) {}
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
