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

package ai.kompile.app.sync.webhook;

import ai.kompile.app.sync.config.NoteSyncConfigService;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.service.NoteSyncConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Receives Notion webhook events for real-time sync triggering.
 * Verifies HMAC-SHA256 signatures using the webhook secret from JSON config.
 */
@RestController
@RequestMapping("/api/sync/webhook/notion")
public class NotionWebhookController {

    private static final Logger log = LoggerFactory.getLogger(NotionWebhookController.class);

    @Autowired
    private NoteSyncConfigService configService;

    @Autowired
    private NoteSyncConnectionRepository connectionRepository;

    @Autowired
    private NoteSyncConnectionService connectionService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Receives Notion webhook events. Returns 200 immediately, processes async.
     */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> receiveWebhook(
            @RequestHeader(value = "X-Notion-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        if (!configService.isNotionEnabled()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "notion sync disabled"));
        }

        // Verify signature if webhook secret is configured
        String webhookSecret = configService.getConfiguration().getNotionWebhookSecret();
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (!verifySignature(rawBody, signature, webhookSecret)) {
                log.warn("Notion webhook signature verification failed");
                return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
            }
        }

        try {
            Map<String, Object> event = objectMapper.readValue(rawBody, Map.class);
            String type = (String) event.get("type");
            log.info("Received Notion webhook event: {}", type);

            // Extract entity info to find the matching connection
            Map<String, Object> entity = (Map<String, Object>) event.get("entity");
            if (entity != null) {
                String entityId = (String) entity.get("id");
                // Find connection that covers this page's parent
                // For now, trigger sync for all enabled Notion connections
                connectionRepository.findByEnabledTrue().stream()
                        .filter(c -> c.getProvider() == ai.kompile.app.sync.domain.SyncProvider.NOTION)
                        .forEach(c -> {
                            try {
                                connectionService.triggerSync(c.getId());
                            } catch (Exception e) {
                                log.error("Failed to trigger sync for connection {}: {}",
                                        c.getId(), e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to process Notion webhook: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of("status", "received"));
    }

    /**
     * Notion webhook registration verification challenge.
     */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verifyChallenge(@RequestBody Map<String, String> body) {
        String challenge = body.get("challenge");
        if (challenge != null) {
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "no challenge provided"));
    }

    private boolean verifySignature(String body, String signatureHeader, String secret) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            String expected = signatureHeader.substring(7);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return computed.equalsIgnoreCase(expected);
        } catch (Exception e) {
            log.error("Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }
}
