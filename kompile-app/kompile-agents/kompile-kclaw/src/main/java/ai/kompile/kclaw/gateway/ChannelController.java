/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.gateway;

import ai.kompile.gateway.core.gateway.channel.ChannelConfig;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.kclaw.gateway.channel.DefaultWhatsAppApiClient;
import ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController("kclawChannelController")
@RequestMapping("/api/kclaw/channels")
@RequiredArgsConstructor
@ConditionalOnBean(ChannelManager.class)
public class ChannelController {

    private final ChannelManager channelManager;

    @GetMapping
    public ResponseEntity<List<ChannelManager.ChannelStatus>> listChannels() {
        return ResponseEntity.ok(channelManager.getStatus());
    }

    @GetMapping("/{channelName}")
    public ResponseEntity<ChannelManager.ChannelStatus> getChannelStatus(@PathVariable String channelName) {
        return channelManager.getAdapter(channelName)
                .map(adapter -> ResponseEntity.ok(toStatus(adapter)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{channelName}/start")
    public ResponseEntity<Void> startChannel(@PathVariable String channelName) {
        channelManager.startChannel(channelName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{channelName}/stop")
    public ResponseEntity<Void> stopChannel(@PathVariable String channelName) {
        channelManager.stopChannel(channelName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{channelName}/config")
    public ResponseEntity<Void> updateChannelConfig(
            @PathVariable String channelName,
            @RequestBody ChannelConfig config) {
        
        channelManager.getAdapter(channelName).ifPresent(adapter -> {
            if (config.getAdapterConfig() != null) {
                adapter.updateConfig(config.getAdapterConfig());
            }
            applyChannelSpecificConfig(adapter, config);
        });
        
        return ResponseEntity.ok().build();
    }

    @GetMapping("/types")
    public ResponseEntity<List<String>> getSupportedChannelTypes() {
        return ResponseEntity.ok(List.of(
                "telegram", "discord", "slack", "whatsapp", "email"
        ));
    }

    // ═══════════════════════════════════════════════════════════════════
    // WhatsApp Webhook — Meta sends inbound messages here
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/webhook/whatsapp")
    public ResponseEntity<String> verifyWhatsAppWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        return channelManager.getAdapter("whatsapp")
                .map(adapter -> {
                    if (adapter instanceof WhatsAppChannelAdapter waAdapter) {
                        Object apiClient = waAdapter.getApiClient();
                        if (apiClient instanceof DefaultWhatsAppApiClient client) {
                            String result = client.verifyWebhook(mode, token, challenge);
                            if (result != null) {
                                return ResponseEntity.ok(result);
                            }
                        }
                    }
                    return ResponseEntity.status(403).<String>body("Verification failed");
                })
                .orElse(ResponseEntity.status(404).<String>body("WhatsApp channel not configured"));
    }

    @PostMapping("/webhook/whatsapp")
    public ResponseEntity<Void> receiveWhatsAppWebhook(@RequestBody Map<String, Object> body) {
        channelManager.getAdapter("whatsapp").ifPresent(adapter -> {
            if (adapter instanceof WhatsAppChannelAdapter waAdapter) {
                Object apiClient = waAdapter.getApiClient();
                if (apiClient instanceof DefaultWhatsAppApiClient client) {
                    client.processWebhookPayload(body);
                }
            }
        });
        return ResponseEntity.ok().build();
    }

    private ChannelManager.ChannelStatus toStatus(ChannelAdapter adapter) {
        return new ChannelManager.ChannelStatus(
                adapter.getChannelName(),
                adapter.isRunning(),
                adapter.getAdapterConfig()
        );
    }

    private void applyChannelSpecificConfig(ChannelAdapter adapter, ChannelConfig config) {
        String channelName = adapter.getChannelName();
        
        switch (channelName) {
            case "telegram" -> applyTelegramConfig(adapter, config);
            case "discord" -> applyDiscordConfig(adapter, config);
            case "slack" -> applySlackConfig(adapter, config);
            case "whatsapp" -> applyWhatsAppConfig(adapter, config);
            case "email" -> applyEmailConfig(adapter, config);
        }
    }

    private void applyTelegramConfig(ChannelAdapter adapter, ChannelConfig config) {
        if (config.getTelegram() == null) return;
        
        ai.kompile.kclaw.gateway.channel.TelegramChannelAdapter telegramAdapter = 
                (ai.kompile.kclaw.gateway.channel.TelegramChannelAdapter) adapter;
        
        if (config.getTelegram().getBotToken() != null) {
            telegramAdapter.setApiClient(
                    new ai.kompile.gateway.core.gateway.channel.DefaultTelegramApiClient(
                            config.getTelegram().getBotToken()
                    )
            );
        }
        
        config.getTelegram().getAllowedChatIds()
                .forEach(telegramAdapter::addAllowedChat);
    }

    private void applyDiscordConfig(ChannelAdapter adapter, ChannelConfig config) {
        if (config.getDiscord() == null) return;
        
        ai.kompile.kclaw.gateway.channel.DiscordChannelAdapter discordAdapter = 
                (ai.kompile.kclaw.gateway.channel.DiscordChannelAdapter) adapter;
        
        if (config.getDiscord().getBotToken() != null) {
            discordAdapter.setBotToken(config.getDiscord().getBotToken());
            discordAdapter.setApiClient(
                    new ai.kompile.kclaw.gateway.channel.DefaultDiscordApiClient()
            );
        }
        
        config.getDiscord().getAllowedChannelIds()
                .forEach(discordAdapter::addAllowedChannel);
        config.getDiscord().getAllowedGuildIds()
                .forEach(discordAdapter::addAllowedGuild);
    }

    private void applySlackConfig(ChannelAdapter adapter, ChannelConfig config) {
        if (config.getSlack() == null) return;
        
        ai.kompile.kclaw.gateway.channel.SlackChannelAdapter slackAdapter = 
                (ai.kompile.kclaw.gateway.channel.SlackChannelAdapter) adapter;
        
        if (config.getSlack().getBotToken() != null) {
            slackAdapter.setBotToken(config.getSlack().getBotToken());
            slackAdapter.setAppToken(config.getSlack().getAppToken());
            slackAdapter.setApiClient(
                    new ai.kompile.kclaw.gateway.channel.DefaultSlackApiClient()
            );
        }
        
        config.getSlack().getAllowedChannelIds()
                .forEach(slackAdapter::addAllowedChannel);
        slackAdapter.setRespondToAllMessages(config.getSlack().isRespondToAllMessages());
    }

    private void applyWhatsAppConfig(ChannelAdapter adapter, ChannelConfig config) {
        if (config.getWhatsapp() == null) return;
        
        ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter waAdapter = 
                (ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter) adapter;
        
        if (config.getWhatsapp().getAccessToken() != null) {
            waAdapter.setAccessToken(config.getWhatsapp().getAccessToken());
            waAdapter.setPhoneNumberId(config.getWhatsapp().getPhoneNumberId());
            waAdapter.setVerifyToken(config.getWhatsapp().getVerifyToken());
            waAdapter.setApiClient(
                    new ai.kompile.kclaw.gateway.channel.DefaultWhatsAppApiClient()
            );
        }
        
        config.getWhatsapp().getAllowedPhoneNumbers()
                .forEach(waAdapter::addAllowedPhone);
    }

    private void applyEmailConfig(ChannelAdapter adapter, ChannelConfig config) {
        if (config.getEmail() == null) return;
        
        ai.kompile.kclaw.gateway.channel.EmailChannelAdapter emailAdapter = 
                (ai.kompile.kclaw.gateway.channel.EmailChannelAdapter) adapter;
        
        ChannelConfig.EmailConfig email = config.getEmail();
        
        ai.kompile.gateway.core.gateway.channel.EmailClient.EmailConfig emailClientConfig = 
                new ai.kompile.gateway.core.gateway.channel.EmailClient.EmailConfig(
                        email.getImapHost(),
                        email.getImapPort(),
                        email.getUsername(),
                        email.getPassword(),
                        "imaps",
                        email.isUseSsl(),
                        true,
                        email.getSmtpHost(),
                        email.getSmtpPort(),
                        email.getFromAddress(),
                        email.getFromName(),
                        email.getPollIntervalSeconds()
                );
        
        emailAdapter.setEmailConfig(emailClientConfig);
        emailAdapter.setEmailClient(
                new ai.kompile.gateway.core.gateway.channel.DefaultEmailClient()
        );
        
        email.getAllowedSenders()
                .forEach(emailAdapter::addAllowedSender);
    }
}
