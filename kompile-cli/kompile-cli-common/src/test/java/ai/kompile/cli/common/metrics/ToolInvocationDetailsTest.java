package ai.kompile.cli.common.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ToolInvocationDetailsTest {
    @TempDir Path temp;

    @Test void retainsExactArgumentsCorrelationAndFullPagedResults() throws Exception {
        ToolInvocationDetails store = new ToolInvocationDetails(temp);
        var context = new ToolInvocationContext("inv-one", "session-one", "transcript-resolved", null,
                "rpc-call-one", null, null, "read", "read", "codex", "mcp-stdio", 1000);
        String output = "α".repeat(ToolInvocationDetails.PAGE_CHARS - 1) + "😀last page";
        store.request(context, "{\"file_path\":\"original.java\"}");
        store.result(context, "reference handle", output, "{\"matches\":1}");
        var detail = store.read("session-one", "inv-one");
        assertTrue(detail.path("available").asBoolean());
        assertEquals("rpc-call-one", detail.path("context").path("clientRequestId").asText());
        assertTrue(detail.path("arguments").path("text").asText().contains("original.java"));
        assertEquals("reference handle", detail.path("output").path("text").asText());
        var first = detail.path("rawOutput");
        assertTrue(first.path("hasMore").asBoolean());
        var next = store.page("session-one", "inv-one", "rawOutput", first.path("nextOffset").asLong());
        assertEquals(output, first.path("text").asText() + next.path("text").asText());
        assertFalse(next.path("hasMore").asBoolean());
        assertFalse(store.read("session-one", "missing").path("available").asBoolean());
    }

    @Test void rejectsTraversalArbitraryFieldsAndSymlinkedDetails() throws Exception {
        ToolInvocationDetails store = new ToolInvocationDetails(temp);
        for (String id : new String[]{"../outside", "..", "/tmp/secret", "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> store.page(id, "inv-one", "output", 0));
        }
        assertThrows(IllegalArgumentException.class, () -> store.page("session", "inv-one", "../../secret", 0));
        assertThrows(IllegalArgumentException.class, () -> store.page("session", "inv-one", "output", -1));
        Files.createDirectories(temp.resolve("details"));
        Files.createSymbolicLink(temp.resolve("details/session"), temp);
        assertThrows(IllegalArgumentException.class, () -> store.read("session", "inv-one"));
    }
}
