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

package ai.kompile.oauth.service.providers;

import ai.kompile.oauth.dto.OAuthProviderInfo;
import ai.kompile.oauth.service.OAuthSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DiscordOAuthHandler}.
 *
 * <p>All tests are self-contained and do not make HTTP calls to Discord.
 * The {@link RestTemplate} and {@link OAuthSettingsService} dependencies are
 * mocked; only the handler's identity, scope, source, and configuration
 * detection logic is exercised.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DiscordOAuthHandler")
class DiscordOAuthHandlerTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private OAuthSettingsService settingsService;

    /**
     * Returns a {@link DiscordOAuthHandler} with no Spring {@code @Value} injections
     * (simulating an unconfigured application where no client-id has been set).
     * The {@link OAuthSettingsService} mock is wired via {@link DiscordOAuthHandler#setSettingsService}.
     */
    private DiscordOAuthHandler handlerWithSettings(OAuthSettingsService svc) {
        DiscordOAuthHandler handler = new DiscordOAuthHandler(restTemplate, new ObjectMapper());
        if (svc != null) {
            handler.setSettingsService(svc);
        }
        return handler;
    }

    /**
     * Returns a handler with no settings service and no Spring-injected values,
     * effectively simulating a completely unconfigured handler (empty client-id).
     */
    private DiscordOAuthHandler unconfiguredHandler() {
        return new DiscordOAuthHandler(restTemplate, new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // Provider identity
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Provider identity")
    class ProviderIdentity {

        @Test
        @DisplayName("getProviderId() returns 'discord'")
        void getProviderIdReturnsDiscord() {
            assertEquals("discord", unconfiguredHandler().getProviderId());
        }

        @Test
        @DisplayName("getDisplayName() returns 'Discord'")
        void getDisplayNameReturnsDiscord() {
            assertEquals("Discord", unconfiguredHandler().getDisplayName());
        }

        @Test
        @DisplayName("getDescription() is not blank")
        void getDescriptionIsNotBlank() {
            String desc = unconfiguredHandler().getDescription();
            assertNotNull(desc);
            assertFalse(desc.isBlank());
        }

        @Test
        @DisplayName("getIcon() is not blank")
        void getIconIsNotBlank() {
            String icon = unconfiguredHandler().getIcon();
            assertNotNull(icon);
            assertFalse(icon.isBlank());
        }

        @Test
        @DisplayName("getColor() is a non-blank hex color")
        void getColorIsHex() {
            String color = unconfiguredHandler().getColor();
            assertNotNull(color);
            assertFalse(color.isBlank());
            assertTrue(color.startsWith("#"),
                    "Color should be a hex value starting with '#', got: " + color);
        }
    }

    // -------------------------------------------------------------------------
    // Required scopes
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getRequiredScopes()")
    class RequiredScopes {

        @Test
        @DisplayName("contains 'identify' scope")
        void containsIdentify() {
            List<String> scopes = unconfiguredHandler().getRequiredScopes();
            assertTrue(scopes.contains("identify"),
                    "Required scopes must include 'identify'. Got: " + scopes);
        }

        @Test
        @DisplayName("contains 'guilds' scope")
        void containsGuilds() {
            List<String> scopes = unconfiguredHandler().getRequiredScopes();
            assertTrue(scopes.contains("guilds"),
                    "Required scopes must include 'guilds'. Got: " + scopes);
        }

        @Test
        @DisplayName("contains 'messages.read' scope")
        void containsMessagesRead() {
            List<String> scopes = unconfiguredHandler().getRequiredScopes();
            assertTrue(scopes.contains("messages.read"),
                    "Required scopes must include 'messages.read'. Got: " + scopes);
        }

        @Test
        @DisplayName("returns at least the four default scopes")
        void returnsAtLeastFourScopes() {
            List<String> scopes = unconfiguredHandler().getRequiredScopes();
            assertTrue(scopes.size() >= 4,
                    "Expected at least 4 default scopes but got: " + scopes);
        }

        @Test
        @DisplayName("uses scopes from OAuthSettingsService when available")
        void usesSettingsServiceScopes() {
            when(settingsService.getScopes(eq("discord"))).thenReturn("identify email");
            // Also stub getClientId/getClientSecret to avoid NPE in isConfigured()
            when(settingsService.getClientId(eq("discord"))).thenReturn(null);

            DiscordOAuthHandler handler = handlerWithSettings(settingsService);
            List<String> scopes = handler.getRequiredScopes();

            assertTrue(scopes.contains("identify"));
            assertTrue(scopes.contains("email"));
            assertEquals(2, scopes.size());
        }

        @Test
        @DisplayName("falls back to defaults when OAuthSettingsService returns null scopes")
        void fallsBackToDefaultsOnNullScopes() {
            when(settingsService.getScopes(eq("discord"))).thenReturn(null);
            when(settingsService.getClientId(eq("discord"))).thenReturn(null);

            DiscordOAuthHandler handler = handlerWithSettings(settingsService);
            List<String> scopes = handler.getRequiredScopes();

            assertFalse(scopes.isEmpty(), "Should fall back to default scopes");
            assertTrue(scopes.contains("identify"));
        }
    }

    // -------------------------------------------------------------------------
    // Related sources
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getRelatedSources()")
    class RelatedSources {

        @Test
        @DisplayName("contains 'discord'")
        void containsDiscord() {
            List<String> sources = unconfiguredHandler().getRelatedSources();
            assertTrue(sources.contains("discord"),
                    "Related sources must include 'discord'. Got: " + sources);
        }

        @Test
        @DisplayName("contains 'discord-history'")
        void containsDiscordHistory() {
            List<String> sources = unconfiguredHandler().getRelatedSources();
            assertTrue(sources.contains("discord-history"),
                    "Related sources must include 'discord-history'. Got: " + sources);
        }

        @Test
        @DisplayName("returns exactly two sources")
        void returnsTwoSources() {
            List<String> sources = unconfiguredHandler().getRelatedSources();
            assertEquals(2, sources.size(),
                    "Expected exactly 2 related sources but got: " + sources);
        }
    }

    // -------------------------------------------------------------------------
    // isConfigured()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isConfigured()")
    class IsConfigured {

        @Test
        @DisplayName("returns false when no client-id is set (no Spring value, no settings service)")
        void returnsFalseWithNoClientId() {
            // Without Spring injection the default @Value resolves to empty string.
            DiscordOAuthHandler handler = unconfiguredHandler();
            assertFalse(handler.isConfigured(),
                    "Handler should not be configured when client-id is absent");
        }

        @Test
        @DisplayName("returns false when OAuthSettingsService returns null client-id")
        void returnsFalseWhenSettingsServiceReturnsNull() {
            when(settingsService.getClientId(eq("discord"))).thenReturn(null);

            DiscordOAuthHandler handler = handlerWithSettings(settingsService);
            assertFalse(handler.isConfigured());
        }

        @Test
        @DisplayName("returns false when OAuthSettingsService returns empty client-id")
        void returnsFalseWhenSettingsServiceReturnsEmpty() {
            when(settingsService.getClientId(eq("discord"))).thenReturn("");

            DiscordOAuthHandler handler = handlerWithSettings(settingsService);
            assertFalse(handler.isConfigured());
        }

        @Test
        @DisplayName("returns true when OAuthSettingsService provides a non-empty client-id")
        void returnsTrueWhenClientIdProvided() {
            when(settingsService.getClientId(eq("discord"))).thenReturn("1234567890");

            DiscordOAuthHandler handler = handlerWithSettings(settingsService);
            assertTrue(handler.isConfigured());
        }

        @Test
        @DisplayName("getNotConfiguredMessage() is not blank when unconfigured")
        void notConfiguredMessageIsNotBlank() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            String msg = handler.getNotConfiguredMessage();
            assertNotNull(msg);
            assertFalse(msg.isBlank());
        }
    }

    // -------------------------------------------------------------------------
    // getProviderInfo()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getProviderInfo()")
    class GetProviderInfo {

        @Test
        @DisplayName("providerId in info matches getProviderId()")
        void providerIdMatches() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            OAuthProviderInfo info = handler.getProviderInfo();
            assertEquals(handler.getProviderId(), info.getProviderId());
        }

        @Test
        @DisplayName("displayName in info matches getDisplayName()")
        void displayNameMatches() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            OAuthProviderInfo info = handler.getProviderInfo();
            assertEquals(handler.getDisplayName(), info.getDisplayName());
        }

        @Test
        @DisplayName("configured flag reflects isConfigured()")
        void configuredFlagMatchesIsConfigured() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            OAuthProviderInfo info = handler.getProviderInfo();
            assertEquals(handler.isConfigured(), info.isConfigured());
        }

        @Test
        @DisplayName("notConfiguredMessage is populated when handler is not configured")
        void notConfiguredMessagePopulatedWhenUnconfigured() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            OAuthProviderInfo info = handler.getProviderInfo();
            assertFalse(info.isConfigured());
            assertNotNull(info.getNotConfiguredMessage());
            assertFalse(info.getNotConfiguredMessage().isBlank());
        }

        @Test
        @DisplayName("notConfiguredMessage is null when handler is configured")
        void notConfiguredMessageNullWhenConfigured() {
            when(settingsService.getClientId(eq("discord"))).thenReturn("my-client-id");

            DiscordOAuthHandler handler = handlerWithSettings(settingsService);
            OAuthProviderInfo info = handler.getProviderInfo();

            assertTrue(info.isConfigured());
            assertNull(info.getNotConfiguredMessage(),
                    "notConfiguredMessage should be null when the handler is configured");
        }

        @Test
        @DisplayName("requiredScopes in info matches getRequiredScopes()")
        void requiredScopesMatch() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            OAuthProviderInfo info = handler.getProviderInfo();
            assertEquals(handler.getRequiredScopes(), info.getRequiredScopes());
        }

        @Test
        @DisplayName("relatedSources in info matches getRelatedSources()")
        void relatedSourcesMatch() {
            DiscordOAuthHandler handler = unconfiguredHandler();
            OAuthProviderInfo info = handler.getProviderInfo();
            assertEquals(handler.getRelatedSources(), info.getRelatedSources());
        }
    }
}
