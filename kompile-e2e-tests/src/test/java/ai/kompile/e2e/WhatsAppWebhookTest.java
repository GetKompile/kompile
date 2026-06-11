package ai.kompile.e2e;

import ai.kompile.kclaw.gateway.channel.DefaultWhatsAppApiClient;
import ai.kompile.kclaw.gateway.channel.WhatsAppApiClient;
import ai.kompile.kclaw.gateway.channel.WhatsAppApiClient.WhatsAppMessage;
import ai.kompile.kclaw.gateway.channel.WhatsAppApiClient.WhatsAppMessageHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for DefaultWhatsAppApiClient: webhook verification, inbound message
 * processing, status update handling, handler notification, and lifecycle.
 */
@Tag("e2e")
@DisplayName("WhatsApp Webhook")
class WhatsAppWebhookTest {

    private DefaultWhatsAppApiClient client;
    private TestWhatsAppHandler handler;

    @BeforeEach
    void setUp() {
        HttpClient httpClient = mock(HttpClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        client = new DefaultWhatsAppApiClient(httpClient, objectMapper);
        handler = new TestWhatsAppHandler();
        client.addMessageHandler(handler);
        client.start("test-access-token", "12345678", "my-verify-token");
    }

    @AfterEach
    void tearDown() {
        client.stop();
    }

    // ── Lifecycle ──

    @Test
    @DisplayName("Client is running after start")
    void testClientRunning() {
        assertTrue(client.isRunning());
    }

    @Test
    @DisplayName("Client is not running after stop")
    void testClientStopped() {
        client.stop();
        assertFalse(client.isRunning());
    }

    @Test
    @DisplayName("Start notifies handlers with onReady")
    void testStartNotifiesReady() {
        assertTrue(handler.readyReceived);
    }

    // ── Webhook Verification ──

    @Test
    @DisplayName("Verify webhook succeeds with correct mode and token")
    void testVerifyWebhookSuccess() {
        String result = client.verifyWebhook("subscribe", "my-verify-token", "challenge-123");

        assertEquals("challenge-123", result);
    }

    @Test
    @DisplayName("Verify webhook fails with wrong token")
    void testVerifyWebhookWrongToken() {
        String result = client.verifyWebhook("subscribe", "wrong-token", "challenge-123");

        assertNull(result);
    }

    @Test
    @DisplayName("Verify webhook fails with wrong mode")
    void testVerifyWebhookWrongMode() {
        String result = client.verifyWebhook("unsubscribe", "my-verify-token", "challenge-123");

        assertNull(result);
    }

    @Test
    @DisplayName("Verify webhook fails with null token")
    void testVerifyWebhookNullToken() {
        String result = client.verifyWebhook("subscribe", null, "challenge-123");

        assertNull(result);
    }

    @Test
    @DisplayName("Verify webhook returns challenge string verbatim")
    void testVerifyWebhookReturnsChallenge() {
        String challenge = "abc-def-ghi-123";
        String result = client.verifyWebhook("subscribe", "my-verify-token", challenge);

        assertEquals(challenge, result);
    }

    @Test
    @DisplayName("getVerifyToken returns configured token")
    void testGetVerifyToken() {
        assertEquals("my-verify-token", client.getVerifyToken());
    }

    // ── Inbound Message Processing ──

    @Test
    @DisplayName("Process text message webhook payload")
    void testProcessTextMessage() {
        Map<String, Object> payload = Map.of(
                "entry", List.of(Map.of(
                        "changes", List.of(Map.of(
                                "value", Map.of(
                                        "messages", List.of(Map.of(
                                                "id", "wamid.123",
                                                "from", "15551234567",
                                                "timestamp", "1700000000",
                                                "type", "text",
                                                "text", Map.of("body", "Hello from WhatsApp!")
                                        )),
                                        "contacts", List.of(Map.of(
                                                "profile", Map.of("name", "John Doe")
                                        ))
                                )
                        ))
                ))
        );

        client.processWebhookPayload(payload);

        assertEquals(1, handler.messagesReceived.size());
        WhatsAppMessage msg = handler.messagesReceived.get(0);
        assertEquals("wamid.123", msg.id());
        assertEquals("15551234567", msg.from());
        assertEquals("John Doe", msg.fromName());
        assertEquals("Hello from WhatsApp!", msg.text());
        assertEquals("text", msg.messageType());
        assertEquals(1700000000000L, msg.timestamp());
    }

    @Test
    @DisplayName("Process message without contacts")
    void testProcessMessageWithoutContacts() {
        Map<String, Object> payload = Map.of(
                "entry", List.of(Map.of(
                        "changes", List.of(Map.of(
                                "value", Map.of(
                                        "messages", List.of(Map.of(
                                                "id", "wamid.456",
                                                "from", "15559876543",
                                                "timestamp", "1700000000",
                                                "type", "text",
                                                "text", Map.of("body", "No contact info")
                                        ))
                                )
                        ))
                ))
        );

        client.processWebhookPayload(payload);

        assertEquals(1, handler.messagesReceived.size());
        assertEquals("", handler.messagesReceived.get(0).fromName());
    }

    @Test
    @DisplayName("Process multiple messages in single payload")
    void testProcessMultipleMessages() {
        Map<String, Object> payload = Map.of(
                "entry", List.of(Map.of(
                        "changes", List.of(Map.of(
                                "value", Map.of(
                                        "messages", List.of(
                                                Map.of("id", "msg1", "from", "111",
                                                        "timestamp", "1700000001",
                                                        "type", "text",
                                                        "text", Map.of("body", "First")),
                                                Map.of("id", "msg2", "from", "222",
                                                        "timestamp", "1700000002",
                                                        "type", "text",
                                                        "text", Map.of("body", "Second"))
                                        )
                                )
                        ))
                ))
        );

        client.processWebhookPayload(payload);

        assertEquals(2, handler.messagesReceived.size());
        assertEquals("First", handler.messagesReceived.get(0).text());
        assertEquals("Second", handler.messagesReceived.get(1).text());
    }

    // ── Status Updates ──

    @Test
    @DisplayName("Process status update payload")
    void testProcessStatusUpdate() {
        Map<String, Object> payload = Map.of(
                "entry", List.of(Map.of(
                        "changes", List.of(Map.of(
                                "value", Map.of(
                                        "statuses", List.of(Map.of(
                                                "id", "wamid.status1",
                                                "status", "delivered",
                                                "recipient_id", "15551234567"
                                        ))
                                )
                        ))
                ))
        );

        client.processWebhookPayload(payload);

        assertEquals(1, handler.statusUpdates.size());
        assertEquals("wamid.status1", handler.statusUpdates.get(0).messageId);
        assertEquals("delivered", handler.statusUpdates.get(0).status);
        assertEquals("15551234567", handler.statusUpdates.get(0).recipientId);
    }

    // ── Edge Cases ──

    @Test
    @DisplayName("Empty entry list does not crash")
    void testEmptyEntryList() {
        Map<String, Object> payload = Map.of("entry", List.of());

        assertDoesNotThrow(() -> client.processWebhookPayload(payload));
        assertTrue(handler.messagesReceived.isEmpty());
    }

    @Test
    @DisplayName("Missing entry key does not crash")
    void testMissingEntryKey() {
        Map<String, Object> payload = Map.of("object", "whatsapp_business_account");

        assertDoesNotThrow(() -> client.processWebhookPayload(payload));
        assertTrue(handler.messagesReceived.isEmpty());
    }

    @Test
    @DisplayName("Payload is not processed when client is stopped")
    void testPayloadIgnoredWhenStopped() {
        client.stop();

        Map<String, Object> payload = Map.of(
                "entry", List.of(Map.of(
                        "changes", List.of(Map.of(
                                "value", Map.of(
                                        "messages", List.of(Map.of(
                                                "id", "msg1", "from", "111",
                                                "timestamp", "1700000000",
                                                "type", "text",
                                                "text", Map.of("body", "Should be ignored")
                                        ))
                                )
                        ))
                ))
        );

        client.processWebhookPayload(payload);

        assertTrue(handler.messagesReceived.isEmpty());
    }

    // ── Handler Management ──

    @Test
    @DisplayName("Remove handler stops notifications")
    void testRemoveHandler() {
        client.removeMessageHandler(handler);

        WhatsAppMessage msg = new WhatsAppMessage(
                "id1", "111", "Test", "Hello", System.currentTimeMillis(),
                "id1", "text", null, null
        );

        client.notifyMessage(msg);

        assertTrue(handler.messagesReceived.isEmpty());
    }

    @Test
    @DisplayName("Multiple handlers all receive messages")
    void testMultipleHandlers() {
        TestWhatsAppHandler handler2 = new TestWhatsAppHandler();
        client.addMessageHandler(handler2);

        WhatsAppMessage msg = new WhatsAppMessage(
                "id1", "111", "Test", "Hello", System.currentTimeMillis(),
                "id1", "text", null, null
        );

        client.notifyMessage(msg);

        assertEquals(1, handler.messagesReceived.size());
        assertEquals(1, handler2.messagesReceived.size());
    }

    // ── Test helper ──

    private static class TestWhatsAppHandler implements WhatsAppMessageHandler {
        final List<WhatsAppMessage> messagesReceived = new ArrayList<>();
        final List<StatusUpdate> statusUpdates = new ArrayList<>();
        boolean readyReceived = false;

        @Override
        public void onMessage(WhatsAppMessage message) {
            messagesReceived.add(message);
        }

        @Override
        public void onStatusUpdate(String messageId, String status, String recipientId) {
            statusUpdates.add(new StatusUpdate(messageId, status, recipientId));
        }

        @Override
        public void onReady() {
            readyReceived = true;
        }

        @Override
        public void onError(Throwable error) {
            // no-op for tests
        }

        record StatusUpdate(String messageId, String status, String recipientId) {}
    }
}
