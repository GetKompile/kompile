/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.services.SingleSourceCrawlStarter;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.core.source.provider.SourceProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSourceIngestControllerIntegrationTest {

    private FactSheetService factSheetService;
    private SingleSourceCrawlStarter crawlStarter;
    private SourceProvider jiraProvider;
    private SourceProvider redditProvider;
    private ExternalSourceIngestController controller;

    @BeforeEach
    void setUp() {
        factSheetService = mock(FactSheetService.class);
        crawlStarter = mock(SingleSourceCrawlStarter.class);
        SourceProviderRegistry providerRegistry = mock(SourceProviderRegistry.class);
        jiraProvider = mock(SourceProvider.class);
        redditProvider = mock(SourceProvider.class);

        when(crawlStarter.isAvailable()).thenReturn(true);
        when(factSheetService.getSheetById(7L)).thenReturn(Optional.of(mock(FactSheet.class)));
        when(providerRegistry.getProvider("jira")).thenReturn(jiraProvider);
        when(providerRegistry.getProvider("reddit")).thenReturn(redditProvider);
        when(jiraProvider.isAvailable()).thenReturn(true);
        when(redditProvider.isAvailable()).thenReturn(true);
        when(jiraProvider.requiresAuth()).thenReturn(false);
        when(redditProvider.requiresAuth()).thenReturn(false);
        SingleSourceCrawlStarter.SingleSourceCrawlResult result =
                mock(SingleSourceCrawlStarter.SingleSourceCrawlResult.class);
        when(result.jobId()).thenReturn("job-1");
        when(result.status()).thenReturn("PENDING");
        when(result.factSheetId()).thenReturn(7L);
        when(crawlStarter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class))).thenReturn(result);

        controller = new ExternalSourceIngestController(
                null, null, null, null, null, null, null, null, null,
                null, null, factSheetService, null, crawlStarter, null, providerRegistry);
    }

    @Test
    void jiraRejectsUnsafeSitesAndIncompleteCredentialPairsBeforeStarting() {
        var unsafe = controller.handleAddJira(new ExternalSourceIngestController.AddJiraRequest(
                7L, "http://team.atlassian.net", "user@example.com", "token",
                "APP", null, 25, true, false, null));
        assertEquals(HttpStatus.BAD_REQUEST, unsafe.getStatusCode());

        var incomplete = controller.handleAddJira(new ExternalSourceIngestController.AddJiraRequest(
                7L, "https://team.atlassian.net", "user@example.com", null,
                "APP", null, 25, true, false, null));
        assertEquals(HttpStatus.BAD_REQUEST, incomplete.getStatusCode());
    }

    @Test
    void jiraPinsTheSelectedFactSheetIntoTheCrawlRequest() {
        var response = controller.handleAddJira(new ExternalSourceIngestController.AddJiraRequest(
                7L, "https://team.atlassian.net", null, null,
                "APP", null, 25, true, false, "recursive-character"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<UnifiedCrawlSource> source = ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        ArgumentCaptor<SingleSourceCrawlStarter.SingleSourceCrawlOptions> options =
                ArgumentCaptor.forClass(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class);
        org.mockito.Mockito.verify(crawlStarter).start(anyString(), source.capture(), options.capture());
        assertEquals(DocumentSourceDescriptor.SourceType.JIRA, source.getValue().getSourceType());
        assertEquals(7L, options.getValue().factSheetId());
        assertEquals("APP", source.getValue().getProperties().get("projectKey"));
    }

    @Test
    void redditRequiresOAuthAndValidatesOptionsSynchronously() {
        when(redditProvider.requiresAuth()).thenReturn(true);
        var noAuth = controller.handleAddReddit(new ExternalSourceIngestController.AddRedditRequest(
                7L, "r/java", "hot", "week", 25, true, 2, 10, 0, false, null, null));
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, noAuth.getStatusCode());

        when(redditProvider.requiresAuth()).thenReturn(false);
        var invalidSort = controller.handleAddReddit(new ExternalSourceIngestController.AddRedditRequest(
                7L, "r/java", "oldest", "week", 25, true, 2, 10, 0, false, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, invalidSort.getStatusCode());
    }

    @Test
    void redditPinsTheSelectedFactSheetAndCanonicalSubreddit() {
        var response = controller.handleAddReddit(new ExternalSourceIngestController.AddRedditRequest(
                7L, "R/java", "top", "month", 25, true, 2, 10, 5, false, "jvm", null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<UnifiedCrawlSource> source = ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        ArgumentCaptor<SingleSourceCrawlStarter.SingleSourceCrawlOptions> options =
                ArgumentCaptor.forClass(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class);
        org.mockito.Mockito.verify(crawlStarter).start(anyString(), source.capture(), options.capture());
        assertEquals(DocumentSourceDescriptor.SourceType.REDDIT, source.getValue().getSourceType());
        assertEquals("java", source.getValue().getPathOrUrl());
        assertEquals(7L, options.getValue().factSheetId());
        assertSame(Boolean.TRUE, source.getValue().getProperties().get("includeComments"));
    }
}
