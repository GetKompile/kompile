/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter;
import ai.kompile.kclaw.gateway.whatsapp.WhatsAppWebhookInbox;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only legacy inventory plus externally authenticated WhatsApp ingress. */
@RestController("kclawChannelController")
@RequestMapping("/api/kclaw/channels")
public class ChannelController {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final String WHATSAPP_SIGNATURE = "X-Hub-Signature-256";

    private final ChannelManager channelManager;
    private final WhatsAppWebhookInbox whatsAppInbox;

    public ChannelController(
            ChannelManager channelManager,
            ObjectProvider<WhatsAppWebhookInbox> whatsAppInboxProvider) {
        this.channelManager = channelManager;
        this.whatsAppInbox = whatsAppInboxProvider.getIfAvailable();
    }

    @GetMapping
    public ResponseEntity<List<ChannelManager.ChannelStatus>> listChannels() {
        return ResponseEntity.ok(channelManager.getStatus());
    }

    @GetMapping("/{connectionName}")
    public ResponseEntity<ChannelManager.ChannelStatus> getChannelStatus(
            @PathVariable String connectionName) {
        return channelManager.getConnectionAdapter(connectionName)
                .map(adapter -> ResponseEntity.ok(new ChannelManager.ChannelStatus(
                        connectionName, adapter.isRunning(), adapter.getAdapterConfig())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/types")
    public ResponseEntity<List<String>> getSupportedChannelTypes() {
        return ResponseEntity.ok(List.of("telegram", "discord", "slack", "whatsapp", "email"));
    }

    /** Meta webhook verification is routed across named WhatsApp connections by verify token. */
    @GetMapping("/webhook/whatsapp")
    public ResponseEntity<String> verifyWhatsAppWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        for (ChannelAdapter adapter : channelManager.getAdaptersByProvider("whatsapp")) {
            if (adapter instanceof WhatsAppChannelAdapter whatsapp) {
                String result = whatsapp.getApiClient().verifyWebhook(mode, token, challenge);
                if (result != null) return ResponseEntity.ok(result);
            }
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    /** Authenticate, partition, durably enqueue, and acknowledge without running an agent inline. */
    @PostMapping("/webhook/whatsapp")
    public ResponseEntity<Void> receiveWhatsAppWebhook(
            @RequestBody byte[] payload,
            @RequestHeader(name = WHATSAPP_SIGNATURE, required = false) String signature)
            throws IOException {
        if (whatsAppInbox == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Map<String, WhatsAppChannelAdapter> authorized = new LinkedHashMap<>();
        channelManager.getConnectionAdaptersByProvider("whatsapp").forEach((name, adapter) -> {
            if (adapter instanceof WhatsAppChannelAdapter whatsapp
                    && whatsapp.getApiClient().verifyWebhookSignature(payload, signature)) {
                authorized.put(name, whatsapp);
            }
        });
        if (authorized.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> body = MAPPER.readValue(
                payload, new TypeReference<Map<String, Object>>() { });
        List<WebhookEvent> events = splitEvents(body);
        List<RoutedEvent> routed = new ArrayList<>();
        for (WebhookEvent event : events) {
            List<String> destinations = authorized.entrySet().stream()
                    .filter(entry -> java.util.Objects.equals(
                            entry.getValue().getPhoneNumberId(), event.phoneNumberId()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (destinations.isEmpty()) return ResponseEntity.notFound().build();
            if (destinations.size() > 1) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            routed.add(new RoutedEvent(destinations.get(0), event));
        }

        for (RoutedEvent event : routed) {
            whatsAppInbox.enqueue(
                    event.connectionName(), event.event().eventId(), event.event().payload());
        }
        return ResponseEntity.accepted().build();
    }

    @SuppressWarnings("unchecked")
    static List<WebhookEvent> splitEvents(Map<String, Object> body) {
        List<WebhookEvent> result = new ArrayList<>();
        Object entries = body.get("entry");
        if (!(entries instanceof List<?> entryList)) return result;
        for (Object entryObject : entryList) {
            if (!(entryObject instanceof Map<?, ?> entry)) continue;
            Object changes = entry.get("changes");
            if (!(changes instanceof List<?> changeList)) continue;
            for (Object changeObject : changeList) {
                if (!(changeObject instanceof Map<?, ?> change)
                        || !(change.get("value") instanceof Map<?, ?> value)) continue;
                String phoneNumberId = phoneNumberId(value);
                boolean emitted = false;
                Object messages = value.get("messages");
                if (messages instanceof List<?> messageList && !messageList.isEmpty()) {
                    for (Object messageObject : messageList) {
                        if (!(messageObject instanceof Map<?, ?> message)) continue;
                        Map<String, Object> narrowedValue = copyMap(value);
                        narrowedValue.put("messages", List.of(copyMap(message)));
                        narrowedValue.remove("statuses");
                        narrowContacts(narrowedValue, message);
                        Map<String, Object> eventPayload = payload(
                                body, entry, change, narrowedValue);
                        result.add(new WebhookEvent(
                                phoneNumberId,
                                "message:" + requiredOrDigest(message.get("id"), eventPayload),
                                eventPayload));
                        emitted = true;
                    }
                }
                Object statuses = value.get("statuses");
                if (statuses instanceof List<?> statusList && !statusList.isEmpty()) {
                    for (Object statusObject : statusList) {
                        if (!(statusObject instanceof Map<?, ?> status)) continue;
                        Map<String, Object> narrowedValue = copyMap(value);
                        narrowedValue.put("statuses", List.of(copyMap(status)));
                        narrowedValue.remove("messages");
                        Map<String, Object> eventPayload = payload(
                                body, entry, change, narrowedValue);
                        String statusId = String.valueOf(status.get("id")) + ":"
                                + status.get("status") + ":" + status.get("timestamp");
                        result.add(new WebhookEvent(
                                phoneNumberId, "status:" + requiredOrDigest(statusId, eventPayload),
                                eventPayload));
                        emitted = true;
                    }
                }
                if (!emitted) {
                    Map<String, Object> eventPayload = payload(
                            body, entry, change, copyMap(value));
                    result.add(new WebhookEvent(
                            phoneNumberId, "change:" + digest(eventPayload), eventPayload));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> payload(
            Map<String, Object> body,
            Map<?, ?> entry,
            Map<?, ?> change,
            Map<String, Object> value) {
        Map<String, Object> changeCopy = copyMap(change);
        changeCopy.put("value", value);
        Map<String, Object> entryCopy = copyMap(entry);
        entryCopy.put("changes", List.of(changeCopy));
        Map<String, Object> payload = new LinkedHashMap<>();
        if (body.get("object") != null) payload.put("object", body.get("object"));
        payload.put("entry", List.of(entryCopy));
        return Map.copyOf(payload);
    }

    private static String phoneNumberId(Map<?, ?> value) {
        if (!(value.get("metadata") instanceof Map<?, ?> metadata)) return "";
        Object id = metadata.get("phone_number_id");
        return id == null ? "" : id.toString();
    }

    private static void narrowContacts(Map<String, Object> value, Map<?, ?> message) {
        Object contacts = value.get("contacts");
        Object from = message.get("from");
        if (!(contacts instanceof List<?> contactList) || from == null) return;
        List<?> matching = contactList.stream()
                .filter(contact -> contact instanceof Map<?, ?> map
                        && java.util.Objects.equals(String.valueOf(map.get("wa_id")), from.toString()))
                .toList();
        value.put("contacts", matching);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static String requiredOrDigest(Object value, Map<String, Object> payload) {
        String text = value == null ? "" : value.toString().trim();
        return text.isEmpty() || "null".equals(text) ? digest(payload) : text;
    }

    private static String digest(Map<String, Object> payload) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(MAPPER.writeValueAsBytes(payload)));
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not hash WhatsApp event", impossible);
        }
    }

    record WebhookEvent(String phoneNumberId, String eventId, Map<String, Object> payload) {
    }

    private record RoutedEvent(String connectionName, WebhookEvent event) {
    }
}
