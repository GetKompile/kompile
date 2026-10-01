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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gmail;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GmailLoaderImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GmailLoaderImpl loader;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        loader = new GmailLoaderImpl();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── getName() ───────────────────────────────────────────────────────

    @Test
    void getNameReturnsGmailLoader() {
        assertEquals("Gmail Loader", loader.getName());
    }

    // ── supports() ──────────────────────────────────────────────────────

    @Test
    void supportsGmailSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .build();
        assertTrue(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportUrlSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.URL)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportFileSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportEmailSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.EMAIL)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportGoogleWorkspaceSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    // ── load() — validation ─────────────────────────────────────────────

    @Test
    void throwsWhenMetadataIsNull() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .metadata(null)
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void throwsWhenAccessTokenIsMissing() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .metadata(Map.of("gmailQuery", "label:inbox"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void throwsWhenAccessTokenIsBlank() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("accessToken", "   ");
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .metadata(meta)
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    // ── A1: thread mode caps TOTAL messages across threads, not thread count ─

    @Test
    void threadModeCapsTotalMessagesAcrossThreadsNotThreadCount() throws Exception {
        long now = System.currentTimeMillis();
        ObjectNode m1 = buildMessage("m1", "tA", now - 6000, List.of());
        ObjectNode m2 = buildMessage("m2", "tA", now - 5000, List.of());
        ObjectNode m3 = buildMessage("m3", "tA", now - 4000, List.of());
        ObjectNode m4 = buildMessage("m4", "tB", now - 3000, List.of());
        ObjectNode m5 = buildMessage("m5", "tB", now - 2000, List.of());
        ObjectNode m6 = buildMessage("m6", "tB", now - 1000, List.of());

        server = startServer(List.of(), Map.of(), Map.of(),
                List.of("tA", "tB"),
                Map.of("tA", buildThread("tA", List.of(m1, m2, m3)),
                        "tB", buildThread("tB", List.of(m4, m5, m6))));
        GmailApiClient apiClient = fakeClient();

        Map<String, Object> meta = new HashMap<>();
        meta.put("threadMode", true);
        meta.put("maxMessages", 4);
        meta.put("includeAttachments", false);

        List<Document> docs = loader.load(apiClient, meta, null);

        assertEquals(4, docs.size(), "must cap TOTAL messages across threads, not thread count");
        List<String> ids = docs.stream().map(d -> (String) d.getMetadata().get("gmail.messageId")).toList();
        assertEquals(List.of("m1", "m2", "m3", "m4"), ids,
                "should stop mid-thread once the cap is reached, not drop whole threads");
    }

    // ── A2: attachment identity — server-ingestion mode ───────────────────

    @Test
    void serverModeAttachmentDocumentCarriesParentSourcePath() throws Exception {
        long now = System.currentTimeMillis();
        ObjectNode att = attachmentPart("report.pdf", "application/pdf", 1024, "att-1", null, null);
        ObjectNode msg = buildMessage("m1", "t1", now, List.of(att));

        server = startServer(List.of("m1"), Map.of("m1", msg), Map.of(), List.of(), Map.of());
        GmailApiClient apiClient = fakeClient();

        Map<String, Object> meta = new HashMap<>();
        meta.put("includeAttachments", true);

        List<Document> docs = loader.load(apiClient, meta, null);

        assertEquals(2, docs.size(), "message + attachment document");
        Document attDoc = docs.get(1);
        assertEquals("gmail://messages/m1#attachment/1", attDoc.getMetadata().get("source_path"),
                "attachment source_path must be relative to the parent message, not an independent path");
        assertEquals("gmail://messages/m1#attachment/1", attDoc.getMetadata().get("source"));
        assertEquals("gmail://messages/m1", attDoc.getMetadata().get("parent_source_path"));
    }

    // ── C4: attachmentDirectory file-write mode ────────────────────────────

    @Test
    void attachmentDirectoryModeWritesFileAndRecordsMetadataNoSeparateDocument(@TempDir Path tempDir) throws Exception {
        byte[] fileBytes = "hello attachment".getBytes(StandardCharsets.UTF_8);
        ObjectNode att = attachmentPart("hello.txt", "text/plain", fileBytes.length, "att-1", null, null);
        ObjectNode msg = buildMessage("m1", "t1", System.currentTimeMillis(), List.of(att));

        server = startServer(List.of("m1"), Map.of("m1", msg),
                Map.of("att-1", fileBytes), List.of(), Map.of());
        GmailApiClient apiClient = fakeClient();

        Map<String, Object> meta = new HashMap<>();
        meta.put("includeAttachments", true);
        meta.put("attachmentDirectory", tempDir.toString());

        List<Document> docs = loader.load(apiClient, meta, null);

        assertEquals(1, docs.size(), "C4 mode emits no separate attachment Document");
        Document messageDoc = docs.get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) messageDoc.getMetadata().get("attachments");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        Map<String, Object> entry = attachments.get(0);
        assertEquals("hello.txt", entry.get("fileName"));
        assertNull(entry.get("skipped"));
        String relPath = (String) entry.get("path");
        assertNotNull(relPath);

        Path written = tempDir.resolve(relPath);
        assertTrue(Files.exists(written));
        assertArrayEquals(fileBytes, Files.readAllBytes(written));
    }

    @Test
    void attachmentDirectoryModeSkipsNamedInlineCidImage(@TempDir Path tempDir) throws Exception {
        byte[] logoBytes = "logo-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] realBytes = "real-bytes".getBytes(StandardCharsets.UTF_8);
        ObjectNode logoPart = attachmentPart("logo.png", "image/png", logoBytes.length, "att-logo", null, "<logo123>");
        ObjectNode realPart = attachmentPart("invoice.pdf", "application/pdf", realBytes.length, "att-real", null, null);
        ObjectNode msg = buildMessage("m1", "t1", System.currentTimeMillis(), List.of(logoPart, realPart));

        server = startServer(List.of("m1"), Map.of("m1", msg),
                Map.of("att-logo", logoBytes, "att-real", realBytes), List.of(), Map.of());
        GmailApiClient apiClient = fakeClient();

        Map<String, Object> meta = new HashMap<>();
        meta.put("includeAttachments", true);
        meta.put("attachmentDirectory", tempDir.toString());

        List<Document> docs = loader.load(apiClient, meta, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) docs.get(0).getMetadata().get("attachments");
        assertEquals(1, attachments.size(), "inline CID image must not appear at all, saved or skipped");
        assertEquals("invoice.pdf", attachments.get(0).get("fileName"));
    }

    @Test
    void attachmentDirectoryModeSkipsOversizeAttachment(@TempDir Path tempDir) throws Exception {
        byte[] bigBytes = new byte[10];
        ObjectNode att = attachmentPart("big.bin", "application/octet-stream", bigBytes.length, "att-big", null, null);
        ObjectNode msg = buildMessage("m1", "t1", System.currentTimeMillis(), List.of(att));

        server = startServer(List.of("m1"), Map.of("m1", msg),
                Map.of("att-big", bigBytes), List.of(), Map.of());
        GmailApiClient apiClient = fakeClient();

        Map<String, Object> meta = new HashMap<>();
        meta.put("includeAttachments", true);
        meta.put("attachmentDirectory", tempDir.toString());
        meta.put("maxAttachmentBytes", 5L);

        List<Document> docs = loader.load(apiClient, meta, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) docs.get(0).getMetadata().get("attachments");
        assertEquals(1, attachments.size());
        Map<String, Object> entry = attachments.get(0);
        assertNull(entry.get("path"));
        assertNotNull(entry.get("skipped"));
        assertTrue(((String) entry.get("skipped")).contains("exceeds maxAttachmentBytes"));
    }

    // ── A4: shared "since" contract + internalDate client-side filter ─────

    @Test
    void sinceFilterExcludesMessagesOlderThanEffectiveSince() throws Exception {
        Instant since = Instant.now().minus(2, ChronoUnit.DAYS);
        ObjectNode oldMsg = buildMessage("old1", "t1", since.minus(1, ChronoUnit.DAYS).toEpochMilli(), List.of());
        ObjectNode newMsg = buildMessage("new1", "t2", since.plus(1, ChronoUnit.HOURS).toEpochMilli(), List.of());

        server = startServer(List.of("old1", "new1"), Map.of("old1", oldMsg, "new1", newMsg),
                Map.of(), List.of(), Map.of());
        GmailApiClient apiClient = fakeClient();

        Map<String, Object> meta = new HashMap<>();
        meta.put("since", since.toString());
        meta.put("daysBack", 30); // wider than "since" — the LATER bound (since) must win
        meta.put("includeAttachments", false);

        List<Document> docs = loader.load(apiClient, meta, null);

        assertEquals(1, docs.size());
        assertEquals("new1", docs.get(0).getMetadata().get("gmail.messageId"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private GmailApiClient fakeClient() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GmailApiClient("fake-token", baseUrl);
    }

    /** Builds a minimal Gmail API message JSON with a plain-text body and optional attachment parts. */
    private ObjectNode buildMessage(String id, String threadId, long internalDateEpochMillis,
                                     List<ObjectNode> attachmentParts) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("id", id);
        msg.put("threadId", threadId);
        msg.put("internalDate", String.valueOf(internalDateEpochMillis));
        ObjectNode payload = msg.putObject("payload");
        ArrayNode headers = payload.putArray("headers");
        addHeaderNode(headers, "Subject", "Subject " + id);
        addHeaderNode(headers, "From", "a@example.com");
        addHeaderNode(headers, "To", "b@example.com");

        if (attachmentParts.isEmpty()) {
            payload.put("mimeType", "text/plain");
            payload.putObject("body").put("data", base64Url("Body of " + id));
        } else {
            payload.put("mimeType", "multipart/mixed");
            ArrayNode parts = payload.putArray("parts");
            ObjectNode bodyPart = parts.addObject();
            bodyPart.put("mimeType", "text/plain");
            bodyPart.put("filename", "");
            bodyPart.putObject("body").put("data", base64Url("Body of " + id));
            for (ObjectNode att : attachmentParts) {
                parts.add(att);
            }
        }
        return msg;
    }

    private ObjectNode attachmentPart(String filename, String mimeType, int size, String attachmentId,
                                       String inlineDataText, String contentId) {
        ObjectNode part = MAPPER.createObjectNode();
        part.put("mimeType", mimeType);
        part.put("filename", filename);
        ObjectNode body = part.putObject("body");
        body.put("size", size);
        if (attachmentId != null) {
            body.put("attachmentId", attachmentId);
        }
        if (inlineDataText != null) {
            body.put("data", base64Url(inlineDataText));
        }
        if (contentId != null) {
            ArrayNode headers = part.putArray("headers");
            addHeaderNode(headers, "Content-ID", contentId);
        }
        return part;
    }

    private ObjectNode buildThread(String threadId, List<ObjectNode> messages) {
        ObjectNode thread = MAPPER.createObjectNode();
        thread.put("id", threadId);
        ArrayNode msgs = thread.putArray("messages");
        for (ObjectNode m : messages) {
            msgs.add(m);
        }
        return thread;
    }

    private void addHeaderNode(ArrayNode headers, String name, String value) {
        ObjectNode header = headers.addObject();
        header.put("name", name);
        header.put("value", value);
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal in-process fake Gmail REST API (per the shared "in-process fake servers only, never
     * real ... endpoints" test rule), dispatching by path only. Query parameters (maxResults, q,
     * format) are accepted but ignored — {@link GmailApiClient} already trims to maxResults
     * client-side, so a fixed-size fixture list is sufficient for every test above.
     */
    private HttpServer startServer(List<String> messageIdOrder, Map<String, ObjectNode> messagesById,
                                    Map<String, byte[]> attachmentsById, List<String> threadIdOrder,
                                    Map<String, ObjectNode> threadsById) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String response;
            if ("/messages".equals(path)) {
                ObjectNode listResp = MAPPER.createObjectNode();
                ArrayNode arr = listResp.putArray("messages");
                for (String id : messageIdOrder) {
                    arr.addObject().put("id", id);
                }
                response = listResp.toString();
            } else if (path.matches("/messages/[^/]+/attachments/.+")) {
                String attId = path.substring(path.lastIndexOf('/') + 1);
                byte[] data = attachmentsById.get(attId);
                ObjectNode attResp = MAPPER.createObjectNode();
                attResp.put("size", data.length);
                attResp.put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(data));
                response = attResp.toString();
            } else if (path.startsWith("/messages/")) {
                String id = path.substring("/messages/".length());
                response = messagesById.get(id).toString();
            } else if ("/threads".equals(path)) {
                ObjectNode listResp = MAPPER.createObjectNode();
                ArrayNode arr = listResp.putArray("threads");
                for (String id : threadIdOrder) {
                    arr.addObject().put("id", id);
                }
                response = listResp.toString();
            } else if (path.startsWith("/threads/")) {
                String id = path.substring("/threads/".length());
                response = threadsById.get(id).toString();
            } else {
                status = 404;
                response = "{}";
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        srv.start();
        return srv;
    }
}
