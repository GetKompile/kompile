package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolAnnotationsTest {
    @Test
    void presetsRetainEveryHint() {
        assertEquals(new McpToolAnnotations(true, false, true, false), McpToolAnnotations.READ_ONLY);
        assertEquals(new McpToolAnnotations(false, false, false, false), McpToolAnnotations.WRITE);
        assertEquals(new McpToolAnnotations(false, true, false, false), McpToolAnnotations.DESTRUCTIVE);
        assertEquals(new McpToolAnnotations(false, false, false, true), McpToolAnnotations.NETWORK);
        assertEquals(new McpToolAnnotations(false, false, false, true), McpToolAnnotations.DELEGATION);
    }

    @Test
    void allHintCombinationsProduceTheExactBooleanJson() {
        for (int bits = 0; bits < 16; bits++) {
            boolean read = (bits & 1) != 0;
            boolean destructive = (bits & 2) != 0;
            boolean idempotent = (bits & 4) != 0;
            boolean openWorld = (bits & 8) != 0;
            var annotations = new McpToolAnnotations(read, destructive, idempotent, openWorld);
            var node = annotations.toJsonNode();
            assertEquals(read, annotations.readOnlyHint());
            assertEquals(destructive, annotations.destructiveHint());
            assertEquals(idempotent, annotations.idempotentHint());
            assertEquals(openWorld, annotations.openWorldHint());
            assertEquals(4, node.size());
            assertTrue(node.get("readOnlyHint").isBoolean());
            assertTrue(node.get("destructiveHint").isBoolean());
            assertTrue(node.get("idempotentHint").isBoolean());
            assertTrue(node.get("openWorldHint").isBoolean());
            assertEquals("{\"readOnlyHint\":" + read + ",\"destructiveHint\":" + destructive
                    + ",\"idempotentHint\":" + idempotent + ",\"openWorldHint\":" + openWorld + "}",
                    node.toString());
            node.put("readOnlyHint", !read);
            assertEquals(read, annotations.toJsonNode().get("readOnlyHint").booleanValue());
            assertNotSame(node, annotations.toJsonNode());
        }
    }
}
