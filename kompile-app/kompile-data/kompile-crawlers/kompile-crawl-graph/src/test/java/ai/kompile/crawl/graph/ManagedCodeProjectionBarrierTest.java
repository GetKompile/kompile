/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ManagedCodeProjectionBarrierTest {

    @Test
    void usesResolvedFactSheetAndDeduplicatesProjects() {
        UnifiedCrawlGraphServiceImpl service = new UnifiedCrawlGraphServiceImpl();
        ManagedCodeProjectionCallback callback = mock(ManagedCodeProjectionCallback.class);
        ReflectionTestUtils.setField(service, "managedCodeProjectionCallback", callback);
        UnifiedCrawlJob job = job(42L, managed("app"), managed("app"), managed("library"));

        service.awaitManagedCodeProjection(job);

        verify(callback).awaitProjection(42L, List.of("app", "library"));
    }

    @Test
    void ordinarySourcesDoNotRequireCallback() {
        UnifiedCrawlGraphServiceImpl service = new UnifiedCrawlGraphServiceImpl();
        ManagedCodeProjectionCallback callback = mock(ManagedCodeProjectionCallback.class);
        ReflectionTestUtils.setField(service, "managedCodeProjectionCallback", callback);
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.DIRECTORY)
                .pathOrUrl("/docs").build();

        service.awaitManagedCodeProjection(job(42L, source));

        verifyNoInteractions(callback);
    }

    @Test
    void managedSourceWithoutCallbackFailsHard() {
        UnifiedCrawlGraphServiceImpl service = new UnifiedCrawlGraphServiceImpl();

        assertThrows(IllegalStateException.class,
                () -> service.awaitManagedCodeProjection(job(42L, managed("app"))));
    }

    private static UnifiedCrawlJob job(Long factSheetId, UnifiedCrawlSource... sources) {
        return UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder()
                .factSheetId(factSheetId).sources(List.of(sources)).build()).build();
    }

    private static UnifiedCrawlSource managed(String projectId) {
        return UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.DIRECTORY)
                .pathOrUrl("/workspace/" + projectId)
                .properties(Map.of("kompileCodeProject", true, "codeProjectId", projectId))
                .build();
    }
}
