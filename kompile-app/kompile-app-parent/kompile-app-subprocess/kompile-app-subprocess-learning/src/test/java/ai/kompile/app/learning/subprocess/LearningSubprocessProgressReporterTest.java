/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.learning.subprocess;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the learning child writes for its launcher: a line per report, the prefix and then a JSON
 * object that names its type once and reads back as the report it was.
 */
class LearningSubprocessProgressReporterTest {

    /** Rejects a key given twice, which a lenient reader lets through. */
    private static final ObjectMapper STRICT_JSON =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Test
    void eachReportIsALineThatNamesItsTypeOnce() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        LearningSubprocessProgressReporter reporter =
                new LearningSubprocessProgressReporter(new PrintStream(written, true, StandardCharsets.UTF_8));

        reporter.reportProgress(2, 4, 0.5);
        reporter.reportCompleted(0.25, "/tmp/embeddings.json", 2, 1);
        reporter.reportFailed("boom");
        List<String> lines;
        reporter.startHeartbeat(10);
        try {
            lines = awaitLines(written, 4);
        } finally {
            reporter.stopHeartbeat();
        }

        List<LearningSubprocessMessage> reports = new ArrayList<>();
        for (String line : lines.subList(0, 4)) {
            assertTrue(line.startsWith(LearningSubprocessMessage.MESSAGE_PREFIX), line);
            reports.add(STRICT_JSON.readValue(line.substring(LearningSubprocessMessage.MESSAGE_PREFIX.length()),
                    LearningSubprocessMessage.class));
        }
        assertEquals(new LearningSubprocessMessage.Progress(2, 4, 0.5, 50.0), reports.get(0));
        assertEquals(new LearningSubprocessMessage.Completed(0.25, "/tmp/embeddings.json", 2, 1), reports.get(1));
        assertEquals(new LearningSubprocessMessage.Failed("boom"), reports.get(2));
        assertTrue(assertInstanceOf(LearningSubprocessMessage.Heartbeat.class, reports.get(3)).timestampMs() > 0,
                lines.get(3));
    }

    /** The lines written so far, once there are at least {@code count} whole ones. */
    private static List<String> awaitLines(ByteArrayOutputStream written, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            String text = written.toString(StandardCharsets.UTF_8);
            // A heartbeat may be part way through its line
            List<String> lines = text.substring(0, text.lastIndexOf('\n') + 1).lines().toList();
            if (lines.size() >= count) {
                return lines;
            }
            assertTrue(System.nanoTime() < deadline, "lines written: " + lines);
            Thread.sleep(10);
        }
    }
}
