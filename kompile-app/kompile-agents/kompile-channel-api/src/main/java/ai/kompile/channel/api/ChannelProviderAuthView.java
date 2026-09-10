/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Non-secret authentication readiness for one conversational channel provider. */
public record ChannelProviderAuthView(
        String providerId,
        Mode mode,
        boolean loginSupported,
        boolean oauthConnected,
        boolean channelScopesGranted,
        List<String> requiredScopes,
        List<String> grantedScopes,
        Set<String> missingCredentialFields,
        Map<String, String> credentialSources,
        String guidance) {

    public ChannelProviderAuthView {
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        grantedScopes = grantedScopes == null ? List.of() : List.copyOf(grantedScopes);
        missingCredentialFields = missingCredentialFields == null
                ? Set.of() : Set.copyOf(missingCredentialFields);
        credentialSources = credentialSources == null ? Map.of() : Map.copyOf(credentialSources);
        guidance = guidance == null ? "" : guidance;
    }

    public boolean credentialReady() {
        return missingCredentialFields.isEmpty()
                && (!loginSupported || oauthConnected && channelScopesGranted);
    }

    public enum Mode {
        OAUTH_PARTIAL,
        BOT_INSTALL,
        MANUAL_SECRET,
        EXTERNAL_SETUP,
        PASSWORD
    }
}
