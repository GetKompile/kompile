/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.confluence;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.oauth.service.providers.AtlassianCloudResourceResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfluenceDocumentLoaderTest {

    @Test
    void resolvesAtlassianCloudResourceMatchingTheSiteUrl() {
        String resources = """
                [
                  {"id":"cloud-other","url":"https://other.atlassian.net"},
                  {"id":"cloud-team","url":"https://team.atlassian.net"}
                ]
                """;

        assertEquals("cloud-team", AtlassianCloudResourceResolver.resolveCloudId(
                JsonUtils.standardMapper(), resources, "https://team.atlassian.net/wiki"));
        assertNull(AtlassianCloudResourceResolver.resolveCloudId(
                JsonUtils.standardMapper(), resources, "https://unknown.atlassian.net/wiki"));
        assertEquals("https://api.atlassian.com/ex/confluence/cloud-team/wiki",
                AtlassianCloudResourceResolver.confluenceApiBase("cloud-team"));
    }
}
