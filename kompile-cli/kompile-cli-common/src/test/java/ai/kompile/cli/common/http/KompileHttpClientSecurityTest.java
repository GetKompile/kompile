/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileHttpClientSecurityTest {

    @Test
    void authenticatesIntegrationApisAndOnlyMutatingCrawlRequests() {
        assertTrue(KompileHttpClient.requiresIntegrationCredential(
                "/api/source-providers", false));
        assertTrue(KompileHttpClient.requiresIntegrationCredential(
                "/api/oauth/google/refresh", true));
        assertTrue(KompileHttpClient.requiresIntegrationCredential(
                "/api/unified-crawl/start", true));
        assertTrue(KompileHttpClient.requiresIntegrationCredential(
                "/api/documents/add-discord", true));
        assertFalse(KompileHttpClient.requiresIntegrationCredential(
                "/api/unified-crawl/jobs/1", false));
        assertFalse(KompileHttpClient.requiresIntegrationCredential(
                "/api/chat", true));
    }
}
