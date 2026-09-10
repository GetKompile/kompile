package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void resumedStoreContinuesAfterHighestIndexedStep() throws Exception {
        Path resultDir = tempDir.resolve("tool-results");
        Files.createDirectories(resultDir);
        Path existing = resultDir.resolve("0007-grep.txt");
        Files.writeString(resultDir.resolve("_index.txt"),
                indexLine(7, "grep", "call-old", false, existing),
                StandardCharsets.UTF_8);

        ToolResultStore store = new ToolResultStore("session", resultDir);
        Path saved = store.save("read", "call-new", "{}", "new output", false);

        assertEquals("0008-read.txt", saved.getFileName().toString());
        assertTrue(Files.exists(saved));
    }

    @Test
    void summaryDeduplicatesPathsAndKeepsOnlyRecentEntries() throws Exception {
        Path resultDir = tempDir.resolve("tool-results");
        Files.createDirectories(resultDir);
        StringBuilder index = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            index.append(indexLine(i, "read", "call-" + i, false,
                    resultDir.resolve(String.format("%04d-read.txt", i))));
        }
        Path newest = resultDir.resolve("0006-read.txt");
        index.append(indexLine(7, "read", "call-duplicate", false, newest));
        Files.writeString(resultDir.resolve("_index.txt"), index, StandardCharsets.UTF_8);

        ToolResultStore store = new ToolResultStore("session", resultDir);
        String summary = store.generateResultsSummary(3, 4_096);

        assertFalse(summary.contains("0003-read.txt"));
        assertTrue(summary.contains("0004-read.txt"));
        assertTrue(summary.contains("0005-read.txt"));
        assertTrue(summary.contains("0006-read.txt"));
        assertEquals(1, occurrences(summary, "0006-read.txt"));
        assertTrue(summary.contains("older unique result files omitted"));
    }

    @Test
    void summaryHasAnIndependentCharacterBudget() throws Exception {
        Path resultDir = tempDir.resolve("tool-results");
        Files.createDirectories(resultDir);
        StringBuilder index = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            Path result = resultDir.resolve("result-" + i + "-" + "x".repeat(180) + ".txt");
            index.append(indexLine(i, "long-running-tool", "call-" + i, false, result));
        }
        Files.writeString(resultDir.resolve("_index.txt"), index, StandardCharsets.UTF_8);

        ToolResultStore store = new ToolResultStore("session", resultDir);
        String summary = store.generateResultsSummary(20, 1_024);

        assertTrue(summary.length() <= 1_024);
        assertTrue(summary.contains("result-20-"));
        assertTrue(summary.contains("complete index"));
    }

    private static String indexLine(int step, String toolName, String callId,
                                    boolean error, Path file) {
        return step + "\t" + toolName + "\t" + callId + "\t"
                + (error ? "ERROR" : "OK") + "\t" + file + "\n";
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
