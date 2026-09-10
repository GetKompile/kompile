package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.ProposedTriple;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.repository.ExtractionJobRepository;
import ai.kompile.knowledgegraph.builder.repository.ExtractionLogRepository;
import ai.kompile.knowledgegraph.builder.repository.TripleProposalRepository;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageRegistry;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static ai.kompile.knowledgegraph.builder.domain.ExtractionJob.JobStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real persistence-context regression: an OSIV-style cached job must not undo remote cancellation. */
class ExtractionJobPersistenceTest {
    @Test
    void staleManagedJobCannotPersistLateModelResultsAfterAnotherRequestCancels() {
        try (var factory = new Configuration()
                .addAnnotatedClass(ExtractionJob.class)
                .addAnnotatedClass(TripleProposal.class)
                .addAnnotatedClass(ExtractionLogRecord.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:extraction-" + UUID.randomUUID())
                .setProperty("hibernate.connection.pool_size", "3")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
             var staleContext = factory.openSession();
             var cancellationContext = factory.openSession()) {
            ExtractionJob stale = ExtractionJob.builder().factSheetId(1L).builderType("native-chat").build();
            stale.start();
            staleContext.beginTransaction();
            staleContext.persist(stale);
            staleContext.getTransaction().commit();

            // Another request cancels while the original persistence context waits for its remote model.
            cancellationContext.beginTransaction();
            cancellationContext.find(ExtractionJob.class, stale.getId()).cancel();
            cancellationContext.getTransaction().commit();
            assertEquals(RUNNING, stale.getStatus());

            var jobs = new JpaRepositoryFactory(staleContext).getRepository(ExtractionJobRepository.class);
            var proposals = mock(TripleProposalRepository.class);
            var service = new ExtractionJobService(jobs, proposals, mock(ExtractionLogRepository.class),
                    mock(KnowledgeGraphService.class), mock(GraphStorageRegistry.class),
                    new ObjectMapper(), staleContext);
            staleContext.beginTransaction();
            try {
                // Prove that the pessimistic repository query alone still returns the stale managed entity.
                assertSame(stale, jobs.findByJobIdForUpdate(stale.getJobId()).orElseThrow());
                assertEquals(RUNNING, stale.getStatus());
                assertThrows(CancellationException.class, () -> service.completeJob(stale.getJobId(), 1));
                assertEquals(CANCELLED, stale.getStatus());
                var triple = new ProposedTriple("Alice", "PERSON", "FOUNDED", "Acme", "ORG",
                        0.9, "chunk-1", "document-1", null, Map.of());
                assertThrows(CancellationException.class, () -> service.createProposals(stale, List.of(triple)));
                service.updateJobProgress(stale.getJobId(), 1, 1);
                service.failJob(stale.getJobId(), "late model failure");
                verifyNoInteractions(proposals);
                staleContext.getTransaction().commit();
            } finally {
                if (staleContext.getTransaction().isActive()) staleContext.getTransaction().rollback();
            }
            try (var verification = factory.openSession()) {
                ExtractionJob stored = verification.find(ExtractionJob.class, stale.getId());
                assertEquals(CANCELLED, stored.getStatus());
                assertEquals(0, stored.getProposalsCreated());
                assertEquals(0, stored.getProcessedChunks());
                assertNull(stored.getErrorMessage());
            }
        }
    }
}
