package ai.kompile.embedding.anserini.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-contained crash reproduction for the crawl-path SIGABRT (exit 134):
 * drives {@link EmbeddingSubprocessMain} directly over stdin/stdout exactly the
 * way the crawl does — LOAD_MODEL_REQUEST followed by concurrent
 * EMBED_BATCH_REQUESTs — with NO launcher, NO crawl, NO LLM in the loop.
 *
 * Runs the model bge-base-en-v1.5 from ~/.kompile/models (present on dev hosts).
 * If the native layer aborts, the process exit code is >= 128 and the test fails
 * with the captured stderr tail instead of dying silently inside a crawl.
 */
class EmbeddingSubprocessEndToEndIT {

    private static final String MODEL_ID = "bge-base-en-v1.5";

    @Test
    @Timeout(value = 300)
    void loadsModelAndEmbedsBatchesWithoutAborting() throws Exception {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-Xmx4g", "-cp", classpath,
                EmbeddingSubprocessMain.class.getName());
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        ObjectMapper mapper = new ObjectMapper();
        List<String> stderrTail = new ArrayList<>();
        List<String> responses = new ArrayList<>();

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (responses) {
                        responses.add(line);
                        if (stderrTail.size() >= 40) {
                            stderrTail.remove(0);
                        }
                        stderrTail.add(line);
                    }
                }
            } catch (Exception ignored) {
                // process died — handled below
            }
        }, "it-reader");
        reader.setDaemon(true);
        reader.start();

        try (OutputStreamWriter w = new OutputStreamWriter(
                proc.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(mapper.writeValueAsString(java.util.Map.of(
                    "type", "LOAD_MODEL_REQUEST",
                    "requestId", "r1",
                    "modelId", MODEL_ID,
                    "optimalBatchSize", 16,
                    "maxBatchSize", 32,
                    "absoluteMaxBatchSize", 64,
                    "modelConfig", java.util.Map.of())));
            w.write("\n");
            w.flush();

            assertTrue(awaitCondition(responses, s -> s.contains("LOAD_MODEL_RESPONSE"),
                    180, TimeUnit.SECONDS),
                    "no LOAD_MODEL_RESPONSE within 180s. Tail:\n" + tail(stderrTail));

            List<String> texts = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                texts.add("Meridian Dynamics was founded by Elena Vasquez in Portland. "
                        + "Batch " + i + " of the crash reproduction.");
            }
            for (int b = 0; b < 19; b++) {
                w.write(mapper.writeValueAsString(java.util.Map.of(
                        "type", "EMBED_BATCH_REQUEST",
                        "requestId", "b" + b,
                        "texts", texts)));
                w.write("\n");
                w.flush();
            }

            assertTrue(awaitCondition(responses, s -> s.contains("EMBED_BATCH_RESPONSE"),
                    120, TimeUnit.SECONDS),
                    "no EMBED_BATCH_RESPONSE within 120s. Tail:\n" + tail(stderrTail));

            w.write(mapper.writeValueAsString(java.util.Map.of(
                    "type", "SHUTDOWN_REQUEST", "requestId", "end")));
            w.write("\n");
            w.flush();
        }

        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "subprocess did not exit cleanly");
        int exit = proc.exitValue();
        assertTrue(exit == 0,
                "subprocess exited " + exit
                        + (exit > 128 ? " (killed by signal " + (exit - 128) + ")" : "")
                        + ". Tail:\n" + tail(stderrTail));
    }

    private static boolean awaitCondition(List<String> lines,
                                          java.util.function.Predicate<String> pred,
                                          long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            synchronized (lines) {
                for (String s : lines) {
                    if (pred.test(s)) {
                        return true;
                    }
                }
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static String tail(List<String> lines) {
        synchronized (lines) {
            return String.join("\n", lines);
        }
    }
}
