/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.slack;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.slack.api.model.Message;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that {@link SlackLoaderImpl} and {@link SlackHistoryLoaderImpl} stamp a
 * per-message source_path (mirroring the pattern already used by {@link SlackCrawler}),
 * so that distinct messages in the same channel no longer collapse onto the same
 * materialized file when there is no other per-message identity metadata.
 */
class SlackSourcePathMetadataTest {

    private Message message(String ts, String text) {
        Message message = new Message();
        message.setTs(ts);
        message.setUser("U123");
        message.setText(text);
        return message;
    }

    // ── SlackLoaderImpl ──────────────────────────────────────────────────

    @Test
    void slackLoaderStampsDistinctSourcePathsPerMessage() {
        SlackLoaderImpl loader = new SlackLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder().build();

        Document doc1 = new Document("first");
        loader.addMetadata(doc1, message("1111111.000100", "first"), "C123", "general", "Alice", descriptor);

        Document doc2 = new Document("second");
        loader.addMetadata(doc2, message("2222222.000200", "second"), "C123", "general", "Alice", descriptor);

        String path1 = (String) doc1.getMetadata().get("source_path");
        String path2 = (String) doc2.getMetadata().get("source_path");

        assertEquals("slack", doc1.getMetadata().get("source"));
        assertEquals("slack", doc2.getMetadata().get("source"));
        assertEquals("slack://channel/C123/message/1111111.000100", path1);
        assertEquals("slack://channel/C123/message/2222222.000200", path2);
        assertNotEquals(path1, path2, "distinct messages must not collapse onto the same source_path");
    }

    // ── SlackHistoryLoaderImpl ───────────────────────────────────────────

    @Test
    void slackHistoryLoaderStampsDistinctSourcePathsPerMessage() {
        SlackHistoryLoaderImpl loader = new SlackHistoryLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder().build();

        Document doc1 = new Document("first");
        loader.addMetadata(doc1, message("1111111.000100", "first"), "C123", "general", "Alice", descriptor, false);

        Document doc2 = new Document("second");
        loader.addMetadata(doc2, message("2222222.000200", "second"), "C123", "general", "Alice", descriptor, false);

        String path1 = (String) doc1.getMetadata().get("source_path");
        String path2 = (String) doc2.getMetadata().get("source_path");

        assertEquals("slack_history", doc1.getMetadata().get("source"));
        assertEquals("slack_history", doc2.getMetadata().get("source"));
        assertEquals("slack://channel/C123/message/1111111.000100", path1);
        assertEquals("slack://channel/C123/message/2222222.000200", path2);
        assertNotEquals(path1, path2, "distinct messages must not collapse onto the same source_path");
    }

    @Test
    void slackHistoryLoaderThreadRepliesGetOwnSourcePathViaOwnTimestamp() {
        SlackHistoryLoaderImpl loader = new SlackHistoryLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder().build();

        Document parent = new Document("parent");
        loader.addMetadata(parent, message("1111111.000100", "parent"), "C123", "general", "Alice", descriptor, false);

        Document reply = new Document("reply");
        loader.addMetadata(reply, message("1111111.000200", "reply"), "C123", "general", "Bob", descriptor, true);

        assertNotEquals(parent.getMetadata().get("source_path"), reply.getMetadata().get("source_path"),
                "a thread reply has its own ts and must get its own source_path");
        assertEquals(Boolean.TRUE, reply.getMetadata().get("is_thread_reply"));
    }
}
