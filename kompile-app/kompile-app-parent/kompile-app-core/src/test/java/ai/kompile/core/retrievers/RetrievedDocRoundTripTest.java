/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.retrievers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the crawl-blocking Jackson serde bug where a List<RetrievedDoc> round-trip
 * via the graph RPC path (writeValueAsString → convertValue) threw
 * IllegalArgumentException: exactly one of text or media must be specified.
 *
 * Root cause: the single-arg @JsonCreator read "content" but serialization wrote "text",
 * so deserialization found text=null AND media=null, triggering the invariant check.
 */
class RetrievedDocRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────
    // Reproduce the EXACT failure path: valueToTree → convertValue
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void textDoc_roundTrip_viaGraphRpcPath() throws Exception {
        RetrievedDoc original = RetrievedDoc.builder()
                .id("doc-001")
                .text("Hello world, this is a text document.")
                .metadata("source", "unit-test")
                .metadata("lang", "en")
                .score(0.92)
                .build();

        // Capture serialized form — PRINT for diagnosis
        String json = mapper.writeValueAsString(original);
        System.out.println("[SERIALIZED JSON] " + json);

        // This is the EXACT RPC path: tree = mapper.valueToTree(list), then convertValue
        JsonNode tree = mapper.valueToTree(List.of(original));
        List<RetrievedDoc> back = mapper.convertValue(tree, new TypeReference<List<RetrievedDoc>>() {});

        assertFalse(back.isEmpty(), "deserialized list must not be empty");
        RetrievedDoc doc = back.get(0);

        assertEquals(original.getText(), doc.getText(), "text must survive round-trip");
        assertEquals(original.getId(), doc.getId(), "id must survive round-trip");
        assertEquals(original.getScore(), doc.getScore(), "score must survive round-trip");
        assertEquals(original.getMetadata(), doc.getMetadata(), "metadata must survive round-trip");
        assertTrue(doc.isText(), "isText() must be true after round-trip");
        assertNull(doc.getMedia(), "media must be null for a text doc");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backward-compat: legacy producers that send {"content": "..."}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void textDoc_legacyContentField_deserializes() throws Exception {
        // Simulate a producer that still sends the old "content" field name
        String legacyJson = "{\"id\":\"leg-1\",\"content\":\"legacy text\",\"metadata\":{},\"score\":0.5}";
        RetrievedDoc doc = mapper.readValue(legacyJson, RetrievedDoc.class);
        assertEquals("legacy text", doc.getText(), "@JsonAlias(\"content\") must still work");
        assertEquals("leg-1", doc.getId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Media doc round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mediaDoc_roundTrip_viaGraphRpcPath() throws Exception {
        Media media = new Media(MediaType.IMAGE_PNG, new byte[]{1, 2, 3, 4});
        RetrievedDoc original = RetrievedDoc.builder()
                .id("media-001")
                .media(media)
                .metadata("filename", "test.png")
                .score(0.75)
                .build();

        String json = mapper.writeValueAsString(original);
        System.out.println("[SERIALIZED MEDIA JSON] " + json);

        JsonNode tree = mapper.valueToTree(List.of(original));
        List<RetrievedDoc> back = mapper.convertValue(tree, new TypeReference<List<RetrievedDoc>>() {});

        assertFalse(back.isEmpty());
        RetrievedDoc doc = back.get(0);
        assertFalse(doc.isText(), "isText() must be false for a media doc");
        assertNotNull(doc.getMedia(), "media must survive round-trip");
        assertEquals(MediaType.IMAGE_PNG, doc.getMedia().getMediaType());
        assertArrayEquals(media.getData(), doc.getMedia().getData());
        assertEquals(original.getId(), doc.getId());
        assertEquals(original.getScore(), doc.getScore());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Genuinely empty doc still throws on construction (invariant preserved)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void emptyDoc_constructionThrows() {
        // text=null, media=null → must still throw
        assertThrows(Exception.class, () ->
                RetrievedDoc.builder().id("bad").metadata(Map.of()).build(),
                "building a doc with no text and no media must throw");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The serialized "text" field must be a string, not a boolean from isText()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void textField_serializesAsString_notBoolean() throws Exception {
        RetrievedDoc doc = RetrievedDoc.builder()
                .id("chk-1")
                .text("check text serialization")
                .metadata("k", "v")
                .build();

        JsonNode node = mapper.valueToTree(doc);
        System.out.println("[JSON NODE] " + node);

        assertTrue(node.has("text"), "serialized JSON must contain 'text' field");
        assertTrue(node.get("text").isTextual(),
                "serialized 'text' field must be a string, not boolean (isText() conflict)");
        assertEquals("check text serialization", node.get("text").asText());
    }
}
