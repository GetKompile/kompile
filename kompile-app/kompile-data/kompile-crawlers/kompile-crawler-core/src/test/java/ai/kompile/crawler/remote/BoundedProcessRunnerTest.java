/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawler.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedProcessRunnerTest {

    @Test
    void drainsLargeOutputWithoutUnboundedCapture() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                fixture("flood"), null, Duration.ofSeconds(10), "test flood");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().length() < 70_000);
        assertTrue(result.truncated());
        assertTrue(result.output().contains("[output truncated]"));
    }

    @Test
    void terminatesAProcessThatExceedsItsDeadline() {
        IOException timeout = assertThrows(IOException.class, () ->
                BoundedProcessRunner.run(
                        fixture("hang"), "x".repeat(1024 * 1024),
                        Duration.ofMillis(100), "test hang"));
        assertTrue(timeout.getMessage().contains("timed out"));
    }

    private static ProcessBuilder fixture(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                OutputProcess.class.getName(), mode);
    }

    public static final class OutputProcess {
        public static void main(String[] args) throws Exception {
            if ("hang".equals(args[0])) {
                Thread.sleep(10_000L);
                return;
            }
            byte[] output = new byte[128 * 1024];
            java.util.Arrays.fill(output, (byte) 'x');
            System.out.write(output);
            System.out.flush();
        }
    }
}
